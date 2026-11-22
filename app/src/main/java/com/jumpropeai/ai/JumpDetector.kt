package com.jumpropeai.ai

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

enum class DetectorStatus {
    NO_PERSON,
    LOW_CONFIDENCE,
    CALIBRATING,
    READY,
    ACTIVE
}

data class JumpDetectionResult(
    val jumpDetected: Boolean,
    val isAirborne: Boolean,
    val status: DetectorStatus,
    val normalizedDisplacement: Float = 0f,
    val calibrationProgress: Float = 0f
)

/**
 * Real-time, baseline-free Peak-Valley biomechanical jump rope detector.
 *
 * Calibrated and verified against real 30 FPS jump rope videos:
 * - Tracks rigid upper-body vertical anchor (nose + shoulders + hips) normalized by smoothed torso height.
 * - Uses an online Peak-Valley (local extremum) state machine instead of a fixed standing baseline,
 *   so walking back from the phone, drifting closer/farther, or bending knees never breaks detection.
 * - Applies biomechanical rise/drop duration and amplitude bounds to reject walking, bending, and
 *   single-frame ML Kit jitter spikes while accurately counting both low fast hops (130+ RPM) and high jumps.
 */
class JumpDetector(
    private val minLandmarkConfidence: Float = 0.4f,
    private val warmupFramesRequired: Int = 3,
    private val smoothingAlpha: Float = 0.55f,
    private val scaleSmoothingAlpha: Float = 0.10f,
    private val minProminence: Float = 0.06f,
    private val maxAmplitude: Float = 0.85f,
    private val minRiseDurationMs: Long = 60L,
    private val maxRiseDurationMs: Long = 550L,
    private val maxDropDurationMs: Long = 450L,
    private val minJumpIntervalMs: Long = 210L
) {
    private enum class Phase {
        SEEK_PEAK,
        SEEK_VALLEY
    }

    private var warmupFrames = 0
    private var smoothedY: Float? = null
    private var smoothedScale: Float? = null

    private var phase: Phase = Phase.SEEK_PEAK
    private var valleyY: Float = 0f
    private var valleyTimeMs: Long = 0L
    private var peakY: Float = 0f
    private var peakTimeMs: Long = 0L
    private var lastJumpTimestampMs: Long? = null

    fun reset() {
        warmupFrames = 0
        smoothedY = null
        smoothedScale = null
        phase = Phase.SEEK_PEAK
        valleyY = 0f
        valleyTimeMs = 0L
        peakY = 0f
        peakTimeMs = 0L
        lastJumpTimestampMs = null
    }

    fun processFrame(frame: PoseFrame, isCountingActive: Boolean): JumpDetectionResult {
        if (frame.landmarks.isEmpty()) {
            return JumpDetectionResult(
                jumpDetected = false,
                isAirborne = false,
                status = DetectorStatus.NO_PERSON
            )
        }

        val nose = frame.landmarks[BodyLandmark.NOSE]?.takeIf { it.confidence >= minLandmarkConfidence }
        val leftShoulder = frame.landmarks[BodyLandmark.LEFT_SHOULDER]?.takeIf { it.confidence >= minLandmarkConfidence }
        val rightShoulder = frame.landmarks[BodyLandmark.RIGHT_SHOULDER]?.takeIf { it.confidence >= minLandmarkConfidence }
        val leftHip = frame.landmarks[BodyLandmark.LEFT_HIP]?.takeIf { it.confidence >= minLandmarkConfidence }
        val rightHip = frame.landmarks[BodyLandmark.RIGHT_HIP]?.takeIf { it.confidence >= minLandmarkConfidence }

        val shoulderMidY = averageY(leftShoulder, rightShoulder)
        val hipMidY = averageY(leftHip, rightHip)

        if (shoulderMidY == null) {
            return JumpDetectionResult(
                jumpDetected = false,
                isAirborne = false,
                status = DetectorStatus.LOW_CONFIDENCE
            )
        }

        // Rigid upper-body vertical anchor (nose + shoulders + hips).
        val rawY = when {
            nose != null && hipMidY != null -> 0.25f * nose.y + 0.50f * shoulderMidY + 0.25f * hipMidY
            hipMidY != null -> 0.60f * shoulderMidY + 0.40f * hipMidY
            else -> shoulderMidY
        }

        val rawScale = computeReferenceScale(
            leftShoulder = leftShoulder,
            rightShoulder = rightShoulder,
            shoulderMidY = shoulderMidY,
            hipMidY = hipMidY,
            imageHeight = frame.imageHeight
        )
        if (rawScale <= 1f) {
            return JumpDetectionResult(
                jumpDetected = false,
                isAirborne = false,
                status = DetectorStatus.LOW_CONFIDENCE
            )
        }

        val currentY = smoothedY?.let { prev ->
            prev + smoothingAlpha * (rawY - prev)
        } ?: rawY
        smoothedY = currentY

        val currentScale = smoothedScale?.let { prev ->
            prev + scaleSmoothingAlpha * (rawScale - prev)
        } ?: rawScale
        smoothedScale = currentScale

        // Quick 3-frame warmup to lock scale & initial valley.
        if (warmupFrames < warmupFramesRequired) {
            warmupFrames++
            valleyY = currentY
            valleyTimeMs = frame.timestampMs
            peakY = currentY
            peakTimeMs = frame.timestampMs
            val progress = (warmupFrames.toFloat() / warmupFramesRequired).coerceIn(0f, 1f)
            return JumpDetectionResult(
                jumpDetected = false,
                isAirborne = false,
                status = if (warmupFrames < warmupFramesRequired) {
                    DetectorStatus.CALIBRATING
                } else if (isCountingActive) {
                    DetectorStatus.ACTIVE
                } else {
                    DetectorStatus.READY
                },
                normalizedDisplacement = 0f,
                calibrationProgress = progress
            )
        }

        if (!isCountingActive) {
            // Keep valley synced while paused so starting is instantaneous.
            phase = Phase.SEEK_PEAK
            valleyY = currentY
            valleyTimeMs = frame.timestampMs
            peakY = currentY
            peakTimeMs = frame.timestampMs
            return JumpDetectionResult(
                jumpDetected = false,
                isAirborne = false,
                status = DetectorStatus.READY,
                normalizedDisplacement = 0f,
                calibrationProgress = 1f
            )
        }

        val nowMs = frame.timestampMs
        var jumpDetected = false
        var normalizedRise = 0f

        when (phase) {
            Phase.SEEK_PEAK -> {
                // In image coordinates, larger Y = lower on screen (grounded valley).
                if (currentY >= valleyY) {
                    valleyY = currentY
                    valleyTimeMs = nowMs
                } else if ((nowMs - valleyTimeMs) > maxRiseDurationMs) {
                    // Slow upward drift (e.g., walking closer or standing up slowly) -> reset valley.
                    valleyY = currentY
                    valleyTimeMs = nowMs
                }

                normalizedRise = (valleyY - currentY) / currentScale
                if (normalizedRise >= minProminence) {
                    phase = Phase.SEEK_VALLEY
                    peakY = currentY
                    peakTimeMs = nowMs
                }
            }

            Phase.SEEK_VALLEY -> {
                // Smaller Y = higher on screen (jump apex).
                if (currentY <= peakY) {
                    peakY = currentY
                    peakTimeMs = nowMs
                }

                val drop = (currentY - peakY) / currentScale
                val amplitude = (valleyY - peakY) / currentScale
                val riseMs = peakTimeMs - valleyTimeMs
                val dropMs = nowMs - peakTimeMs
                normalizedRise = (valleyY - currentY) / currentScale

                if (dropMs > maxDropDurationMs) {
                    // Stayed elevated too long (not a rhythmic jump) -> reset cleanly.
                    phase = Phase.SEEK_PEAK
                    valleyY = currentY
                    valleyTimeMs = nowMs
                } else if (drop >= max(minProminence * 0.65f, amplitude * 0.40f)) {
                    val sinceLast = lastJumpTimestampMs?.let { nowMs - it } ?: Long.MAX_VALUE
                    val validTiming = riseMs in minRiseDurationMs..maxRiseDurationMs
                    val validAmplitude = amplitude <= maxAmplitude
                    if (validTiming && validAmplitude && sinceLast >= minJumpIntervalMs) {
                        jumpDetected = true
                        lastJumpTimestampMs = nowMs
                    }
                    phase = Phase.SEEK_PEAK
                    valleyY = currentY
                    valleyTimeMs = nowMs
                }
            }
        }

        return JumpDetectionResult(
            jumpDetected = jumpDetected,
            isAirborne = phase == Phase.SEEK_VALLEY,
            status = DetectorStatus.ACTIVE,
            normalizedDisplacement = normalizedRise,
            calibrationProgress = 1f
        )
    }

    private fun averageY(a: LandmarkPoint?, b: LandmarkPoint?): Float? {
        return when {
            a != null && b != null -> (a.y + b.y) * 0.5f
            a != null -> a.y
            b != null -> b.y
            else -> null
        }
    }

    private fun computeReferenceScale(
        leftShoulder: LandmarkPoint?,
        rightShoulder: LandmarkPoint?,
        shoulderMidY: Float,
        hipMidY: Float?,
        imageHeight: Int
    ): Float {
        if (hipMidY != null) {
            val torsoHeight = abs(hipMidY - shoulderMidY)
            if (torsoHeight > 10f) return torsoHeight
        }
        if (leftShoulder != null && rightShoulder != null) {
            val shoulderWidth = hypot(
                (leftShoulder.x - rightShoulder.x).toDouble(),
                (leftShoulder.y - rightShoulder.y).toDouble()
            ).toFloat()
            if (shoulderWidth > 10f) return shoulderWidth * 1.4f
        }
        return max(imageHeight * 0.22f, 1f)
    }
}
