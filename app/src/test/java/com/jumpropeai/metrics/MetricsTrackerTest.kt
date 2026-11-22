package com.jumpropeai.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsTrackerTest {

    @Test
    fun tracksRpmStreakAndCaloriesAccurately() {
        val tracker = MetricsTracker(userWeightKg = 65.0)
        tracker.start(0L)

        // 11 jumps spaced 500ms apart => 120 RPM
        for (i in 0..10) {
            tracker.recordJump(i * 500L)
        }

        assertEquals(11, tracker.jumpCount)
        assertEquals(11, tracker.currentStreak)
        assertEquals(11, tracker.bestStreak)
        assertEquals(120, tracker.getCurrentRpm(5_000L))
        assertEquals(120, tracker.peakRpm)
        assertTrue("Expected positive calories", tracker.estimatedCalories > 0.5)
    }

    @Test
    fun resetsActiveStreakAfterTimeoutWhileKeepingBestStreak() {
        val tracker = MetricsTracker(streakTimeoutMs = 2_000L)
        tracker.start(0L)

        // 4 consecutive jumps
        tracker.recordJump(1_000L)
        tracker.recordJump(1_500L)
        tracker.recordJump(2_000L)
        tracker.recordJump(2_500L)
        assertEquals(4, tracker.bestStreak)

        // Idle for 3 seconds (> streakTimeoutMs)
        assertEquals(0, tracker.getActiveStreak(5_500L))
        assertEquals(0, tracker.getCurrentRpm(6_000L))

        // Next jump starts new streak at 1, bestStreak remains 4
        tracker.recordJump(6_000L)
        assertEquals(1, tracker.currentStreak)
        assertEquals(4, tracker.bestStreak)
    }

    @Test
    fun pausesAndResumesElapsedDurationCorrectly() {
        val tracker = MetricsTracker()
        tracker.start(1_000L)
        assertEquals(2_000L, tracker.getElapsedMs(3_000L))

        tracker.pause(3_000L)
        assertEquals(2_000L, tracker.getElapsedMs(8_000L))

        tracker.start(8_000L)
        assertEquals(5_000L, tracker.getElapsedMs(11_000L))
    }
}
