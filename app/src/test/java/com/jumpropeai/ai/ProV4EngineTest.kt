package com.jumpropeai.ai

import com.jumpropeai.audio.BeatSyncEngine
import com.jumpropeai.audio.FluxSample
import com.jumpropeai.audio.StftAudioEngine
import com.jumpropeai.data.WorkoutHistoryRepository
import com.jumpropeai.data.WorkoutSession
import com.jumpropeai.metrics.MetricsTracker
import com.jumpropeai.metrics.RopeType
import com.jumpropeai.update.AppUpdateChecker
import com.jumpropeai.workout.WorkoutConfig
import com.jumpropeai.workout.WorkoutModeEvaluator
import com.jumpropeai.workout.WorkoutModeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ProV4EngineTest {

    private fun makeFullBodyPose(
        tsMs: Long,
        lWristX: Float = 260f,
        rWristX: Float = 460f,
        lWristY: Float = 660f,
        rWristY: Float = 660f,
        includeAnkles: Boolean = true
    ): PoseFrame {
        val map = mutableMapOf(
            BodyLandmark.NOSE to LandmarkPoint(360f, 260f, 0.95f),
            BodyLandmark.LEFT_SHOULDER to LandmarkPoint(300f, 400f, 0.95f),
            BodyLandmark.RIGHT_SHOULDER to LandmarkPoint(420f, 400f, 0.95f),
            BodyLandmark.LEFT_ELBOW to LandmarkPoint(270f, 540f, 0.95f),
            BodyLandmark.RIGHT_ELBOW to LandmarkPoint(450f, 540f, 0.95f),
            BodyLandmark.LEFT_WRIST to LandmarkPoint(lWristX, lWristY, 0.95f),
            BodyLandmark.RIGHT_WRIST to LandmarkPoint(rWristX, rWristY, 0.95f),
            BodyLandmark.LEFT_HIP to LandmarkPoint(320f, 680f, 0.95f),
            BodyLandmark.RIGHT_HIP to LandmarkPoint(400f, 680f, 0.95f),
            BodyLandmark.LEFT_KNEE to LandmarkPoint(320f, 900f, 0.95f),
            BodyLandmark.RIGHT_KNEE to LandmarkPoint(400f, 900f, 0.95f)
        )
        if (includeAnkles) {
            map[BodyLandmark.LEFT_ANKLE] = LandmarkPoint(320f, 1120f, 0.95f)
            map[BodyLandmark.RIGHT_ANKLE] = LandmarkPoint(400f, 1120f, 0.95f)
        }
        return PoseFrame(map, tsMs, 720, 1280, isMirrored = false)
    }

    @Test
    fun peakAlignedCrissCrossSurvives220msCausalLookaheadDelay() {
        val coach = KineticTrickCoach()
        // Warmup regular jump
        coach.processFrame(makeFullBodyPose(400L), true, true, 0.08f, emptyList(), false, 400L)

        // At t = 1000ms (jump apex), wrists are deeply crossed (L > R for front-facing where L.x=300 < R.x=420)
        coach.processFrame(
            frame = makeFullBodyPose(1000L, lWristX = 460f, rWristX = 260f),
            isAirborne = true,
            jumpAccepted = false,
            jumpAmplitude = 0f,
            audioHistory = emptyList(),
            isShuffleMode = false
        )
        // At t = 1066..1198ms, athlete uncrosses arms before landing
        coach.processFrame(makeFullBodyPose(1066L, lWristX = 320f, rWristX = 400f), true, false, 0f, emptyList(), false)
        coach.processFrame(makeFullBodyPose(1132L, lWristX = 260f, rWristX = 460f), false, false, 0f, emptyList(), false)

        // At t = 1220ms (+220ms lookahead), JumpDetector confirms the jump whose peak was at 1000ms!
        val res = coach.processFrame(
            frame = makeFullBodyPose(1220L, lWristX = 260f, rWristX = 460f),
            isAirborne = false,
            jumpAccepted = true,
            jumpAmplitude = 0.09f,
            audioHistory = emptyList(),
            isShuffleMode = false,
            jumpPeakTimeMs = 1000L
        )
        assertEquals(KineticMove.CRISS_CROSS, res.currentMove)
    }

    @Test
    fun touchlessGestureControllerChecksFramingAndTriggersOnSingleHandHold() {
        val ctrl = TouchlessGestureController()

        // Missing ankles -> FEET_CUT_OFF after debounce (350ms)
        var state = TouchlessFrameState()
        for (t in 0L..500L step 50L) {
            state = ctrl.evaluate(
                frame = makeFullBodyPose(t, includeAnkles = false),
                isSessionRunning = false,
                isCountingDown = false,
                isAirborne = false,
                lastJumpAcceptedMs = -10_000L,
                gestureEnabled = true
            )
        }
        assertEquals(FramingStatus.FEET_CUT_OFF, state.framingStatus)
        assertEquals(GestureTrigger.NONE, state.trigger)

        // Full body visible -> READY
        for (t in 550L..1000L step 50L) {
            state = ctrl.evaluate(
                frame = makeFullBodyPose(t, includeAnkles = true),
                isSessionRunning = false,
                isCountingDown = false,
                isAirborne = false,
                lastJumpAcceptedMs = -10_000L,
                gestureEnabled = true
            )
        }
        assertEquals(FramingStatus.READY, state.framingStatus)

        // Hold RIGHT hand high above nose (y=150 < nose 260) while LEFT hand stays relaxed (y=660) for 1.25s
        var triggered = false
        for (t in 1100L..2500L step 50L) {
            val s = ctrl.evaluate(
                frame = makeFullBodyPose(t, lWristY = 660f, rWristY = 150f, includeAnkles = true),
                isSessionRunning = false,
                isCountingDown = false,
                isAirborne = false,
                lastJumpAcceptedMs = -10_000L,
                gestureEnabled = true
            )
            if (s.trigger == GestureTrigger.TOGGLE_SESSION) {
                triggered = true
                break
            }
        }
        assertTrue("Expected Touchless single-hand hold to trigger session toggle", triggered)
    }

    @Test
    fun streamingStftSeparatesBassKickAndHighRopeWhipAt48kHz() {
        val stft = StftAudioEngine(sampleRate = 16_000)
        val session = stft.StreamingOfflineSession(inputSampleRate = 48_000)

        // Feed 1.5s of 48 kHz mono PCM in small 2048-sample chunks:
        // - 0.3s..0.4s: 125 Hz kick drum
        // - 0.9s..1.0s: 5200 Hz rope whip
        val totalSamples = 48_000 * 3 / 2
        val chunk = FloatArray(2048)
        var pos = 0
        while (pos < totalSamples) {
            val len = minOf(chunk.size, totalSamples - pos)
            for (i in 0 until len) {
                val n = pos + i
                val sec = n / 48_000.0
                chunk[i] = when {
                    sec in 0.30..0.42 -> (0.7 * sin(2.0 * PI * 125.0 * sec)).toFloat()
                    sec in 0.90..0.98 -> (0.7 * sin(2.0 * PI * 5200.0 * sec)).toFloat()
                    else -> 0f
                }
            }
            session.feedMonoChunk(chunk, len)
            pos += len
        }
        val flux = session.finish()
        assertTrue("Expected >= 100 flux frames, got ${flux.size}", flux.size >= 100)
        val maxBass = flux.maxOf { it.bass }
        val maxHi2 = flux.maxOf { it.high2 }
        assertTrue("Expected strong BASS onset (> 0.2), got $maxBass", maxBass > 0.2f)
        assertTrue("Expected strong HIGH_2 onset (> 0.2), got $maxHi2", maxHi2 > 0.2f)
    }

    @Test
    fun unbiasedBeatSyncDetects96BpmOnBassKickBand() {
        val engine = BeatSyncEngine()
        // 96 BPM = 625 ms period = 62.5 samples at 100 Hz
        val flux = (0 until 360).map { i ->
            val t = i * 10L
            val isKick = (i % 62 == 0)
            FluxSample(
                timestampMs = t,
                mid = 0.2f,
                high1 = 0.1f,
                high2 = 0.1f,
                wideHigh = 0.1f,
                bass = if (isKick) 18f else 0.1f
            )
        }
        val snap = engine.updateFromAudio(flux, 3600L)
        assertTrue("Expected ~96 BPM (93..100), got ${snap.detectedBpm}", snap.detectedBpm in 93..100)
    }

    @Test
    fun ijruCompetitionModesAndWeightedRopeCaloriesWorkAccurately() {
        val sprintCfg = WorkoutConfig(mode = WorkoutModeType.IJRU_SPEED_30S)
        val midSprint = WorkoutModeEvaluator.evaluate(sprintCfg, jumpCount = 75, elapsedMs = 15_000L)
        assertEquals(15_000L, midSprint.remainingMs)
        assertFalse(midSprint.isCompleted)
        val doneSprint = WorkoutModeEvaluator.evaluate(sprintCfg, jumpCount = 155, elapsedMs = 30_000L)
        assertTrue(doneSprint.isCompleted)

        val speedTracker = MetricsTracker(userWeightKg = 70.0).apply {
            updateAthleteProfile(70.0, RopeType.SPEED_CABLE)
            start(0L)
        }
        val weightedTracker = MetricsTracker(userWeightKg = 70.0).apply {
            updateAthleteProfile(70.0, RopeType.WEIGHTED_ONE_LB)
            start(0L)
        }
        for (i in 0..20) {
            speedTracker.recordJump(i * 450L)
            weightedTracker.recordJump(i * 450L)
        }
        assertTrue(
            "Weighted 1 lb rope should burn significantly more kcal (${weightedTracker.estimatedCalories} vs ${speedTracker.estimatedCalories})",
            weightedTracker.estimatedCalories > speedTracker.estimatedCalories * 1.25
        )
    }

    @Test
    fun cumulativeStatsComputesPersonalRecordsAndTodayJumps() {
        val now = System.currentTimeMillis()
        val sessions = listOf(
            WorkoutSession(
                id = "s1",
                timestampMillis = now,
                modeLabel = "IJRU 30s Sprint",
                modeType = WorkoutModeType.IJRU_SPEED_30S,
                jumpCount = 148,
                durationMs = 30_000L,
                averageRpm = 296,
                peakRpm = 315,
                estimatedCalories = 12.4,
                bestStreak = 148,
                formScore = 92,
                moveBreakdown = mapOf("Double Under" to 12, "Boxer Step" to 40)
            )
        )
        val stats = WorkoutHistoryRepository.computeStats(sessions)
        assertEquals(148, stats.todayJumps)
        assertEquals(1, stats.activeDayStreak)
        assertEquals(148, stats.best30sSprintJumps)
        assertEquals(315, stats.fastestPeakRpm)
        assertEquals(92, stats.bestFormScore)
        assertEquals(12, stats.totalDoubleUnders)
        assertTrue(AppUpdateChecker.isNewerVersion("4.0.0", "3.3.6"))
    }
}
