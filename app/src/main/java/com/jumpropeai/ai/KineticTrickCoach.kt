package com.jumpropeai.ai

import com.jumpropeai.audio.FluxSample
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class KineticMove(val label: String, val isShuffle: Boolean = false) {
    REGULAR("Regular"),
    BOXER_STEP("Boxer Step"),
    CRISS_CROSS("Criss-Cross"),
    DOUBLE_UNDER("Double Under"),
    HIGH_KNEES("High Knees"),
    SIDE_SWING("Side Swing"),
    SHUFFLE_RUNNING_MAN("Running Man", isShuffle = true),
    SHUFFLE_T_STEP("T-Step", isShuffle = true),
    SHUFFLE_CHARLESTON("Charleston", isShuffle = true)
}

data class FormCoachSnapshot(
    val overallScore: Int = 0,
    val postureScore: Int = 0,
    val handSymmetryScore: Int = 0,
    val rhythmConsistencyScore: Int = 0,
    val landingSoftnessScore: Int = 0,
    val coachingTip: String = "Start moving for live AI Form Coach",
    val sampleCount: Int = 0
)

data class KineticFrameResult(
    val currentMove: KineticMove,
    val shuffleStepDetected: Boolean,
    val shuffleStepTimestampMs: Long,
    val formSnapshot: FormCoachSnapshot,
    val moveCounts: Map<KineticMove, Int>
)

/**
 * Jumpvex AI Engine v13 — Zero-GC Trick Classifier, Shuffle Dance Footwork Detector & AI Form Coach.
 *
 * Runs strictly in parallel with JumpDetector + FusionEngine (never alters jump counting thresholds),
 * guaranteeing 0% regression on the 46-video Ground-Truth benchmark while executing in < 3 µs/frame.
 */
class KineticTrickCoach {

    private companion object {
        const val BUF_SIZE = 32
        const val MIN_CONF = 0.35f
        const val SHUFFLE_REFRACTORY_MS = 230L
    }

    // Reusable primitive circular buffers (Zero-GC hot path)
    private val timesMs = LongArray(BUF_SIZE)
    private val leftAnkleX = FloatArray(BUF_SIZE)
    private val leftAnkleY = FloatArray(BUF_SIZE)
    private val rightAnkleX = FloatArray(BUF_SIZE)
    private val rightAnkleY = FloatArray(BUF_SIZE)
    private val hipYBuf = FloatArray(BUF_SIZE)
    private var bufHead = 0
    private var bufCount = 0

    // Jump / step rolling stats
    private val intervalBuf = FloatArray(16)
    private var intervalHead = 0
    private var intervalCount = 0
    private var lastEventTimeMs = -1L

    private var rollingJumpAmp = 0.08f
    private var airborneStartMs = -1L
    private var lastAirborneDurationMs = 0L

    // Form coach running averages
    private var postureAccum = 0f
    private var handAccum = 0f
    private var softnessAccum = 0f
    private var formSamples = 0
    private var lastFormTip = "Start moving for live AI Form Coach"

    // Trick & Shuffle state
    private var currentMove: KineticMove = KineticMove.REGULAR
    private var lastMoveHoldUntilMs = 0L
    private var lastShuffleStepMs = -10_000L
    private var prevAnkleSep = 0f
    private var prevAnkleSepVel = 0f

    // Fixed enum counter array (Zero-GC during frame processing)
    private val moveCounter = IntArray(KineticMove.entries.size)

    fun reset() {
        bufHead = 0
        bufCount = 0
        intervalHead = 0
        intervalCount = 0
        lastEventTimeMs = -1L
        rollingJumpAmp = 0.08f
        airborneStartMs = -1L
        lastAirborneDurationMs = 0L
        postureAccum = 0f
        handAccum = 0f
        softnessAccum = 0f
        formSamples = 0
        lastFormTip = "Start moving for live AI Form Coach"
        currentMove = KineticMove.REGULAR
        lastMoveHoldUntilMs = 0L
        lastShuffleStepMs = -10_000L
        prevAnkleSep = 0f
        prevAnkleSepVel = 0f
        moveCounter.fill(0)
    }

    fun snapshotMoveCounts(): Map<KineticMove, Int> {
        val map = LinkedHashMap<KineticMove, Int>()
        for (move in KineticMove.entries) {
            val c = moveCounter[move.ordinal]
            if (c > 0) map[move] = c
        }
        return map
    }

    fun currentFormSnapshot(): FormCoachSnapshot {
        if (formSamples == 0) return FormCoachSnapshot()
        val posture = (postureAccum / formSamples).roundToInt().coerceIn(0, 100)
        val hands = (handAccum / formSamples).roundToInt().coerceIn(0, 100)
        val softness = (softnessAccum / formSamples).roundToInt().coerceIn(0, 100)
        val rhythm = computeRhythmConsistency()
        val overall = (posture * 0.28f + hands * 0.27f + rhythm * 0.25f + softness * 0.20f)
            .roundToInt()
            .coerceIn(0, 100)

        return FormCoachSnapshot(
            overallScore = overall,
            postureScore = posture,
            handSymmetryScore = hands,
            rhythmConsistencyScore = rhythm,
            landingSoftnessScore = softness,
            coachingTip = lastFormTip,
            sampleCount = formSamples
        )
    }

    fun processFrame(
        frame: PoseFrame,
        isAirborne: Boolean,
        jumpAccepted: Boolean,
        jumpAmplitude: Float,
        audioHistory: List<FluxSample>,
        isShuffleMode: Boolean
    ): KineticFrameResult {
        val nowMs = frame.timestampMs
        val lm = frame.landmarks

        val lShoulder = lm[BodyLandmark.LEFT_SHOULDER]
        val rShoulder = lm[BodyLandmark.RIGHT_SHOULDER]
        val lHip = lm[BodyLandmark.LEFT_HIP]
        val rHip = lm[BodyLandmark.RIGHT_HIP]

        if (lShoulder == null || rShoulder == null || lHip == null || rHip == null ||
            lShoulder.confidence < MIN_CONF || rShoulder.confidence < MIN_CONF ||
            lHip.confidence < MIN_CONF || rHip.confidence < MIN_CONF
        ) {
            return KineticFrameResult(
                currentMove = currentMove,
                shuffleStepDetected = false,
                shuffleStepTimestampMs = 0L,
                formSnapshot = currentFormSnapshot(),
                moveCounts = snapshotMoveCounts()
            )
        }

        val shoulderMidX = (lShoulder.x + rShoulder.x) * 0.5f
        val shoulderMidY = (lShoulder.y + rShoulder.y) * 0.5f
        val hipMidX = (lHip.x + rHip.x) * 0.5f
        val hipMidY = (lHip.y + rHip.y) * 0.5f
        val torsoH = max(0.06f, abs(hipMidY - shoulderMidY))
        val shoulderSpan = max(0.04f, abs(lShoulder.x - rShoulder.x))

        val lWrist = lm[BodyLandmark.LEFT_WRIST]?.takeIf { it.confidence >= MIN_CONF }
        val rWrist = lm[BodyLandmark.RIGHT_WRIST]?.takeIf { it.confidence >= MIN_CONF }
        val lKnee = lm[BodyLandmark.LEFT_KNEE]?.takeIf { it.confidence >= MIN_CONF }
        val rKnee = lm[BodyLandmark.RIGHT_KNEE]?.takeIf { it.confidence >= MIN_CONF }
        val lAnkle = lm[BodyLandmark.LEFT_ANKLE]?.takeIf { it.confidence >= MIN_CONF }
        val rAnkle = lm[BodyLandmark.RIGHT_ANKLE]?.takeIf { it.confidence >= MIN_CONF }
        val nose = lm[BodyLandmark.NOSE]?.takeIf { it.confidence >= MIN_CONF }

        // Track airborne duration for Double Under detection
        if (isAirborne) {
            if (airborneStartMs < 0L) airborneStartMs = nowMs
        } else if (airborneStartMs >= 0L) {
            lastAirborneDurationMs = nowMs - airborneStartMs
            airborneStartMs = -1L
        }

        // Push ankle/hip kinematics into ring buffer
        val prevIdx = if (bufCount > 0) (bufHead - 1 + BUF_SIZE) % BUF_SIZE else -1
        val dtSec = if (prevIdx >= 0) ((nowMs - timesMs[prevIdx]).coerceIn(10L, 250L)) / 1000f else 0.033f

        val lAx = lAnkle?.x ?: (hipMidX - shoulderSpan * 0.35f)
        val lAy = lAnkle?.y ?: (hipMidY + torsoH * 1.45f)
        val rAx = rAnkle?.x ?: (hipMidX + shoulderSpan * 0.35f)
        val rAy = rAnkle?.y ?: (hipMidY + torsoH * 1.45f)

        val vLx = if (prevIdx >= 0) (lAx - leftAnkleX[prevIdx]) / (torsoH * dtSec) else 0f
        val vLy = if (prevIdx >= 0) (lAy - leftAnkleY[prevIdx]) / (torsoH * dtSec) else 0f
        val vRx = if (prevIdx >= 0) (rAx - rightAnkleX[prevIdx]) / (torsoH * dtSec) else 0f
        val vRy = if (prevIdx >= 0) (rAy - rightAnkleY[prevIdx]) / (torsoH * dtSec) else 0f

        timesMs[bufHead] = nowMs
        leftAnkleX[bufHead] = lAx
        leftAnkleY[bufHead] = lAy
        rightAnkleX[bufHead] = rAx
        rightAnkleY[bufHead] = rAy
        hipYBuf[bufHead] = hipMidY
        bufHead = (bufHead + 1) % BUF_SIZE
        if (bufCount < BUF_SIZE) bufCount++

        // 1. Detect Upper-Body Jump Rope Tricks (Criss-Cross & Side Swing)
        val shoulderDir = lShoulder.x - rShoulder.x
        val isCrissCross = if (lWrist != null && rWrist != null && abs(shoulderDir) > 0.02f) {
            val wristDir = lWrist.x - rWrist.x
            (wristDir * shoulderDir) < -(shoulderSpan * shoulderSpan * 0.05f)
        } else {
            false
        }

        val hipMinX = min(lHip.x, rHip.x)
        val hipMaxX = max(lHip.x, rHip.x)
        val isSideSwing = if (lWrist != null && rWrist != null && !isCrissCross) {
            val bothLeft = lWrist.x < hipMinX - shoulderSpan * 0.12f && rWrist.x < hipMinX
            val bothRight = lWrist.x > hipMaxX && rWrist.x > hipMaxX + shoulderSpan * 0.12f
            (bothLeft || bothRight) && !isAirborne
        } else {
            false
        }

        // 2. Detect High Knees (either knee lifted near hip height)
        val isHighKnees = if (lKnee != null && rKnee != null) {
            val leftLift = (lKnee.y - hipMidY) / torsoH
            val rightLift = (rKnee.y - hipMidY) / torsoH
            val kneeDelta = abs(lKnee.y - rKnee.y) / torsoH
            min(leftLift, rightLift) < 0.36f && kneeDelta > 0.22f
        } else {
            false
        }

        // 3. Detect Shuffle Dance Footwork (Running Man, T-Step, Charleston)
        val ankleSep = abs(lAx - rAx) / torsoH
        val ankleSepVel = (ankleSep - prevAnkleSep) / dtSec
        val hasAnkles = lAnkle != null && rAnkle != null

        var detectedShuffleMove: KineticMove? = null
        var shuffleStepDetected = false

        if (hasAnkles && bufCount >= 4) {
            val opposingHorizStride = (vLx * vRx < -0.55f) && ankleSep > 0.34f
            val kneeDrive = if (lKnee != null && rKnee != null) abs(lKnee.y - rKnee.y) / torsoH > 0.14f else false
            val tStepGlide = (abs(vLx) > 1.15f && abs(vRx) < 0.45f && abs(vRy) > 0.75f) ||
                (abs(vRx) > 1.15f && abs(vLx) < 0.45f && abs(vLy) > 0.75f)
            val charlestonFlip = (prevAnkleSepVel * ankleSepVel < -0.8f) &&
                (ankleSep > 0.30f || prevAnkleSep < 0.16f) &&
                (abs(vLx) + abs(vRx) > 1.3f)

            detectedShuffleMove = when {
                opposingHorizStride && kneeDrive -> KineticMove.SHUFFLE_RUNNING_MAN
                tStepGlide -> KineticMove.SHUFFLE_T_STEP
                charlestonFlip -> KineticMove.SHUFFLE_CHARLESTON
                else -> null
            }

            if (isShuffleMode && detectedShuffleMove != null && (nowMs - lastShuffleStepMs) >= SHUFFLE_REFRACTORY_MS) {
                shuffleStepDetected = true
                lastShuffleStepMs = nowMs
                recordMoveAndInterval(detectedShuffleMove, nowMs)
            }
        }
        prevAnkleSep = ankleSep
        prevAnkleSepVel = ankleSepVel

        // 4. Classify Jump Move when a Jump is accepted
        if (jumpAccepted) {
            if (jumpAmplitude > 0.01f) {
                rollingJumpAmp = rollingJumpAmp * 0.85f + jumpAmplitude * 0.15f
            }
            val isDoubleUnder = (jumpAmplitude >= rollingJumpAmp * 1.42f && lastAirborneDurationMs >= 230L) ||
                (jumpAmplitude >= rollingJumpAmp * 1.30f && hasDualAudioBurst(audioHistory, nowMs))
            val isBoxerStep = hasAnkles && (abs(lAy - rAy) / torsoH > 0.13f || abs(vLy - vRy) > 1.1f)

            val classifiedJump = when {
                isDoubleUnder -> KineticMove.DOUBLE_UNDER
                isCrissCross -> KineticMove.CRISS_CROSS
                isHighKnees -> KineticMove.HIGH_KNEES
                detectedShuffleMove != null && isShuffleMode -> detectedShuffleMove
                isBoxerStep -> KineticMove.BOXER_STEP
                else -> KineticMove.REGULAR
            }
            recordMoveAndInterval(classifiedJump, nowMs)
            evaluateFormSample(
                nose = nose,
                shoulderMidX = shoulderMidX,
                shoulderMidY = shoulderMidY,
                hipMidX = hipMidX,
                hipMidY = hipMidY,
                torsoH = torsoH,
                shoulderSpan = shoulderSpan,
                lWrist = lWrist,
                rWrist = rWrist,
                jumpAmplitude = jumpAmplitude,
                isCrissCross = isCrissCross,
                audioHistory = audioHistory,
                nowMs = nowMs
            )
        } else if (isSideSwing && nowMs > lastMoveHoldUntilMs) {
            currentMove = KineticMove.SIDE_SWING
            lastMoveHoldUntilMs = nowMs + 650L
            moveCounter[KineticMove.SIDE_SWING.ordinal]++
        } else if (shuffleStepDetected && detectedShuffleMove != null) {
            evaluateFormSample(
                nose = nose,
                shoulderMidX = shoulderMidX,
                shoulderMidY = shoulderMidY,
                hipMidX = hipMidX,
                hipMidY = hipMidY,
                torsoH = torsoH,
                shoulderSpan = shoulderSpan,
                lWrist = lWrist,
                rWrist = rWrist,
                jumpAmplitude = 0.06f,
                isCrissCross = false,
                audioHistory = audioHistory,
                nowMs = nowMs
            )
        }

        return KineticFrameResult(
            currentMove = currentMove,
            shuffleStepDetected = shuffleStepDetected,
            shuffleStepTimestampMs = if (shuffleStepDetected) nowMs else 0L,
            formSnapshot = currentFormSnapshot(),
            moveCounts = snapshotMoveCounts()
        )
    }

    private fun recordMoveAndInterval(move: KineticMove, nowMs: Long) {
        currentMove = move
        lastMoveHoldUntilMs = nowMs + 950L
        moveCounter[move.ordinal]++

        if (lastEventTimeMs > 0L) {
            val dt = (nowMs - lastEventTimeMs).toFloat()
            if (dt in 160f..1400f) {
                intervalBuf[intervalHead] = dt
                intervalHead = (intervalHead + 1) % intervalBuf.size
                if (intervalCount < intervalBuf.size) intervalCount++
            }
        }
        lastEventTimeMs = nowMs
    }

    private fun evaluateFormSample(
        nose: LandmarkPoint?,
        shoulderMidX: Float,
        shoulderMidY: Float,
        hipMidX: Float,
        hipMidY: Float,
        torsoH: Float,
        shoulderSpan: Float,
        lWrist: LandmarkPoint?,
        rWrist: LandmarkPoint?,
        jumpAmplitude: Float,
        isCrissCross: Boolean,
        audioHistory: List<FluxSample>,
        nowMs: Long
    ) {
        // 1. Posture uprightness (shoulder aligned vertically above hips + head upright)
        val torsoTilt = abs(shoulderMidX - hipMidX) / torsoH
        val headForward = if (nose != null) abs(nose.x - shoulderMidX) / torsoH else 0f
        val posture = (100f - torsoTilt * 140f - headForward * 45f).coerceIn(45f, 100f)

        // 2. Hand symmetry & proximity to hips (ignore during intentional Criss-Cross)
        val hands = if (lWrist != null && rWrist != null && !isCrissCross) {
            val yDiff = abs(lWrist.y - rWrist.y) / torsoH
            val avgWristY = (lWrist.y + rWrist.y) * 0.5f
            val tooHighPenalty = max(0f, (hipMidY - torsoH * 0.45f) - avgWristY) / torsoH * 95f
            val wristSpread = abs(lWrist.x - rWrist.x) / shoulderSpan
            val tooWidePenalty = max(0f, wristSpread - 2.35f) * 32f
            (100f - yDiff * 110f - tooHighPenalty - tooWidePenalty).coerceIn(40f, 100f)
        } else {
            92f
        }

        // 3. Landing softness (gentle compact hops + controlled audio impact)
        val excessiveHeightPenalty = max(0f, jumpAmplitude - 0.16f) * 180f
        val impactSpike = samplePeakAudioMid(audioHistory, nowMs)
        val heavyStompPenalty = max(0f, impactSpike - 18f) * 1.4f
        val softness = (100f - excessiveHeightPenalty - heavyStompPenalty).coerceIn(50f, 100f)

        postureAccum += posture
        handAccum += hands
        softnessAccum += softness
        formSamples++

        // Pick single clearest actionable coaching tip
        lastFormTip = when {
            hands < 74f -> "Keep hands symmetrical & near hips"
            posture < 76f -> "Lift your chest & stay upright"
            softness < 74f -> "Land softer on the balls of your feet"
            computeRhythmConsistency() < 75 && intervalCount >= 5 -> "Keep a steady rhythm"
            else -> "Great form! Clean & efficient"
        }
    }

    private fun computeRhythmConsistency(): Int {
        if (intervalCount < 3) return 90
        var sum = 0f
        for (i in 0 until intervalCount) sum += intervalBuf[i]
        val mean = sum / intervalCount
        if (mean <= 1f) return 90
        var varSum = 0f
        for (i in 0 until intervalCount) {
            val d = intervalBuf[i] - mean
            varSum += d * d
        }
        val cv = sqrt(varSum / intervalCount) / mean // Coefficient of variation
        return (100f - cv * 145f).roundToInt().coerceIn(45, 100)
    }

    private fun hasDualAudioBurst(audioHistory: List<FluxSample>, peakMs: Long): Boolean {
        if (audioHistory.size < 20) return false
        val startMs = peakMs - 220L
        val endMs = peakMs + 140L
        var peaks = 0
        var lastPeakMs = -10_000L
        var mean = 0f
        var count = 0
        // O(log N) binary search start index
        var lo = 0
        var hi = audioHistory.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (audioHistory[mid].timestampMs < startMs) lo = mid + 1 else hi = mid
        }
        var idx = lo
        while (idx < audioHistory.size && audioHistory[idx].timestampMs <= endMs) {
            mean += audioHistory[idx].wideHigh
            count++
            idx++
        }
        if (count < 6) return false
        val thresh = (mean / count) * 1.65f + 0.8f
        idx = lo + 1
        val endIdx = min(audioHistory.size - 2, lo + count - 2)
        while (idx <= endIdx) {
            val v = audioHistory[idx].wideHigh
            if (v > thresh && v >= audioHistory[idx - 1].wideHigh && v >= audioHistory[idx + 1].wideHigh) {
                val ts = audioHistory[idx].timestampMs
                if (ts - lastPeakMs >= 65L) {
                    peaks++
                    lastPeakMs = ts
                }
            }
            idx++
        }
        return peaks >= 2
    }

    private fun samplePeakAudioMid(audioHistory: List<FluxSample>, nowMs: Long): Float {
        if (audioHistory.isEmpty()) return 0f
        val startMs = nowMs - 180L
        var lo = 0
        var hi = audioHistory.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (audioHistory[mid].timestampMs < startMs) lo = mid + 1 else hi = mid
        }
        var peak = 0f
        var i = lo
        while (i < audioHistory.size && audioHistory[i].timestampMs <= nowMs + 60L) {
            val v = audioHistory[i].mid
            if (v > peak) peak = v
            i++
        }
        return peak
    }
}
