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

data class JumpCandidate(
    val peakTimeMs: Long,
    val landTimeMs: Long,
    val amplitude: Float,
    val ankleAlternation: Float
)

data class JumpDetectionResult(
    val jumpDetected: Boolean,
    val candidate: JumpCandidate? = null,
    val isAirborne: Boolean,
    val status: DetectorStatus,
    val normalizedDisplacement: Float = 0f,
    val calibrationProgress: Float = 0f
)

/**
 * Full-body kinematic Peak-Valley jump detector (v6).
 *
 * Calibrated on 19 real-world jump rope videos:
 * - Upper-body rigid vertical anchor (nose + shoulders + hips) normalized by smoothed torso height.
 * - Ankle alternation tracking to distinguish Regular Bounce, Boxer Step, and Power/High jumps.
 * - Emits both strong and soft jump candidates (0.045..1.05x torso) so FusionEngine can apply
 *   Self-Learning STFT Audio verification, PLL bridging, and trick-jump support.
 */
class JumpDetector(
    private val minLandmarkConfidence: Float = 0.4f,
    private val warmupFramesRequired: Int = 3,
    private val smoothingAlpha: Float = 0.55f,
    private val scaleSmoothingAlpha: Float = 0.10f,
    private val minProminence: Float = 0.045f,
    private val standaloneProminence: Float = 0.06f,
    private val maxAmplitude: Float = 1.05f,
    private val minRiseDurationMs: Long = 50L,
    private val maxRiseDurationMs: Long = 580L,
    private val maxDropDurationMs: Long = 450L,
    private val minJumpIntervalMs: Long = 200L
) {
    private enum class Phase {
        SEEK_PEAK,
        SEEK_VALLEY
    }

    private var warmupFrames = 0
    private var prevFrameMs: Long? = null
    private var avgFrameDtMs: Float = 33f
    private var smoothedY: Float? = null
    private var smoothedScale: Float? = null

    private var phase: Phase = Phase.SEEK_PEAK
    private var valleyY: Float = 0f
    private var valleyTimeMs: Long = 0L
    private var peakY: Float = 0f
    private var peakTimeMs: Long = 0L
    private var lastJumpTimestampMs: Long? = null
    private val recentAnkleDiffs = ArrayDeque<Float>()

    fun reset() {
        warmupFrames = 0
        prevFrameMs = null
        avgFrameDtMs = 33f
        smoothedY = null
        smoothedScale = null
        phase = Phase.SEEK_PEAK
        valleyY = 0f
        valleyTimeMs = 0L
        peakY = 0f
        peakTimeMs = 0L
        lastJumpTimestampMs = null
        recentAnkleDiffs.clear()
    }

    fun processFrame(frame: PoseFrame, isCountingActive: Boolean): JumpDetectionResult {
        prevFrameMs?.let { pMs ->
            val dtFrame = (frame.timestampMs - pMs).coerceIn(16L, 300L).toFloat()
            avgFrameDtMs = avgFrameDtMs * 0.85f + dtFrame * 0.15f
        }
        prevFrameMs = frame.timestampMs

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
        val leftAnkle = frame.landmarks[BodyLandmark.LEFT_ANKLE]?.takeIf { it.confidence >= minLandmarkConfidence }
        val rightAnkle = frame.landmarks[BodyLandmark.RIGHT_ANKLE]?.takeIf { it.confidence >= minLandmarkConfidence }

        val shoulderMidY = averageY(leftShoulder, rightShoulder)
        val hipMidY = averageY(leftHip, rightHip)

        if (shoulderMidY == null) {
            return JumpDetectionResult(
                jumpDetected = false,
                isAirborne = false,
                status = DetectorStatus.LOW_CONFIDENCE
            )
        }

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

        // FPS-aware adaptive smoothing: preserves 1-frame jump apexes on low-end 8-15 FPS phones
        // while keeping exact 0.55 smoothing on smooth 30 FPS devices.
        val lowFpsFactor = ((avgFrameDtMs - 40f) / 60f).coerceIn(0f, 1f)
        val effectiveAlpha = (smoothingAlpha + 0.33f * lowFpsFactor).coerceAtMost(0.90f)
        val effectiveDropFrac = 0.40f - 0.08f * lowFpsFactor

        val currentY = smoothedY?.let { prev ->
            prev + effectiveAlpha * (rawY - prev)
        } ?: rawY
        smoothedY = currentY

        val currentScale = smoothedScale?.let { prev ->
            prev + scaleSmoothingAlpha * (rawScale - prev)
        } ?: rawScale
        smoothedScale = currentScale

        if (leftAnkle != null && rightAnkle != null) {
            recentAnkleDiffs.addLast(abs(leftAnkle.y - rightAnkle.y) / currentScale)
            while (recentAnkleDiffs.size > 12) recentAnkleDiffs.removeFirst()
        }

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
        var candidate: JumpCandidate? = null
        var normalizedRise = 0f

        when (phase) {
            Phase.SEEK_PEAK -> {
                if (currentY >= valleyY) {
                    valleyY = currentY
                    valleyTimeMs = nowMs
                } else if ((nowMs - valleyTimeMs) > maxRiseDurationMs) {
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
                    phase = Phase.SEEK_PEAK
                    valleyY = currentY
                    valleyTimeMs = nowMs
                } else if (drop >= max(minProminence * 0.65f, amplitude * effectiveDropFrac)) {
                    val sinceLast = lastJumpTimestampMs?.let { nowMs - it } ?: Long.MAX_VALUE
                    val validTiming = riseMs in minRiseDurationMs..maxRiseDurationMs
                    val validAmplitude = amplitude <= maxAmplitude
                    if (validTiming && validAmplitude && sinceLast >= minJumpIntervalMs) {
                        val ankleAlt = computeAnkleAlternation()
                        candidate = JumpCandidate(
                            peakTimeMs = peakTimeMs,
                            landTimeMs = nowMs,
                            amplitude = amplitude,
                            ankleAlternation = ankleAlt
                        )
                        // Standalone Vision-only threshold remains >= 0.06f
                        jumpDetected = amplitude >= standaloneProminence
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
            candidate = candidate,
            isAirborne = phase == Phase.SEEK_VALLEY,
            status = DetectorStatus.ACTIVE,
            normalizedDisplacement = normalizedRise,
            calibrationProgress = 1f
        )
    }

    private fun computeAnkleAlternation(): Float {
        if (recentAnkleDiffs.size < 3) return 0f
        val sorted = recentAnkleDiffs.sorted()
        val p80Idx = (sorted.lastIndex * 0.8f).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[p80Idx]
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
