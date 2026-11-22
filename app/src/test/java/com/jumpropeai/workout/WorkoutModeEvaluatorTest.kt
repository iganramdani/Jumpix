package com.jumpropeai.workout

import com.jumpropeai.data.WorkoutHistoryRepository
import com.jumpropeai.data.WorkoutSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutModeEvaluatorTest {

    @Test
    fun targetJumpsTracksProgressAndCompletesAtTarget() {
        val config = WorkoutConfig(mode = WorkoutModeType.TARGET_JUMPS, targetJumps = 100)

        val mid = WorkoutModeEvaluator.evaluate(config, jumpCount = 50, elapsedMs = 25_000L)
        assertEquals(0.5f, mid.progressFraction, 0.001f)
        assertFalse(mid.isCompleted)
        assertTrue(mid.isCountingAllowed)

        val done = WorkoutModeEvaluator.evaluate(config, jumpCount = 100, elapsedMs = 50_000L)
        assertEquals(1f, done.progressFraction, 0.001f)
        assertTrue(done.isCompleted)
        assertFalse(done.isCountingAllowed)
    }

    @Test
    fun targetTimeTracksRemainingAndCompletes() {
        val config = WorkoutConfig(mode = WorkoutModeType.TARGET_TIME, targetTimeSeconds = 60)

        val mid = WorkoutModeEvaluator.evaluate(config, jumpCount = 40, elapsedMs = 20_000L)
        assertEquals(40_000L, mid.remainingMs)
        assertFalse(mid.isCompleted)

        val done = WorkoutModeEvaluator.evaluate(config, jumpCount = 120, elapsedMs = 60_000L)
        assertEquals(0L, done.remainingMs)
        assertTrue(done.isCompleted)
    }

    @Test
    fun hiitSwitchesBetweenWorkAndRestAndCompletes() {
        // 30s work, 15s rest, 3 rounds => total = 3*30 + 2*15 = 120s
        val config = WorkoutConfig(
            mode = WorkoutModeType.HIIT,
            hiitWorkSeconds = 30,
            hiitRestSeconds = 15,
            hiitRounds = 3
        )

        // 10s -> Round 1 WORK, 20s remaining
        val r1Work = WorkoutModeEvaluator.evaluate(config, jumpCount = 15, elapsedMs = 10_000L)
        assertEquals(HiitPhase.WORK, r1Work.hiitPhase)
        assertEquals(1, r1Work.currentRound)
        assertEquals(20_000L, r1Work.remainingMs)
        assertTrue(r1Work.isCountingAllowed)

        // 35s -> Round 1 REST, 10s remaining, counting paused
        val r1Rest = WorkoutModeEvaluator.evaluate(config, jumpCount = 55, elapsedMs = 35_000L)
        assertEquals(HiitPhase.REST, r1Rest.hiitPhase)
        assertEquals(1, r1Rest.currentRound)
        assertEquals(10_000L, r1Rest.remainingMs)
        assertFalse(r1Rest.isCountingAllowed)

        // 50s -> Round 2 WORK, 25s remaining
        val r2Work = WorkoutModeEvaluator.evaluate(config, jumpCount = 60, elapsedMs = 50_000L)
        assertEquals(HiitPhase.WORK, r2Work.hiitPhase)
        assertEquals(2, r2Work.currentRound)
        assertEquals(25_000L, r2Work.remainingMs)
        assertTrue(r2Work.isCountingAllowed)

        // 120s -> Completed
        val finished = WorkoutModeEvaluator.evaluate(config, jumpCount = 180, elapsedMs = 120_000L)
        assertTrue(finished.isCompleted)
        assertEquals(1f, finished.progressFraction, 0.001f)
    }

    @Test
    fun cumulativeStatsAggregatesHistoryCorrectly() {
        val sessions = listOf(
            WorkoutSession("1", 1000L, "Mode Bebas", WorkoutModeType.FREE, 120, 60_000L, 120, 140, 14.5, 55),
            WorkoutSession("2", 2000L, "HIIT 30s/15s × 5", WorkoutModeType.HIIT, 250, 210_000L, 110, 150, 32.0, 90)
        )
        val stats = WorkoutHistoryRepository.computeStats(sessions)
        assertEquals(2, stats.totalSessions)
        assertEquals(370, stats.totalJumps)
        assertEquals(270_000L, stats.totalDurationMs)
        assertEquals(46.5, stats.totalCalories, 0.01)
        assertEquals(90, stats.allTimeBestStreak)
    }
}
