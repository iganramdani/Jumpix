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
 * Real-time, scale-invariant jump rope detector using upper-body / torso vertical kinematics.
 *
 * Uses torso height (shoulder-to-hip distance) as a scale reference so jump thresholds remain
 * consistent regardless of how far the user stands from the camera.
 */
class JumpDetector(
    private val minLandmarkConfidence: Float = 0.5f,
    private val calibrationFramesRequired: Int = 10,
    private val smoothingAlpha: Float = 0.45f,
    private val baselineAdaptationAlpha: Float = 0.08f,
    private val liftThreshold: Float = 0.13f,
    private val landThreshold: Float = 0.045f,
    private val minJumpIntervalMs: Long = 220L,
    private val maxAirborneDurationMs: Long = 1200L
) {
    private enum class Phase {
        CALIBRATING,
        GROUNDED,
        AIRBORNE
    }

    private var phase: Phase = Phase.CALIBRATING
    private var calibrationSamples = 0
    private var calibrationSumY = 0f
    private var baselineY = 0f
    private var smoothedY: Float? = null
    private var airborneStartMs: Long = 0L
    private var lastJumpTimestampMs: Long = 0L
    private var hasEverJumped: Boolean = false

    fun reset() {
        phase = Phase.CALIBRATING
        calibrationSamples = 0
        calibrationSumY = 0f
        baselineY = 0f
        smoothedY = null
        airborneStartMs = 0L
        lastJumpTimestampMs = 0L
        hasEverJumped = false
    }

    fun processFrame(frame: PoseFrame, isCountingActive: Boolean): JumpDetectionResult {
        if (frame.landmarks.isEmpty()) {
            return JumpDetectionResult(
                jumpDetected = false,
                isAirborne = false,
                status = DetectorStatus.NO_PERSON
            )
        }

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

        // Anchor point: average of shoulders and hips when both are visible, else shoulders.
        val rawY = if (hipMidY != null) {
            (shoulderMidY + hipMidY) * 0.5f
        } else {
            shoulderMidY
        }

        // Reference scale for distance invariance.
        val referenceScale = computeReferenceScale(
            leftShoulder = leftShoulder,
            rightShoulder = rightShoulder,
            shoulderMidY = shoulderMidY,
            hipMidY = hipMidY,
            imageHeight = frame.imageHeight
        )
        if (referenceScale <= 1f) {
            return JumpDetectionResult(
                jumpDetected = false,
                isAirborne = false,
                status = DetectorStatus.LOW_CONFIDENCE
            )
        }

        // Low-pass filter to reduce frame-to-frame ML Kit jitter.
        val currentY = smoothedY?.let { prev ->
            prev + smoothingAlpha * (rawY - prev)
        } ?: rawY
        smoothedY = currentY

        // Phase 1: Calibration
        if (phase == Phase.CALIBRATING) {
            calibrationSumY += currentY
            calibrationSamples++
            val progress = (calibrationSamples.toFloat() / calibrationFramesRequired).coerceIn(0f, 1f)
            if (calibrationSamples >= calibrationFramesRequired) {
                baselineY = calibrationSumY / calibrationSamples
                phase = Phase.GROUNDED
                return JumpDetectionResult(
                    jumpDetected = false,
                    isAirborne = false,
                    status = if (isCountingActive) DetectorStatus.ACTIVE else DetectorStatus.READY,
                    normalizedDisplacement = 0f,
                    calibrationProgress = 1f
                )
            }
            return JumpDetectionResult(
                jumpDetected = false,
                isAirborne = false,
                status = DetectorStatus.CALIBRATING,
                normalizedDisplacement = 0f,
                calibrationProgress = progress
            )
        }

        // Image Y increases downward; jumping upward produces positive displacement.
        val upwardDisplacement = (baselineY - currentY) / referenceScale
        var jumpDetected = false

        when (phase) {
            Phase.GROUNDED -> {
                if (isCountingActive && upwardDisplacement >= liftThreshold) {
                    phase = Phase.AIRBORNE
                    airborneStartMs = frame.timestampMs
                } else {
                    // Gently adapt standing baseline when user is grounded and near baseline.
                    if (abs(upwardDisplacement) < liftThreshold * 0.85f || !isCountingActive) {
                        baselineY = baselineY * (1f - baselineAdaptationAlpha) + currentY * baselineAdaptationAlpha
                    }
                }
            }

            Phase.AIRBORNE -> {
                if (!isCountingActive) {
                    phase = Phase.GROUNDED
                    baselineY = currentY
                } else if (upwardDisplacement <= landThreshold) {
                    val sinceLastJump = frame.timestampMs - lastJumpTimestampMs
                    if (lastJumpTimestampMs == 0L || sinceLastJump >= minJumpIntervalMs) {
                        jumpDetected = true
                        lastJumpTimestampMs = frame.timestampMs
                        hasEverJumped = true
                    }
                    phase = Phase.GROUNDED
                    // Lightly anchor baseline toward landing level.
                    baselineY = baselineY * 0.85f + currentY * 0.15f
                } else if (frame.timestampMs - airborneStartMs > maxAirborneDurationMs) {
                    // User likely repositioned higher in frame; smoothly recalibrate without false count.
                    phase = Phase.GROUNDED
                    baselineY = currentY
                }
            }

            Phase.CALIBRATING -> Unit
        }

        val status = when {
            isCountingActive && (hasEverJumped || phase == Phase.AIRBORNE) -> DetectorStatus.ACTIVE
            isCountingActive -> DetectorStatus.ACTIVE
            else -> DetectorStatus.READY
        }

        return JumpDetectionResult(
            jumpDetected = jumpDetected,
            isAirborne = phase == Phase.AIRBORNE,
            status = status,
            normalizedDisplacement = upwardDisplacement,
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
        return max(imageHeight * 0.25f, 1f)
    }
}
