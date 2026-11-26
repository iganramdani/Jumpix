package com.jumpropeai.ai

import com.jumpropeai.audio.FluxSample
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

enum class KineticMove(val label: String, val isShuffle: Boolean = false) {
    REGULAR("Regular"),
    BOXER_STEP("Boxer Step"),
    HEEL_TOE("Heel-Toe"),
    CRISS_CROSS("Criss-Cross"),
    DOUBLE_UNDER("Double Under"),
    TRIPLE_UNDER("Triple Under"),
    HIGH_KNEES("High Knees"),
    SIDE_SWING("Side Swing"),
    EB_CROSS("EB Cross"),
    TOAD("Toad"),
    BACKWARD_SKIP("Backward Skip"),
    DOUBLE_UNDER_CROSS("DU Criss-Cross"),
    SKIERS("Skiers"),
    BELL_JUMP("Bell Jump"),
    SPIN_360("360° Spin"),
    SHUFFLE_RUNNING_MAN("Running Man", isShuffle = true),
    SHUFFLE_T_STEP("T-Step", isShuffle = true),
    SHUFFLE_CHARLESTON("Charleston", isShuffle = true),
    SHUFFLE_POLLY_POCKET("Polly Pocket", isShuffle = true),
    SHUFFLE_KICK_BALL_CHANGE("Kick-Ball-Change", isShuffle = true)
}

data class TripAutopsyResult(
    val causeCode: String,
    val headline: String,
    val coachingFix: String
)

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
    val moveCounts: Map<KineticMove, Int>,
    val comboCount: Int = 0
)

/**
 * Jumpvex AI Engine v14.0 — Peak-Aligned Heel-to-Toe Foot-Triangle Trick Classifier,
 * Triple Under & Combo Chain Detector, Authentic Melbourne Shuffle Engine & 3D Form Coach (Zero-GC).
 *
 * Key v14 Enhancements over v13.5:
 * - Peak-Aligned Trick Window (`[jumpPeakTimeMs - 110ms .. jumpPeakTimeMs + 110ms]`): eliminates the
 *   ~250ms causal lookahead phase mismatch so fast Criss-Crosses, High Knees & Heel-Toe taps executed
 *   at mid-air apex are captured even after arms uncross / knees descend by landing confirmation!
 * - Triple Under (`TRIPLE_UNDER`) kinematic + triple-acoustic burst classifier.
 * - Combo Chain tracker (`comboCount`) counting distinct freestyle skill transitions.
 * - 3D World Z-Depth forward-lean posture scoring when ML Kit 3D coordinates are present.
 */
class KineticTrickCoach {

    private companion object {
        const val BUF_SIZE = 32
        const val MIN_CONF = 0.45f
        const val FOOT_MIN_CONF = 0.40f
        const val FRONT_VIEW_MIN_SH_RATIO = 0.40f
        const val SHUFFLE_REFRACTORY_MS = 250L
    }

    // Reusable primitive circular buffers (Zero-GC hot path)
    private val timesMs = LongArray(BUF_SIZE)
    private val leftAnkleX = FloatArray(BUF_SIZE)
    private val leftAnkleY = FloatArray(BUF_SIZE)
    private val rightAnkleX = FloatArray(BUF_SIZE)
    private val rightAnkleY = FloatArray(BUF_SIZE)
    private val leftFootYawBuf = FloatArray(BUF_SIZE)
    private val rightFootYawBuf = FloatArray(BUF_SIZE)
    private val leftFootPitchBuf = FloatArray(BUF_SIZE)
    private val rightFootPitchBuf = FloatArray(BUF_SIZE)
    private val hasBothFeetBuf = BooleanArray(BUF_SIZE)
    private val crossMetricBuf = FloatArray(BUF_SIZE)
    private val minKneeLiftBuf = FloatArray(BUF_SIZE)
    private val kneeAsymBuf = FloatArray(BUF_SIZE)
    private val hipXBuf = FloatArray(BUF_SIZE)
    private val hipYBuf = FloatArray(BUF_SIZE)
    private val wristMidXBuf = FloatArray(BUF_SIZE)
    private val wristMidYBuf = FloatArray(BUF_SIZE)
    private var bufHead = 0
    private var bufCount = 0

    // Jump / step rolling stats
    private val intervalBuf = FloatArray(16)
    private var intervalHead = 0
    private var intervalCount = 0
    private var lastEventTimeMs = -1L
    private var lastJumpAcceptedMs = -10_000L

    private var baselineJumpCount = 0
    private var rollingJumpAmp = 0.08f
    private var airborneStartMs = -1L
    private var lastAirborneDurationMs = 0L

    // Real-data temporal confirmation states (eliminate 1-frame noise)
    private var crissCrossStreakFrames = 0
    private var lastBoxerAnkleSign = 0
    private var boxerFlipConfirmCount = 0
    private var sideSwingCooldownUntilMs = 0L

    // Shuffle 2-beat confirmation state (rejects walking false positives)
    private var lastShuffleCandidateMs = -10_000L
    private var pendingShuffleMove: KineticMove? = null
    private var lastShuffleStepMs = -10_000L
    private var prevAnkleSep = 0f
    private var prevAnkleSepVel = 0f
    private var prevToeInOut = 0f
    private var charlestonTwistCount = 0
    private var lastCharlestonTwistMs = -10_000L

    // Combo chain tracker
    private var prevNonRegularMove: KineticMove? = null
    var comboCount: Int = 0
        private set

    // AI Engine v15 Trip Root-Cause Autopsy telemetry (Zero-GC)
    private var lastWristDyNorm: Float = 0f
    private var lastWristSpanRatio: Float = 1.0f
    private var lastJumpClearanceRatio: Float = 1.0f
    private var lastCrossFrameMs: Long = -10_000L
    var lastTripAutopsy: TripAutopsyResult? = null
        private set

    // Form coach running averages
    private var postureAccum = 0f
    private var handAccum = 0f
    private var softnessAccum = 0f
    private var formSamples = 0
    private var lastFormTip = "Start moving for live AI Form Coach"

    // Zero-GC cached immutable snapshots (rebuilt ONLY when a move or form sample changes!)
    private var cachedFormSnapshot = FormCoachSnapshot()
    private var cachedMoveCounts: Map<KineticMove, Int> = emptyMap()

    // Zero-GC last-frame kinematic telemetry (read by optional SessionDiagnosticLogger)
    var lastShRatio: Float = 0f; private set
    var lastAnkSep: Float = 0f; private set
    var lastAnkDy: Float = 0f; private set
    var lastKnAsym: Float = 0f; private set
    var lastVLxTimesVRx: Float = 0f; private set
    var lastHipVx: Float = 0f; private set
    var lastHipStd: Float = 0f; private set
    var lastLPitch: Float = 0f; private set
    var lastRPitch: Float = 0f; private set
    var lastVYawL: Float = 0f; private set
    var lastVYawR: Float = 0f; private set

    // Current move & fixed enum counters
    private var currentMove: KineticMove = KineticMove.REGULAR
    private val moveCounter = IntArray(KineticMove.entries.size)

    fun reset() {
        bufHead = 0
        bufCount = 0
        intervalHead = 0
        intervalCount = 0
        lastEventTimeMs = -1L
        lastJumpAcceptedMs = -10_000L
        baselineJumpCount = 0
        rollingJumpAmp = 0.08f
        airborneStartMs = -1L
        lastAirborneDurationMs = 0L
        crissCrossStreakFrames = 0
        lastBoxerAnkleSign = 0
        boxerFlipConfirmCount = 0
        sideSwingCooldownUntilMs = 0L
        lastShuffleCandidateMs = -10_000L
        pendingShuffleMove = null
        lastShuffleStepMs = -10_000L
        prevAnkleSep = 0f
        prevAnkleSepVel = 0f
        prevToeInOut = 0f
        charlestonTwistCount = 0
        lastCharlestonTwistMs = -10_000L
        prevNonRegularMove = null
        comboCount = 0
        postureAccum = 0f
        handAccum = 0f
        softnessAccum = 0f
        formSamples = 0
        lastFormTip = "Start moving for live AI Form Coach"
        cachedFormSnapshot = FormCoachSnapshot()
        cachedMoveCounts = emptyMap()
        lastShRatio = 0f; lastAnkSep = 0f; lastAnkDy = 0f; lastKnAsym = 0f
        lastVLxTimesVRx = 0f; lastHipVx = 0f; lastHipStd = 0f
        lastLPitch = 0f; lastRPitch = 0f; lastVYawL = 0f; lastVYawR = 0f
        currentMove = KineticMove.REGULAR
        moveCounter.fill(0)
    }

    fun snapshotMoveCounts(): Map<KineticMove, Int> = cachedMoveCounts

    private fun rebuildMoveCountsCache() {
        val map = LinkedHashMap<KineticMove, Int>()
        for (move in KineticMove.entries) {
            val c = moveCounter[move.ordinal]
            if (c > 0) map[move] = c
        }
        cachedMoveCounts = map
    }

    fun currentFormSnapshot(): FormCoachSnapshot = cachedFormSnapshot

    private fun rebuildFormSnapshotCache() {
        if (formSamples == 0) {
            cachedFormSnapshot = FormCoachSnapshot()
            return
        }
        val posture = (postureAccum / formSamples).roundToInt().coerceIn(0, 100)
        val hands = (handAccum / formSamples).roundToInt().coerceIn(0, 100)
        val softness = (softnessAccum / formSamples).roundToInt().coerceIn(0, 100)
        val rhythm = computeRhythmConsistency()
        val overall = (posture * 0.28f + hands * 0.27f + rhythm * 0.25f + softness * 0.20f)
            .roundToInt()
            .coerceIn(0, 100)

        cachedFormSnapshot = FormCoachSnapshot(
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
        isShuffleMode: Boolean,
        jumpPeakTimeMs: Long = frame.timestampMs
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
                moveCounts = snapshotMoveCounts(),
                comboCount = comboCount
            )
        }

        val shoulderMidX = (lShoulder.x + rShoulder.x) * 0.5f
        val shoulderMidY = (lShoulder.y + rShoulder.y) * 0.5f
        val shoulderMidZ = (lShoulder.z + rShoulder.z) * 0.5f
        val hipMidX = (lHip.x + rHip.x) * 0.5f
        val hipMidY = (lHip.y + rHip.y) * 0.5f
        val hipMidZ = (lHip.z + rHip.z) * 0.5f
        val torsoH = max(1e-3f, abs(hipMidY - shoulderMidY))
        val shoulderDir = lShoulder.x - rShoulder.x
        val shoulderSpan = abs(shoulderDir)
        val isFrontFacing = (shoulderSpan / torsoH) >= FRONT_VIEW_MIN_SH_RATIO

        val lWrist = lm[BodyLandmark.LEFT_WRIST]?.takeIf { it.confidence >= MIN_CONF }
        val rWrist = lm[BodyLandmark.RIGHT_WRIST]?.takeIf { it.confidence >= MIN_CONF }
        val lKnee = lm[BodyLandmark.LEFT_KNEE]?.takeIf { it.confidence >= MIN_CONF }
        val rKnee = lm[BodyLandmark.RIGHT_KNEE]?.takeIf { it.confidence >= MIN_CONF }
        val lAnkle = lm[BodyLandmark.LEFT_ANKLE]?.takeIf { it.confidence >= MIN_CONF }
        val rAnkle = lm[BodyLandmark.RIGHT_ANKLE]?.takeIf { it.confidence >= MIN_CONF }
        val lHeel = lm[BodyLandmark.LEFT_HEEL]?.takeIf { it.confidence >= FOOT_MIN_CONF }
        val rHeel = lm[BodyLandmark.RIGHT_HEEL]?.takeIf { it.confidence >= FOOT_MIN_CONF }
        val lToe = lm[BodyLandmark.LEFT_FOOT_INDEX]?.takeIf { it.confidence >= FOOT_MIN_CONF }
        val rToe = lm[BodyLandmark.RIGHT_FOOT_INDEX]?.takeIf { it.confidence >= FOOT_MIN_CONF }
        val nose = lm[BodyLandmark.NOSE]?.takeIf { it.confidence >= MIN_CONF }

        // Track airborne duration for Double & Triple Under detection
        if (isAirborne) {
            if (airborneStartMs < 0L) airborneStartMs = nowMs
        } else if (airborneStartMs >= 0L) {
            lastAirborneDurationMs = nowMs - airborneStartMs
            airborneStartMs = -1L
        }
        val activeFlightMs = if (isAirborne && airborneStartMs >= 0L) {
            max(lastAirborneDurationMs, nowMs - airborneStartMs)
        } else {
            lastAirborneDurationMs
        }

        val prevIdx = if (bufCount > 0) (bufHead - 1 + BUF_SIZE) % BUF_SIZE else -1
        val dtSec = if (prevIdx >= 0) ((nowMs - timesMs[prevIdx]).coerceIn(10L, 250L)) / 1000f else 0.033f

        val lAx = lAnkle?.x ?: (hipMidX - shoulderSpan * 0.35f)
        val lAy = lAnkle?.y ?: (hipMidY + torsoH * 1.45f)
        val rAx = rAnkle?.x ?: (hipMidX + shoulderSpan * 0.35f)
        val rAy = rAnkle?.y ?: (hipMidY + torsoH * 1.45f)
        val wMidX = if (lWrist != null && rWrist != null) (lWrist.x + rWrist.x) * 0.5f else hipMidX
        val wMidY = if (lWrist != null && rWrist != null) (lWrist.y + rWrist.y) * 0.5f else hipMidY

        // Full Heel-to-Toe Foot-Triangle Kinematics
        val hasLeftFoot = lHeel != null && lToe != null
        val hasRightFoot = rHeel != null && rToe != null
        val hasBothFeet = hasLeftFoot && hasRightFoot

        val lFootPitch = if (hasLeftFoot) (lToe!!.y - lHeel!!.y) / torsoH else 0.16f
        val rFootPitch = if (hasRightFoot) (rToe!!.y - rHeel!!.y) / torsoH else 0.16f
        val lFootYaw = if (hasLeftFoot) (lToe!!.x - lHeel!!.x) / torsoH else 0f
        val rFootYaw = if (hasRightFoot) (rToe!!.x - rHeel!!.x) / torsoH else 0f

        val instCrossMetric = if (isFrontFacing && lWrist != null && rWrist != null && shoulderSpan > 1e-3f) {
            ((lWrist.x - rWrist.x) * sign(shoulderDir)) / shoulderSpan
        } else {
            1f
        }
        val instMinKneeLift = if (lKnee != null && rKnee != null) {
            min((lKnee.y - hipMidY) / torsoH, (rKnee.y - hipMidY) / torsoH)
        } else {
            1f
        }
        val knAsymVal = if (lKnee != null && rKnee != null) abs(lKnee.y - rKnee.y) / torsoH else 0f

        val vLx = if (prevIdx >= 0) (lAx - leftAnkleX[prevIdx]) / (torsoH * dtSec) else 0f
        val vLy = if (prevIdx >= 0) (lAy - leftAnkleY[prevIdx]) / (torsoH * dtSec) else 0f
        val vRx = if (prevIdx >= 0) (rAx - rightAnkleX[prevIdx]) / (torsoH * dtSec) else 0f
        val vRy = if (prevIdx >= 0) (rAy - rightAnkleY[prevIdx]) / (torsoH * dtSec) else 0f
        val vYawL = if (prevIdx >= 0 && hasLeftFoot) (lFootYaw - leftFootYawBuf[prevIdx]) / dtSec else 0f
        val vYawR = if (prevIdx >= 0 && hasRightFoot) (rFootYaw - rightFootYawBuf[prevIdx]) / dtSec else 0f
        val hipVx = if (prevIdx >= 0) (hipMidX - hipXBuf[prevIdx]) / (torsoH * dtSec) else 0f
        val wristSpeed = if (prevIdx >= 0) {
            val dwx = (wMidX - wristMidXBuf[prevIdx]) / (torsoH * dtSec)
            val dwy = (wMidY - wristMidYBuf[prevIdx]) / (torsoH * dtSec)
            sqrt(dwx * dwx + dwy * dwy)
        } else {
            0f
        }

        // Store frame into Zero-GC circular buffers BEFORE peak-window evaluation
        timesMs[bufHead] = nowMs
        leftAnkleX[bufHead] = lAx
        leftAnkleY[bufHead] = lAy
        rightAnkleX[bufHead] = rAx
        rightAnkleY[bufHead] = rAy
        leftFootYawBuf[bufHead] = lFootYaw
        rightFootYawBuf[bufHead] = rFootYaw
        leftFootPitchBuf[bufHead] = lFootPitch
        rightFootPitchBuf[bufHead] = rFootPitch
        hasBothFeetBuf[bufHead] = hasBothFeet
        crossMetricBuf[bufHead] = instCrossMetric
        minKneeLiftBuf[bufHead] = instMinKneeLift
        kneeAsymBuf[bufHead] = knAsymVal
        hipXBuf[bufHead] = hipMidX
        hipYBuf[bufHead] = hipMidY
        wristMidXBuf[bufHead] = wMidX
        wristMidYBuf[bufHead] = wMidY
        bufHead = (bufHead + 1) % BUF_SIZE
        if (bufCount < BUF_SIZE) bufCount++

        // 1. Criss-Cross (instantaneous + peak-windowed lookback across +-110ms around jump apex)
        val wristsCrossedNow = instCrossMetric < -0.35f
        if (wristsCrossedNow) crissCrossStreakFrames++ else crissCrossStreakFrames = 0
        val isCrissCrossNow = wristsCrossedNow || crissCrossStreakFrames >= 2

        // 2. Side Swing (active skipping window, front-facing, both wrists clearly past hip + swinging)
        val hipMinX = min(lHip.x, rHip.x)
        val hipMaxX = max(lHip.x, rHip.x)
        val recentlySkipping = (nowMs - lastJumpAcceptedMs) in 250L..1600L
        val isSideSwing = if (
            !isShuffleMode &&
            recentlySkipping &&
            isFrontFacing &&
            !isAirborne &&
            !isCrissCrossNow &&
            nowMs >= sideSwingCooldownUntilMs &&
            lWrist != null &&
            rWrist != null &&
            wristSpeed > 1.15f
        ) {
            val bothFarLeft = lWrist.x < hipMinX - shoulderSpan * 0.22f && rWrist.x < hipMinX - shoulderSpan * 0.05f
            val bothFarRight = lWrist.x > hipMaxX + shoulderSpan * 0.05f && rWrist.x > hipMaxX + shoulderSpan * 0.22f
            bothFarLeft || bothFarRight
        } else {
            false
        }

        // 3. Instantaneous High Knees & Heel-Toe (also evaluated across peak window below when jumpAccepted!)
        val isHighKneesNow = instMinKneeLift < 0.30f && knAsymVal > 0.32f
        val isHeelToeNow = evaluateHeelToeFrame(hasBothFeet, lFootPitch, rFootPitch)

        // 4. Authentic Melbourne Shuffle Dance Footwork (camera-angle aware!)
        val ankleSep = abs(lAx - rAx) / torsoH
        val ankleSepVel = (ankleSep - prevAnkleSep) / dtSec
        val hasAnkles = lAnkle != null && rAnkle != null
        val hipVertStd = computeRecentHipVertStd(torsoH)
        val ankDyVal = if (hasAnkles) abs(lAy - rAy) / torsoH else 0f
        val vProd = vLx * vRx
        val shRatioVal = shoulderSpan / torsoH
        val isSideView = shRatioVal < 0.32f

        lastShRatio = shRatioVal
        lastAnkSep = ankleSep
        lastAnkDy = ankDyVal
        lastKnAsym = knAsymVal
        lastVLxTimesVRx = vProd
        lastHipVx = hipVx
        lastHipStd = hipVertStd
        lastLPitch = lFootPitch
        lastRPitch = rFootPitch
        lastVYawL = vYawL
        lastVYawR = vYawR

        var shuffleStepDetected = false
        var detectedShuffleMove: KineticMove? = null

        if (hasAnkles && bufCount >= 6) {
            if (nowMs - lastCharlestonTwistMs > 750L) {
                charlestonTwistCount = 0
            }

            val sustainedHipShiftX = computeRecentHipShiftX(torsoH)
            val runningManBounceOk = hipVertStd < 0.22f

            val minRmSep = if (isSideView) 0.72f else 0.45f
            val minRmVProd = if (isSideView) -3.80f else -2.20f
            val opposingGlide = (vProd < minRmVProd) && ankleSep > minRmSep
            val kneeDrive = knAsymVal > 0.20f

            val lateralTravel = sustainedHipShiftX >= 0.26f && abs(hipVx) > 0.55f
            val footYawPivot = hasBothFeet && (abs(vYawL) > 1.45f || abs(vYawR) > 1.45f)
            val tStepMotion = lateralTravel && (
                footYawPivot ||
                    (abs(vLx) > 1.35f && abs(vRy) > 0.85f && abs(vRx) < 0.55f) ||
                    (abs(vRx) > 1.35f && abs(vLy) > 0.85f && abs(vLx) < 0.55f)
                )

            val toeInOut = if (hasBothFeet) {
                ((abs(lToe!!.x - rToe!!.x) - abs(lHeel!!.x - rHeel!!.x)) / torsoH)
            } else {
                0f
            }
            val toesFlipped = hasBothFeet && (prevToeInOut * toeInOut < -0.004f) && abs(toeInOut - prevToeInOut) > 0.14f
            if (toesFlipped) {
                if (nowMs - lastCharlestonTwistMs <= 750L) {
                    charlestonTwistCount++
                } else {
                    charlestonTwistCount = 1
                }
                lastCharlestonTwistMs = nowMs
            }
            prevToeInOut = toeInOut
            val charlestonMotion = sustainedHipShiftX < 0.18f &&
                charlestonTwistCount >= 3 &&
                hasBothFeet &&
                (abs(vYawL) + abs(vYawR) > 2.2f)

            val candidateMove = when {
                !isHeelToeNow && runningManBounceOk && opposingGlide && kneeDrive -> KineticMove.SHUFFLE_RUNNING_MAN
                tStepMotion -> KineticMove.SHUFFLE_T_STEP
                charlestonMotion -> KineticMove.SHUFFLE_CHARLESTON
                else -> null
            }

            if (candidateMove != null && (nowMs - lastShuffleStepMs) >= SHUFFLE_REFRACTORY_MS) {
                val sincePrevBeat = nowMs - lastShuffleCandidateMs
                if (sincePrevBeat in 200L..850L && pendingShuffleMove == candidateMove) {
                    detectedShuffleMove = candidateMove
                    lastShuffleStepMs = nowMs
                    pendingShuffleMove = candidateMove
                    lastShuffleCandidateMs = nowMs
                    if (isShuffleMode) {
                        shuffleStepDetected = true
                        recordMoveAndInterval(candidateMove, nowMs)
                    } else {
                        currentMove = candidateMove
                    }
                } else if (sincePrevBeat >= 200L) {
                    pendingShuffleMove = candidateMove
                    lastShuffleCandidateMs = nowMs
                }
            }
        }
        prevAnkleSep = ankleSep
        prevAnkleSepVel = ankleSepVel

        // 5. Classify Jump Move when a real Jump is accepted (Peak-Aligned v14!)
        if (jumpAccepted) {
            lastJumpAcceptedMs = nowMs
            baselineJumpCount++
            if (jumpAmplitude > 0.01f) {
                rollingJumpAmp = if (baselineJumpCount <= 2) {
                    jumpAmplitude
                } else {
                    rollingJumpAmp * 0.85f + jumpAmplitude * 0.15f
                }
            }

            val audioBursts = countHighAudioBursts(audioHistory, jumpPeakTimeMs)
            val isTripleUnder = baselineJumpCount >= 3 && (
                (jumpAmplitude >= max(0.15f, rollingJumpAmp * 1.75f) && activeFlightMs >= 370L) ||
                    (jumpAmplitude >= max(0.13f, rollingJumpAmp * 1.55f) && audioBursts >= 3)
                )

            val isDoubleUnder = !isTripleUnder && baselineJumpCount >= 3 && (
                (jumpAmplitude >= max(0.11f, rollingJumpAmp * 1.48f) && activeFlightMs >= 250L) ||
                    (jumpAmplitude >= max(0.10f, rollingJumpAmp * 1.38f) && audioBursts >= 2)
                )

            // Peak-Aligned Window `[jumpPeakTimeMs - 110ms .. jumpPeakTimeMs + 110ms]`
            val isCrissCross = isCrissCrossNow || hadCrissCrossNearPeak(jumpPeakTimeMs)
            val isHighKnees = isHighKneesNow || hadHighKneesNearPeak(jumpPeakTimeMs)
            val isHeelToe = isHeelToeNow || hadHeelToeNearPeak(jumpPeakTimeMs)

            // Boxer Step (300ms windowed around jump peak/landing)
            val instAnkleDelta = if (hasAnkles) (lAy - rAy) / torsoH else 0f
            val peakWinAnkleDelta = if (hasAnkles) computeRecentPeakSignedAnkleDelta(torsoH, nowMs) else 0f
            val ankleDelta = if (abs(peakWinAnkleDelta) >= abs(instAnkleDelta)) peakWinAnkleDelta else instAnkleDelta
            val pitchDelta = if (hasBothFeet) (lFootPitch - rFootPitch) else 0f
            val absAnkleDelta = abs(ankleDelta)
            val currSign = when {
                ankleDelta > 0.14f || pitchDelta > 0.11f -> 1
                ankleDelta < -0.14f || pitchDelta < -0.11f -> -1
                else -> 0
            }
            if (currSign != 0 && lastBoxerAnkleSign != 0 && currSign != lastBoxerAnkleSign) {
                boxerFlipConfirmCount = (boxerFlipConfirmCount + 1).coerceAtMost(4)
            } else if (currSign == 0) {
                boxerFlipConfirmCount = (boxerFlipConfirmCount - 1).coerceAtLeast(0)
            }
            if (currSign != 0) lastBoxerAnkleSign = currSign

            val isBoxerStep = hasAnkles && (
                (absAnkleDelta > 0.22f) ||
                    ((absAnkleDelta > 0.15f || abs(pitchDelta) > 0.12f) && boxerFlipConfirmCount >= 1)
                )

            val recentShuffleHold = (nowMs - lastShuffleStepMs) <= 650L && pendingShuffleMove != null
            val isDoubleUnderCross = isDoubleUnder && isCrissCross
            val isToad = isCrissCross && isHighKnees
            val isEbCross = isCrissCross && lWrist != null && rWrist != null &&
                abs(lWrist.z - rWrist.z) > 0.25f
            val isSkiers = hasAnkles && !isBoxerStep &&
                abs(hipVx) > 0.45f && ankleSep < 0.22f &&
                computeRecentHipShiftX(torsoH) >= 0.16f

            if (lWrist != null && rWrist != null) {
                lastWristDyNorm = (lWrist.y - rWrist.y) / torsoH
                lastWristSpanRatio = abs(lWrist.x - rWrist.x) / shoulderSpan.coerceAtLeast(0.05f)
            }
            if (isCrissCross) lastCrossFrameMs = nowMs
            lastJumpClearanceRatio = jumpAmplitude / rollingJumpAmp.coerceAtLeast(0.04f)

            val classifiedJump = when {
                isTripleUnder -> KineticMove.TRIPLE_UNDER
                isDoubleUnderCross -> KineticMove.DOUBLE_UNDER_CROSS
                isDoubleUnder -> KineticMove.DOUBLE_UNDER
                isToad -> KineticMove.TOAD
                isEbCross -> KineticMove.EB_CROSS
                isCrissCross -> KineticMove.CRISS_CROSS
                isHighKnees -> KineticMove.HIGH_KNEES
                isHeelToe -> KineticMove.HEEL_TOE
                detectedShuffleMove != null -> detectedShuffleMove
                recentShuffleHold -> pendingShuffleMove!!
                isSkiers -> KineticMove.SKIERS
                isBoxerStep -> KineticMove.BOXER_STEP
                else -> KineticMove.REGULAR
            }
            recordMoveAndInterval(classifiedJump, nowMs)
            evaluateFormSample(
                nose = nose,
                shoulderMidX = shoulderMidX,
                shoulderMidY = shoulderMidY,
                shoulderMidZ = shoulderMidZ,
                hipMidX = hipMidX,
                hipMidY = hipMidY,
                hipMidZ = hipMidZ,
                torsoH = torsoH,
                shoulderSpan = shoulderSpan,
                isFrontFacing = isFrontFacing,
                lWrist = lWrist,
                rWrist = rWrist,
                jumpAmplitude = jumpAmplitude,
                isCrissCross = isCrissCross,
                audioHistory = audioHistory,
                nowMs = jumpPeakTimeMs
            )
        } else if (isSideSwing) {
            currentMove = KineticMove.SIDE_SWING
            sideSwingCooldownUntilMs = nowMs + 550L
            moveCounter[KineticMove.SIDE_SWING.ordinal]++
            updateComboChain(KineticMove.SIDE_SWING)
            rebuildMoveCountsCache()
        } else if (shuffleStepDetected && detectedShuffleMove != null) {
            evaluateFormSample(
                nose = nose,
                shoulderMidX = shoulderMidX,
                shoulderMidY = shoulderMidY,
                shoulderMidZ = shoulderMidZ,
                hipMidX = hipMidX,
                hipMidY = hipMidY,
                hipMidZ = hipMidZ,
                torsoH = torsoH,
                shoulderSpan = shoulderSpan,
                isFrontFacing = isFrontFacing,
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
            formSnapshot = cachedFormSnapshot,
            moveCounts = cachedMoveCounts,
            comboCount = comboCount
        )
    }

    private fun evaluateHeelToeFrame(hasBothFeet: Boolean, lFootPitch: Float, rFootPitch: Float): Boolean {
        if (!hasBothFeet) return false
        val pitchContrast = abs(lFootPitch - rFootPitch)
        val minFootPitch = min(lFootPitch, rFootPitch)
        return (lFootPitch < 0.04f && rFootPitch > 0.12f) ||
            (rFootPitch < 0.04f && lFootPitch > 0.12f) ||
            (minFootPitch < 0.08f && pitchContrast >= 0.14f)
    }

    private fun hadCrissCrossNearPeak(peakMs: Long): Boolean {
        val minTs = peakMs - 110L
        val maxTs = peakMs + 110L
        for (i in 0 until bufCount) {
            val idx = (bufHead - 1 - i + BUF_SIZE) % BUF_SIZE
            val ts = timesMs[idx]
            if (ts < minTs) break
            if (ts <= maxTs && crossMetricBuf[idx] < -0.35f) return true
        }
        return false
    }

    private fun hadHighKneesNearPeak(peakMs: Long): Boolean {
        val minTs = peakMs - 110L
        val maxTs = peakMs + 110L
        for (i in 0 until bufCount) {
            val idx = (bufHead - 1 - i + BUF_SIZE) % BUF_SIZE
            val ts = timesMs[idx]
            if (ts < minTs) break
            if (ts <= maxTs && minKneeLiftBuf[idx] < 0.30f && kneeAsymBuf[idx] > 0.32f) return true
        }
        return false
    }

    private fun hadHeelToeNearPeak(peakMs: Long): Boolean {
        val minTs = peakMs - 110L
        val maxTs = peakMs + 110L
        for (i in 0 until bufCount) {
            val idx = (bufHead - 1 - i + BUF_SIZE) % BUF_SIZE
            val ts = timesMs[idx]
            if (ts < minTs) break
            if (ts <= maxTs && evaluateHeelToeFrame(hasBothFeetBuf[idx], leftFootPitchBuf[idx], rightFootPitchBuf[idx])) {
                return true
            }
        }
        return false
    }

    private fun updateComboChain(move: KineticMove) {
        if (move != KineticMove.REGULAR) {
            if (prevNonRegularMove != null && prevNonRegularMove != move) {
                comboCount++
            }
            prevNonRegularMove = move
        }
    }

    private fun computeRecentHipVertStd(torsoH: Float): Float {
        val n = min(bufCount, 12)
        if (n < 4) return 0f
        var sum = 0f
        for (k in 0 until n) {
            val idx = (bufHead - 1 - k + BUF_SIZE) % BUF_SIZE
            sum += hipYBuf[idx]
        }
        val mean = sum / n
        var v = 0f
        for (k in 0 until n) {
            val idx = (bufHead - 1 - k + BUF_SIZE) % BUF_SIZE
            val d = (hipYBuf[idx] - mean) / torsoH
            v += d * d
        }
        return sqrt(v / n)
    }

    private fun computeRecentHipShiftX(torsoH: Float): Float {
        if (bufCount < 4) return 0f
        val newestIdx = (bufHead - 1 + BUF_SIZE) % BUF_SIZE
        val newestMs = timesMs[newestIdx]
        val newestX = hipXBuf[newestIdx]
        var refX = newestX
        val limit = min(bufCount, 14)
        for (k in 1 until limit) {
            val idx = (bufHead - 1 - k + BUF_SIZE) % BUF_SIZE
            refX = hipXBuf[idx]
            if (newestMs - timesMs[idx] >= 350L) break
        }
        return abs(newestX - refX) / torsoH
    }

    private fun recordMoveAndInterval(move: KineticMove, nowMs: Long) {
        currentMove = move
        moveCounter[move.ordinal]++
        updateComboChain(move)
        rebuildMoveCountsCache()

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
        shoulderMidZ: Float,
        hipMidX: Float,
        hipMidY: Float,
        hipMidZ: Float,
        torsoH: Float,
        shoulderSpan: Float,
        isFrontFacing: Boolean,
        lWrist: LandmarkPoint?,
        rWrist: LandmarkPoint?,
        jumpAmplitude: Float,
        isCrissCross: Boolean,
        audioHistory: List<FluxSample>,
        nowMs: Long
    ) {
        val torsoTilt = abs(shoulderMidX - hipMidX) / torsoH
        val headForward = if (nose != null && isFrontFacing) abs(nose.x - shoulderMidX) / torsoH else 0f
        // Incorporate 3D forward lean (Z depth normalized by torsoH) when ML Kit 3D coordinates are present
        val forwardLean3d = if (shoulderMidZ != 0f || hipMidZ != 0f) {
            max(0f, (abs(shoulderMidZ - hipMidZ) / torsoH) - 0.18f) * 55f
        } else {
            0f
        }
        val posture = (100f - torsoTilt * 140f - headForward * 45f - forwardLean3d).coerceIn(45f, 100f)

        val hands = if (isFrontFacing && lWrist != null && rWrist != null && !isCrissCross && shoulderSpan > 1e-3f) {
            val yDiff = abs(lWrist.y - rWrist.y) / torsoH
            val avgWristY = (lWrist.y + rWrist.y) * 0.5f
            val tooHighPenalty = max(0f, (hipMidY - torsoH * 0.45f) - avgWristY) / torsoH * 95f
            val wristSpread = abs(lWrist.x - rWrist.x) / shoulderSpan
            val tooWidePenalty = max(0f, wristSpread - 2.35f) * 32f
            (100f - yDiff * 110f - tooHighPenalty - tooWidePenalty).coerceIn(40f, 100f)
        } else {
            92f
        }

        val excessiveHeightPenalty = max(0f, jumpAmplitude - 0.16f) * 180f
        val impactSpike = samplePeakAudioMid(audioHistory, nowMs)
        val heavyStompPenalty = max(0f, impactSpike - 18f) * 1.4f
        val softness = (100f - excessiveHeightPenalty - heavyStompPenalty).coerceIn(50f, 100f)

        postureAccum += posture
        handAccum += hands
        softnessAccum += softness
        formSamples++

        lastFormTip = when {
            hands < 74f -> "Keep hands symmetrical & near hips"
            posture < 76f -> "Lift your chest & stay upright"
            softness < 74f -> "Land softer on the balls of your feet"
            computeRhythmConsistency() < 75 && intervalCount >= 5 -> "Keep a steady rhythm"
            else -> "Great form! Clean & efficient"
        }
        rebuildFormSnapshotCache()
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
        val cv = sqrt(varSum / intervalCount) / mean
        return (100f - cv * 145f).roundToInt().coerceIn(45, 100)
    }

    private fun countHighAudioBursts(audioHistory: List<FluxSample>, peakMs: Long): Int {
        if (audioHistory.size < 20) return 0
        val startMs = peakMs - 240L
        val endMs = peakMs + 160L
        var peaks = 0
        var lastPeakMs = -10_000L
        var mean = 0f
        var count = 0
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
        if (count < 6) return 0
        val thresh = (mean / count) * 1.75f + 1.0f
        idx = lo + 1
        val endIdx = min(audioHistory.size - 2, lo + count - 2)
        while (idx <= endIdx) {
            val v = audioHistory[idx].wideHigh
            if (v > thresh && v >= audioHistory[idx - 1].wideHigh && v >= audioHistory[idx + 1].wideHigh) {
                val ts = audioHistory[idx].timestampMs
                if (ts - lastPeakMs in 65L..165L || lastPeakMs < 0L) {
                    peaks++
                    lastPeakMs = ts
                }
            }
            idx++
        }
        return peaks
    }

    private fun computeRecentPeakSignedAnkleDelta(torsoH: Float, nowMs: Long): Float {
        if (bufCount < 2) return 0f
        val startMs = nowMs - 300L
        var best = 0f
        var bestAbs = 0f
        for (i in 0 until bufCount) {
            val idx = (bufHead - 1 - i + BUF_SIZE) % BUF_SIZE
            if (timesMs[idx] < startMs) break
            val delta = (leftAnkleY[idx] - rightAnkleY[idx]) / torsoH
            val a = abs(delta)
            if (a > bestAbs) {
                bestAbs = a
                best = delta
            }
        }
        return best
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

    /**
     * AI Engine v15 — Trip Root-Cause Autopsy.
     * Inspects the last 1,500 ms of wrist symmetry, wrist width, jump clearance, and cross timing
     * to explain *why* the rope caught on the athlete's shoes.
     */
    fun diagnoseTrip(nowMs: Long): TripAutopsyResult {
        val result = when {
            (nowMs - lastCrossFrameMs) in 0L..900L -> TripAutopsyResult(
                causeCode = "EARLY_UNCROSS",
                headline = "Uncrossed Too Early",
                coachingFix = "Hold wrists past opposite hips 60ms longer until rope clears toes"
            )
            abs(lastWristDyNorm) > 0.16f -> {
                val higherSide = if (lastWristDyNorm < 0f) "Left" else "Right"
                TripAutopsyResult(
                    causeCode = "HAND_DRIFT",
                    headline = "$higherSide Hand Drifted High",
                    coachingFix = "Keep both hands level at hip height to prevent a tilted rope arc"
                )
            }
            lastWristSpanRatio > 1.65f -> TripAutopsyResult(
                causeCode = "HANDS_WIDE",
                headline = "Hands Widened Too Far",
                coachingFix = "Tuck elbows closer to ribs so the rope arc stays full length"
            )
            lastJumpClearanceRatio < 0.72f -> TripAutopsyResult(
                causeCode = "LOW_CLEARANCE",
                headline = "Jump Clearance Dropped",
                coachingFix = "Calf fatigue detected — bounce crisply 2–4 cm on the balls of your feet"
            )
            else -> TripAutopsyResult(
                causeCode = "TIMING_DRIFT",
                headline = "Rhythm Timing Drift",
                coachingFix = "Lock wrist flicks to your landing cadence using the Audio Pacemaker"
            )
        }
        lastTripAutopsy = result
        return result
    }
}
