package com.jumpropeai.ai

import com.jumpropeai.audio.BeatSyncEngine
import com.jumpropeai.audio.FluxSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class KineticEngineV13Test {

    private fun makePose(
        tsMs: Long,
        lWristX: Float = 0.34f,
        rWristX: Float = 0.66f,
        lWristY: Float = 0.54f,
        rWristY: Float = 0.54f,
        lKneeY: Float = 0.72f,
        rKneeY: Float = 0.72f,
        lAnkleX: Float = 0.44f,
        rAnkleX: Float = 0.56f,
        lAnkleY: Float = 0.88f,
        rAnkleY: Float = 0.88f,
        shoulderOffsetX: Float = 0f
    ): PoseFrame {
        val map = mapOf(
            BodyLandmark.NOSE to LandmarkPoint(0.50f + shoulderOffsetX, 0.22f, 0.95f),
            BodyLandmark.LEFT_SHOULDER to LandmarkPoint(0.42f + shoulderOffsetX, 0.34f, 0.95f),
            BodyLandmark.RIGHT_SHOULDER to LandmarkPoint(0.58f + shoulderOffsetX, 0.34f, 0.95f),
            BodyLandmark.LEFT_ELBOW to LandmarkPoint(0.37f, 0.46f, 0.95f),
            BodyLandmark.RIGHT_ELBOW to LandmarkPoint(0.63f, 0.46f, 0.95f),
            BodyLandmark.LEFT_WRIST to LandmarkPoint(lWristX, lWristY, 0.95f),
            BodyLandmark.RIGHT_WRIST to LandmarkPoint(rWristX, rWristY, 0.95f),
            BodyLandmark.LEFT_HIP to LandmarkPoint(0.44f, 0.54f, 0.95f),
            BodyLandmark.RIGHT_HIP to LandmarkPoint(0.56f, 0.54f, 0.95f),
            BodyLandmark.LEFT_KNEE to LandmarkPoint(0.44f, lKneeY, 0.95f),
            BodyLandmark.RIGHT_KNEE to LandmarkPoint(0.56f, rKneeY, 0.95f),
            BodyLandmark.LEFT_ANKLE to LandmarkPoint(lAnkleX, lAnkleY, 0.95f),
            BodyLandmark.RIGHT_ANKLE to LandmarkPoint(rAnkleX, rAnkleY, 0.95f)
        )
        return PoseFrame(map, tsMs, 720, 1280, isMirrored = false)
    }

    @Test
    fun detectsCrissCrossHighKneesBoxerStepDoubleUnderAndSideSwing() {
        val coach = KineticTrickCoach()

        // 1. Criss-Cross (wrists crossed over torso midline)
        val cc = coach.processFrame(
            frame = makePose(tsMs = 1000L, lWristX = 0.62f, rWristX = 0.38f),
            isAirborne = true,
            jumpAccepted = true,
            jumpAmplitude = 0.08f,
            audioHistory = emptyList(),
            isShuffleMode = false
        )
        assertEquals(KineticMove.CRISS_CROSS, cc.currentMove)

        // 2. High Knees (left knee lifted up to hip level)
        val hk = coach.processFrame(
            frame = makePose(tsMs = 1500L, lKneeY = 0.56f, rKneeY = 0.74f),
            isAirborne = true,
            jumpAccepted = true,
            jumpAmplitude = 0.08f,
            audioHistory = emptyList(),
            isShuffleMode = false
        )
        assertEquals(KineticMove.HIGH_KNEES, hk.currentMove)

        // 3. Boxer Step (alternating left/right ankle elevation)
        val bs = coach.processFrame(
            frame = makePose(tsMs = 2000L, lAnkleY = 0.80f, rAnkleY = 0.89f),
            isAirborne = true,
            jumpAccepted = true,
            jumpAmplitude = 0.08f,
            audioHistory = emptyList(),
            isShuffleMode = false
        )
        assertEquals(KineticMove.BOXER_STEP, bs.currentMove)

        // 4. Double Under (high jump + dual acoustic whip bursts)
        val audio = (0 until 50).map { i ->
            val t = 2250L + i * 10L
            val burst = if (t == 2400L || t == 2510L) 16f else 1f
            FluxSample(t, mid = 1f, high1 = burst, high2 = burst, wideHigh = burst)
        }
        val du = coach.processFrame(
            frame = makePose(tsMs = 2500L),
            isAirborne = true,
            jumpAccepted = true,
            jumpAmplitude = 0.14f,
            audioHistory = audio,
            isShuffleMode = false
        )
        assertEquals(KineticMove.DOUBLE_UNDER, du.currentMove)

        // 5. Side Swing (both wrists shifted to left of torso while grounded)
        val sw = coach.processFrame(
            frame = makePose(tsMs = 3600L, lWristX = 0.25f, rWristX = 0.36f),
            isAirborne = false,
            jumpAccepted = false,
            jumpAmplitude = 0f,
            audioHistory = emptyList(),
            isShuffleMode = false
        )
        assertEquals(KineticMove.SIDE_SWING, sw.currentMove)
    }

    @Test
    fun detectsShuffleRunningManAndCountsSteps() {
        val coach = KineticTrickCoach()
        var steps = 0
        var sawRunningMan = false

        // Simulate 2 seconds of Running Man opposing ankle stride + alternating knee drive
        for (i in 0 until 60) {
            val t = i * 33L
            val phase = sin(i * 0.45).toFloat()
            val lAnkleX = 0.50f + phase * 0.11f
            val rAnkleX = 0.50f - phase * 0.11f
            val lKneeY = 0.70f - abs(phase) * 0.06f
            val rKneeY = 0.74f + abs(phase) * 0.02f
            val res = coach.processFrame(
                frame = makePose(
                    tsMs = t,
                    lAnkleX = lAnkleX,
                    rAnkleX = rAnkleX,
                    lKneeY = lKneeY,
                    rKneeY = rKneeY
                ),
                isAirborne = false,
                jumpAccepted = false,
                jumpAmplitude = 0f,
                audioHistory = emptyList(),
                isShuffleMode = true
            )
            if (res.shuffleStepDetected) steps++
            if (res.currentMove == KineticMove.SHUFFLE_RUNNING_MAN) sawRunningMan = true
        }

        assertTrue("Expected Running Man detection", sawRunningMan)
        assertTrue("Expected Shuffle steps counted (>= 3), got $steps", steps >= 3)
    }

    @Test
    fun formCoachScoresGoodVsPoorFormAccurately() {
        val goodCoach = KineticTrickCoach()
        val poorCoach = KineticTrickCoach()

        for (i in 1..10) {
            val t = i * 420L
            goodCoach.processFrame(
                frame = makePose(tsMs = t),
                isAirborne = true,
                jumpAccepted = true,
                jumpAmplitude = 0.08f,
                audioHistory = emptyList(),
                isShuffleMode = false
            )
            poorCoach.processFrame(
                frame = makePose(
                    tsMs = t,
                    lWristX = 0.12f,
                    rWristX = 0.88f,
                    lWristY = 0.30f,
                    rWristY = 0.58f,
                    shoulderOffsetX = 0.12f
                ),
                isAirborne = true,
                jumpAccepted = true,
                jumpAmplitude = 0.24f,
                audioHistory = emptyList(),
                isShuffleMode = false
            )
        }

        val goodScore = goodCoach.currentFormSnapshot().overallScore
        val poorScore = poorCoach.currentFormSnapshot().overallScore
        assertTrue("Expected good form >= 85, got $goodScore", goodScore >= 85)
        assertTrue("Expected poor form < good form ($poorScore vs $goodScore)", poorScore < goodScore - 15)
    }

    @Test
    fun beatSyncEngineDetects125BpmAndHighOnBeatSyncScore() {
        val beatEngine = BeatSyncEngine()
        // 125 BPM = 480ms period = 48 samples at 100 Hz
        val flux = (0 until 400).map { i ->
            val t = i * 10L
            val isBeat = (i % 48 == 0)
            val v = if (isBeat) 20f else 1f
            FluxSample(t, mid = v, high1 = 1f, high2 = 1f, wideHigh = v * 0.4f)
        }

        val snap = beatEngine.updateFromAudio(flux, 4000L)
        assertTrue("Expected ~125 BPM (121..129), got ${snap.detectedBpm}", snap.detectedBpm in 121..129)

        // Simulate 5 Shuffle steps landing right on beat (every 480ms)
        for (k in 1..5) {
            beatEngine.recordMoveOnBeat(k * 480L)
        }
        val finalSnap = beatEngine.snapshot()
        assertTrue("Expected Beat-Sync Score >= 85%, got ${finalSnap.beatSyncScore}%", finalSnap.beatSyncScore >= 85)
    }

    @Test
    fun kineticEngineV13Meets99PercentLightweightSpeedTarget() {
        val coach = KineticTrickCoach()
        val pose = makePose(tsMs = 0L)
        // Warmup
        repeat(500) { i ->
            coach.processFrame(pose.copy(timestampMs = i * 33L), false, i % 15 == 0, 0.08f, emptyList(), true)
        }
        val iterations = 10_000
        val t0 = System.nanoTime()
        repeat(iterations) { i ->
            coach.processFrame(pose.copy(timestampMs = (500 + i) * 33L), i % 2 == 0, i % 15 == 0, 0.08f, emptyList(), true)
        }
        val elapsedUs = (System.nanoTime() - t0) / 1000.0
        val usPerFrame = elapsedUs / iterations
        println("KineticTrickCoach speed: %.2f us/frame".format(usPerFrame))
        assertTrue("Expected < 25 us/frame, got $usPerFrame", usPerFrame < 25.0)
    }
}
