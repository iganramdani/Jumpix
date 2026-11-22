package com.jumpropeai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class JumpDetectorTest {

    private fun createStandingFrame(
        offsetY: Float,
        timestampMs: Long,
        torsoHeight: Float = 200f,
        confidence: Float = 0.95f
    ): PoseFrame {
        val shoulderY = 300f - offsetY
        val hipY = shoulderY + torsoHeight
        return PoseFrame(
            landmarks = mapOf(
                BodyLandmark.LEFT_SHOULDER to LandmarkPoint(200f, shoulderY, confidence),
                BodyLandmark.RIGHT_SHOULDER to LandmarkPoint(300f, shoulderY, confidence),
                BodyLandmark.LEFT_HIP to LandmarkPoint(210f, hipY, confidence),
                BodyLandmark.RIGHT_HIP to LandmarkPoint(290f, hipY, confidence)
            ),
            timestampMs = timestampMs,
            imageWidth = 480,
            imageHeight = 800,
            isMirrored = true
        )
    }

    @Test
    fun detectsFiveCleanJumpsAfterCalibration() {
        val detector = JumpDetector(calibrationFramesRequired = 5)
        var t = 0L

        // 5 calibration frames while standing still
        repeat(5) {
            val res = detector.processFrame(createStandingFrame(0f, t), isCountingActive = true)
            assertFalse(res.jumpDetected)
            t += 33L
        }

        // Simulate 5 rhythmic jumps (~120 RPM = 500ms per jump, 15 frames per jump at 30fps)
        var totalJumps = 0
        repeat(5) {
            for (step in 0 until 15) {
                // Half-sine upward trajectory peaking at 50px (0.25 of 200px torso height)
                val height = if (step in 2..10) {
                    (sin(((step - 2) / 8.0) * PI) * 50.0).toFloat()
                } else {
                    0f
                }
                val res = detector.processFrame(createStandingFrame(height, t), isCountingActive = true)
                if (res.jumpDetected) totalJumps++
                t += 33L
            }
        }

        assertEquals(5, totalJumps)
    }

    @Test
    fun ignoresSmallJitterBelowThreshold() {
        val detector = JumpDetector(calibrationFramesRequired = 5)
        var t = 0L

        repeat(5) {
            detector.processFrame(createStandingFrame(0f, t), isCountingActive = true)
            t += 33L
        }

        var totalJumps = 0
        repeat(60) { i ->
            // Tiny +/- 6px jitter (0.03 of torso height, well below 0.13 threshold)
            val jitter = if (i % 2 == 0) 6f else -6f
            val res = detector.processFrame(createStandingFrame(jitter, t), isCountingActive = true)
            if (res.jumpDetected) totalJumps++
            t += 33L
        }

        assertEquals(0, totalJumps)
    }

    @Test
    fun doesNotCountWhenSessionPaused() {
        val detector = JumpDetector(calibrationFramesRequired = 5)
        var t = 0L

        repeat(5) {
            detector.processFrame(createStandingFrame(0f, t), isCountingActive = false)
            t += 33L
        }

        var totalJumps = 0
        for (step in 0 until 15) {
            val height = if (step in 2..10) {
                (sin(((step - 2) / 8.0) * PI) * 55.0).toFloat()
            } else {
                0f
            }
            val res = detector.processFrame(createStandingFrame(height, t), isCountingActive = false)
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
            imageHeight = 800,
            isMirrored = true
        )
        val res = detector.processFrame(emptyFrame, isCountingActive = true)
        assertEquals(DetectorStatus.NO_PERSON, res.status)
        assertFalse(res.jumpDetected)
        assertTrue(res.calibrationProgress == 0f)
    }
}
