package com.jumpropeai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class JumpDetectorTest {

    private fun createFrame(
        offsetY: Float,
        timestampMs: Long,
        baseShoulderY: Float = 300f,
        torsoHeight: Float = 200f,
        confidence: Float = 0.95f
    ): PoseFrame {
        val shoulderY = baseShoulderY - offsetY
        val noseY = shoulderY - torsoHeight * 0.25f
        val hipY = shoulderY + torsoHeight
        return PoseFrame(
            landmarks = mapOf(
                BodyLandmark.NOSE to LandmarkPoint(250f, noseY, confidence),
                BodyLandmark.LEFT_SHOULDER to LandmarkPoint(200f, shoulderY, confidence),
                BodyLandmark.RIGHT_SHOULDER to LandmarkPoint(300f, shoulderY, confidence),
                BodyLandmark.LEFT_HIP to LandmarkPoint(210f, hipY, confidence),
                BodyLandmark.RIGHT_HIP to LandmarkPoint(290f, hipY, confidence)
            ),
            timestampMs = timestampMs,
            imageWidth = 480,
            imageHeight = 640,
            isMirrored = true
        )
    }

    @Test
    fun detectsFastLowHopsAndWorksEvenAfterBaselineDrift() {
        val detector = JumpDetector()
        var t = 0L

        // Warmup while standing close to camera (higher in frame)
        repeat(4) {
            val res = detector.processFrame(createFrame(0f, t, baseShoulderY = 220f), isCountingActive = true)
            assertFalse(res.jumpDetected)
            t += 33L
        }

        // User walks back to jumping spot (lower in frame: baseShoulderY shifts from 220 -> 340)
        repeat(15) { i ->
            val shiftedY = 220f + (120f * i / 14f)
            detector.processFrame(createFrame(0f, t, baseShoulderY = shiftedY), isCountingActive = true)
            t += 33L
        }

        // Simulate 10 realistic low fast hops (~128 RPM = 466ms per jump, peak = 22px = 0.11x torso)
        var totalJumps = 0
        repeat(10) {
            for (step in 0 until 14) {
                val height = if (step in 1..10) {
                    (sin(((step - 1) / 9.0) * PI) * 22.0).toFloat()
                } else {
                    0f
                }
                val res = detector.processFrame(createFrame(height, t, baseShoulderY = 340f), isCountingActive = true)
                if (res.jumpDetected) totalJumps++
                t += 33L
            }
        }

        assertEquals(10, totalJumps)
    }

    @Test
    fun ignoresSmallJitterBelowProminenceThreshold() {
        val detector = JumpDetector()
        var t = 0L

        repeat(4) {
            detector.processFrame(createFrame(0f, t), isCountingActive = true)
            t += 33L
        }

        var totalJumps = 0
        repeat(60) { i ->
            // Tiny +/- 4px jitter (0.02x torso, well below 0.06 prominence)
            val jitter = if (i % 2 == 0) 4f else -4f
            val res = detector.processFrame(createFrame(jitter, t), isCountingActive = true)
            if (res.jumpDetected) totalJumps++
            t += 33L
        }

        assertEquals(0, totalJumps)
    }

    @Test
    fun rejectsSlowStandingUpFromBend() {
        val detector = JumpDetector()
        var t = 0L

        repeat(4) {
            detector.processFrame(createFrame(0f, t), isCountingActive = true)
            t += 33L
        }

        // Slowly stand up over 1.2 seconds (36 frames) by 120px, then bend slightly
        var totalJumps = 0
        for (i in 0 until 36) {
            val h = 120f * (i / 35f)
            val res = detector.processFrame(createFrame(h, t), isCountingActive = true)
            if (res.jumpDetected) totalJumps++
            t += 33L
        }
        for (i in 0 until 15) {
            val h = 120f - 40f * (i / 14f)
            val res = detector.processFrame(createFrame(h, t), isCountingActive = true)
            if (res.jumpDetected) totalJumps++
            t += 33L
        }

        assertEquals(0, totalJumps)
    }

    @Test
    fun doesNotCountWhenSessionPaused() {
        val detector = JumpDetector()
        var t = 0L

        repeat(4) {
            detector.processFrame(createFrame(0f, t), isCountingActive = false)
            t += 33L
        }

        var totalJumps = 0
        for (step in 0 until 15) {
            val height = if (step in 2..10) {
                (sin(((step - 2) / 8.0) * PI) * 55.0).toFloat()
            } else {
                0f
            }
            val res = detector.processFrame(createFrame(height, t), isCountingActive = false)
            if (res.jumpDetected) totalJumps++
            t += 33L
        }

        assertEquals(0, totalJumps)
    }

    @Test
    fun reportsNoPersonWhenLandmarksEmpty() {
        val detector = JumpDetector()
        val emptyFrame = PoseFrame(
            landmarks = emptyMap(),
            timestampMs = 100L,
            imageWidth = 480,
            imageHeight = 640,
            isMirrored = true
        )
        val res = detector.processFrame(emptyFrame, isCountingActive = true)
        assertEquals(DetectorStatus.NO_PERSON, res.status)
        assertFalse(res.jumpDetected)
        assertTrue(res.calibrationProgress == 0f)
    }
}
