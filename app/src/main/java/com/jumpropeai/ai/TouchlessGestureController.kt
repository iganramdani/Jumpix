package com.jumpropeai.ai

import kotlin.math.abs
import kotlin.math.max

enum class FramingStatus(val guidance: String, val isOptimal: Boolean = false) {
    NO_PERSON("Step into camera view"),
    LOW_CONFIDENCE("Move into brighter light"),
    FEET_CUT_OFF("Step back so your feet stay in frame"),
    HEAD_CUT_OFF("Step back or tilt camera slightly up"),
    OFF_CENTER("Move toward the center of frame"),
    READY("Full body locked", isOptimal = true)
}

enum class GestureTrigger {
    NONE,
    TOGGLE_SESSION
}

data class TouchlessFrameState(
    val framingStatus: FramingStatus = FramingStatus.NO_PERSON,
    val gestureProgress: Float = 0f,
    val trigger: GestureTrigger = GestureTrigger.NONE,
    val isHandRaised: Boolean = false
)

/**
 * Zero-GC Touchless Hand-Gesture Controller & Smart Camera Framing Assistant (AI Engine v14).
 *
 * Allows athletes standing 2–4 meters from the phone to Start or Pause a session hands-free by
 * holding ONE hand clearly above head level for 1.15s while keeping the opposite hand relaxed
 * below shoulder level. Includes strict anti-false-positive guards during active skipping.
 */
class TouchlessGestureController {

    private companion object {
        const val MIN_TORSO_CONF = 0.45f
        const val MIN_WRIST_CONF = 0.50f
        const val MIN_ANKLE_CONF = 0.38f
        const val HOLD_DURATION_MS = 1_150L
        const val DROP_GRACE_MS = 200L
        const val COOLDOWN_MS = 2_600L
        const val PAUSE_QUIET_MS = 1_300L
        const val FRAMING_DEBOUNCE_MS = 350L
    }

    private var holdStartMs: Long = -1L
    private var lastValidRaiseMs: Long = -1L
    private var holdStartHipY: Float = 0f
    private var cooldownUntilMs: Long = 0L
    private var requireHandLowerReset: Boolean = false

    private var stableFraming: FramingStatus = FramingStatus.NO_PERSON
    private var candidateFraming: FramingStatus = FramingStatus.NO_PERSON
    private var candidateFramingSinceMs: Long = 0L

    fun reset() {
        holdStartMs = -1L
        lastValidRaiseMs = -1L
        holdStartHipY = 0f
        cooldownUntilMs = 0L
        requireHandLowerReset = false
        stableFraming = FramingStatus.NO_PERSON
        candidateFraming = FramingStatus.NO_PERSON
        candidateFramingSinceMs = 0L
    }

    fun evaluate(
        frame: PoseFrame,
        isSessionRunning: Boolean,
        isCountingDown: Boolean,
        isAirborne: Boolean,
        lastJumpAcceptedMs: Long,
        gestureEnabled: Boolean
    ): TouchlessFrameState {
        val nowMs = frame.timestampMs
        val rawFraming = assessFraming(frame)
        if (rawFraming != candidateFraming) {
            candidateFraming = rawFraming
            candidateFramingSinceMs = nowMs
        } else if (stableFraming != candidateFraming && (nowMs - candidateFramingSinceMs) >= FRAMING_DEBOUNCE_MS) {
            stableFraming = candidateFraming
        }

        if (!gestureEnabled || isCountingDown || frame.landmarks.isEmpty()) {
            clearHold()
            return TouchlessFrameState(framingStatus = stableFraming)
        }

        val lm = frame.landmarks
        val nose = lm[BodyLandmark.NOSE]?.takeIf { it.confidence >= MIN_TORSO_CONF }
        val lShoulder = lm[BodyLandmark.LEFT_SHOULDER]?.takeIf { it.confidence >= MIN_TORSO_CONF }
        val rShoulder = lm[BodyLandmark.RIGHT_SHOULDER]?.takeIf { it.confidence >= MIN_TORSO_CONF }
        val lHip = lm[BodyLandmark.LEFT_HIP]?.takeIf { it.confidence >= MIN_TORSO_CONF }
        val rHip = lm[BodyLandmark.RIGHT_HIP]?.takeIf { it.confidence >= MIN_TORSO_CONF }

        if (nose == null || lShoulder == null || rShoulder == null || lHip == null || rHip == null) {
            if (holdStartMs >= 0L && (nowMs - lastValidRaiseMs) > DROP_GRACE_MS) {
                clearHold()
            }
            return TouchlessFrameState(framingStatus = stableFraming)
        }

        val shoulderMidY = (lShoulder.y + rShoulder.y) * 0.5f
        val hipMidY = (lHip.y + rHip.y) * 0.5f
        val torsoH = max(12f, abs(hipMidY - shoulderMidY))

        val lWrist = lm[BodyLandmark.LEFT_WRIST]?.takeIf { it.confidence >= MIN_WRIST_CONF }
        val rWrist = lm[BodyLandmark.RIGHT_WRIST]?.takeIf { it.confidence >= MIN_WRIST_CONF }

        val raiseCeilingY = nose.y - torsoH * 0.08f
        val relaxedFloorY = shoulderMidY + torsoH * 0.08f

        val leftRaised = lWrist != null && lWrist.y < raiseCeilingY && (rWrist == null || rWrist.y > relaxedFloorY)
        val rightRaised = rWrist != null && rWrist.y < raiseCeilingY && (lWrist == null || lWrist.y > relaxedFloorY)
        val singleHandSalute = (leftRaised xor rightRaised) && !isAirborne

        if (requireHandLowerReset) {
            if (!leftRaised && !rightRaised) {
                requireHandLowerReset = false
            } else {
                return TouchlessFrameState(framingStatus = stableFraming)
            }
        }

        if (nowMs < cooldownUntilMs) {
            clearHold()
            return TouchlessFrameState(framingStatus = stableFraming)
        }

        // During an active workout, only arm Touchless Pause when athlete has stopped skipping for >= 1.3s
        val quietEnoughToPause = !isSessionRunning ||
            lastJumpAcceptedMs <= 0L ||
            (nowMs - lastJumpAcceptedMs) >= PAUSE_QUIET_MS

        if (singleHandSalute && quietEnoughToPause) {
            if (holdStartMs < 0L) {
                holdStartMs = nowMs
                holdStartHipY = hipMidY
            }
            lastValidRaiseMs = nowMs

            // Reject if athlete is walking/bouncing significantly while raising hand
            val hipDrift = abs(hipMidY - holdStartHipY) / torsoH
            if (hipDrift > 0.16f) {
                holdStartMs = nowMs
                holdStartHipY = hipMidY
                return TouchlessFrameState(
                    framingStatus = stableFraming,
                    gestureProgress = 0.05f,
                    isHandRaised = true
                )
            }

            val heldMs = (nowMs - holdStartMs).coerceAtLeast(0L)
            if (heldMs >= HOLD_DURATION_MS) {
                clearHold()
                cooldownUntilMs = nowMs + COOLDOWN_MS
                requireHandLowerReset = true
                return TouchlessFrameState(
                    framingStatus = stableFraming,
                    gestureProgress = 1f,
                    trigger = GestureTrigger.TOGGLE_SESSION,
                    isHandRaised = true
                )
            }

            val progress = (heldMs.toFloat() / HOLD_DURATION_MS.toFloat()).coerceIn(0f, 0.99f)
            return TouchlessFrameState(
                framingStatus = stableFraming,
                gestureProgress = progress,
                isHandRaised = true
            )
        } else {
            if (holdStartMs >= 0L && (nowMs - lastValidRaiseMs) <= DROP_GRACE_MS && quietEnoughToPause) {
                val heldMs = (nowMs - holdStartMs).coerceAtLeast(0L)
                val progress = (heldMs.toFloat() / HOLD_DURATION_MS.toFloat()).coerceIn(0f, 0.99f)
                return TouchlessFrameState(
                    framingStatus = stableFraming,
                    gestureProgress = progress,
                    isHandRaised = true
                )
            }
            clearHold()
            return TouchlessFrameState(framingStatus = stableFraming)
        }
    }

    private fun clearHold() {
        holdStartMs = -1L
        lastValidRaiseMs = -1L
    }

    private fun assessFraming(frame: PoseFrame): FramingStatus {
        if (frame.landmarks.isEmpty() || frame.imageWidth <= 0 || frame.imageHeight <= 0) {
            return FramingStatus.NO_PERSON
        }
        val lm = frame.landmarks
        val lShoulder = lm[BodyLandmark.LEFT_SHOULDER]
        val rShoulder = lm[BodyLandmark.RIGHT_SHOULDER]
        val lHip = lm[BodyLandmark.LEFT_HIP]
        val rHip = lm[BodyLandmark.RIGHT_HIP]

        if (lShoulder == null || rShoulder == null || lHip == null || rHip == null ||
            lShoulder.confidence < MIN_TORSO_CONF || rShoulder.confidence < MIN_TORSO_CONF
        ) {
            return FramingStatus.LOW_CONFIDENCE
        }

        val w = frame.imageWidth.toFloat()
        val h = frame.imageHeight.toFloat()
        val midX = (lShoulder.x + rShoulder.x + lHip.x + rHip.x) * 0.25f
        if (midX < w * 0.14f || midX > w * 0.86f) {
            return FramingStatus.OFF_CENTER
        }

        val nose = lm[BodyLandmark.NOSE]
        if (nose == null || nose.confidence < MIN_TORSO_CONF || nose.y < h * 0.035f) {
            return FramingStatus.HEAD_CUT_OFF
        }

        val lAnkle = lm[BodyLandmark.LEFT_ANKLE]
        val rAnkle = lm[BodyLandmark.RIGHT_ANKLE]
        if (lAnkle == null || rAnkle == null ||
            lAnkle.confidence < MIN_ANKLE_CONF || rAnkle.confidence < MIN_ANKLE_CONF ||
            max(lAnkle.y, rAnkle.y) > h * 0.965f
        ) {
            return FramingStatus.FEET_CUT_OFF
        }

        return FramingStatus.READY
    }
}
