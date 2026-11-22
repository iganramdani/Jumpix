package com.jumpropeai.metrics

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Tracks live jump rope metrics: duration, rolling RPM (pace), peak RPM,
 * unbroken jump streak, best streak, and MET-based calorie estimation.
 */
class MetricsTracker(
    private val userWeightKg: Double = 65.0,
    private val rpmWindowMs: Long = 10_000L,
    private val streakTimeoutMs: Long = 2_200L
) {
    private val jumpTimestamps = ArrayDeque<Long>()

    var jumpCount: Int = 0
        private set
    var currentStreak: Int = 0
        private set
    var bestStreak: Int = 0
        private set
    var peakRpm: Int = 0
        private set
    var estimatedCalories: Double = 0.0
        private set

    private var lastJumpMs: Long? = null
    private var accumulatedElapsedMs: Long = 0L
    private var activeSegmentStartMs: Long? = null

    fun start(nowMs: Long) {
        if (activeSegmentStartMs == null) {
            activeSegmentStartMs = nowMs
        }
    }

    fun pause(nowMs: Long) {
        activeSegmentStartMs?.let { start ->
            accumulatedElapsedMs += max(0L, nowMs - start)
            activeSegmentStartMs = null
        }
    }

    fun reset() {
        jumpTimestamps.clear()
        jumpCount = 0
        currentStreak = 0
        bestStreak = 0
        peakRpm = 0
        estimatedCalories = 0.0
        lastJumpMs = null
        accumulatedElapsedMs = 0L
        activeSegmentStartMs = null
    }

    fun recordJump(timestampMs: Long) {
        jumpCount++

        val prevJumpMs = lastJumpMs
        // Update streak: continues if within streakTimeoutMs of previous jump.
        if (prevJumpMs != null && (timestampMs - prevJumpMs) <= streakTimeoutMs) {
            currentStreak++
        } else {
            currentStreak = 1
        }
        if (currentStreak > bestStreak) {
            bestStreak = currentStreak
        }

        // Update rolling RPM window.
        jumpTimestamps.addLast(timestampMs)
        pruneOldJumps(timestampMs)
        val rpm = computeCurrentRpm(timestampMs)
        if (rpm > peakRpm) {
            peakRpm = rpm
        }

        // Estimate calories per jump scaled by intensity (MET 8.8 - 12.3 for jump rope).
        val met = when {
            rpm >= 120 -> 12.3
            rpm >= 100 -> 11.8
            rpm >= 80 -> 10.5
            else -> 8.8
        }
        // Standard MET formula: kcal/min = MET * 3.5 * weightKg / 200.
        // Convert to per-jump using actual interval (clamped 0.25s..1.5s) for smooth real-time increments.
        val deltaSec = if (prevJumpMs != null) {
            ((timestampMs - prevJumpMs) / 1000.0).coerceIn(0.25, 1.5)
        } else {
            0.55
        }
        val kcalPerSec = (met * 3.5 * userWeightKg / 200.0) / 60.0
        estimatedCalories += kcalPerSec * deltaSec

        lastJumpMs = timestampMs
    }

    fun getElapsedMs(nowMs: Long): Long {
        val activeDelta = activeSegmentStartMs?.let { max(0L, nowMs - it) } ?: 0L
        return accumulatedElapsedMs + activeDelta
    }

    fun getCurrentRpm(nowMs: Long): Int {
        pruneOldJumps(nowMs)
        val last = lastJumpMs ?: return 0
        // If user stopped jumping for > 3 seconds, pace drops to 0.
        if ((nowMs - last) > 3_000L) {
            return 0
        }
        return computeCurrentRpm(nowMs)
    }

    fun getActiveStreak(nowMs: Long): Int {
        val last = lastJumpMs ?: return 0
        if ((nowMs - last) > streakTimeoutMs) {
            return 0
        }
        return currentStreak
    }

    private fun pruneOldJumps(nowMs: Long) {
        val cutoff = nowMs - rpmWindowMs
        while (jumpTimestamps.isNotEmpty() && jumpTimestamps.first() < cutoff) {
            jumpTimestamps.removeFirst()
        }
    }

    private fun computeCurrentRpm(nowMs: Long): Int {
        val count = jumpTimestamps.size
        if (count < 2) return 0
        val spanMs = max(nowMs - jumpTimestamps.first(), 1_000L)
        // Use actual span for fast ramp-up during first 10 seconds, capped by rpmWindowMs.
        val effectiveWindowMs = spanMs.coerceAtMost(rpmWindowMs)
        return ((count - 1) * 60_000.0 / effectiveWindowMs).roundToInt()
    }
}
