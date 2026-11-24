package com.jumpropeai.metrics

import kotlin.math.max
import kotlin.math.roundToInt

enum class RopeType(val label: String, val metMultiplier: Double) {
    SPEED_CABLE("Speed Cable", 1.00),
    PVC_FREESTYLE("PVC Freestyle", 1.04),
    BEADED("Beaded Rope", 1.08),
    WEIGHTED_HALF_LB("Weighted 0.5 lb", 1.18),
    WEIGHTED_ONE_LB("Weighted 1.0 lb", 1.32),
    CORDLESS("Cordless", 0.92)
}

/**
 * Thread-safe live jump rope metrics tracker: duration, rolling RPM, peak RPM,
 * unbroken jump streak, best streak, and athlete-weight + rope-weighted MET calorie estimation.
 */
class MetricsTracker(
    userWeightKg: Double = 65.0,
    private val rpmWindowMs: Long = 10_000L,
    private val streakTimeoutMs: Long = 2_200L
) {
    private val jumpTimestamps = ArrayDeque<Long>()

    @Volatile
    private var weightKg: Double = userWeightKg.coerceIn(30.0, 200.0)
    @Volatile
    private var ropeType: RopeType = RopeType.SPEED_CABLE

    @get:Synchronized
    var jumpCount: Int = 0
        private set

    @get:Synchronized
    var currentStreak: Int = 0
        private set

    @get:Synchronized
    var bestStreak: Int = 0
        private set

    @get:Synchronized
    var peakRpm: Int = 0
        private set

    @get:Synchronized
    var estimatedCalories: Double = 0.0
        private set

    private var lastJumpMs: Long? = null
    private var accumulatedElapsedMs: Long = 0L
    private var activeSegmentStartMs: Long? = null

    @Synchronized
    fun updateAthleteProfile(newWeightKg: Double, newRopeType: RopeType) {
        weightKg = newWeightKg.coerceIn(30.0, 200.0)
        ropeType = newRopeType
    }

    @Synchronized
    fun start(nowMs: Long) {
        if (activeSegmentStartMs == null) {
            activeSegmentStartMs = nowMs
        }
    }

    @Synchronized
    fun pause(nowMs: Long) {
        activeSegmentStartMs?.let { start ->
            accumulatedElapsedMs += max(0L, nowMs - start)
            activeSegmentStartMs = null
        }
    }

    @Synchronized
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

    @Synchronized
    fun recordJump(timestampMs: Long) {
        jumpCount++

        val prevJumpMs = lastJumpMs
        if (prevJumpMs != null && (timestampMs - prevJumpMs) <= streakTimeoutMs) {
            currentStreak++
        } else {
            currentStreak = 1
        }
        if (currentStreak > bestStreak) {
            bestStreak = currentStreak
        }

        jumpTimestamps.addLast(timestampMs)
        pruneOldJumps(timestampMs)
        val rpm = computeCurrentRpm(timestampMs)
        if (rpm > peakRpm) {
            peakRpm = rpm
        }

        // ACSM Jump Rope MET curve scaled by athlete body weight & rope resistance
        val baseMet = when {
            rpm >= 160 -> 13.2
            rpm >= 120 -> 12.3
            rpm >= 100 -> 11.8
            rpm >= 80 -> 10.5
            else -> 8.8
        }
        val effectiveMet = baseMet * ropeType.metMultiplier
        val deltaSec = if (prevJumpMs != null) {
            ((timestampMs - prevJumpMs) / 1000.0).coerceIn(0.18, 1.5)
        } else {
            0.55
        }
        val kcalPerSec = (effectiveMet * 3.5 * weightKg / 200.0) / 60.0
        estimatedCalories += kcalPerSec * deltaSec

        lastJumpMs = timestampMs
    }

    @Synchronized
    fun getElapsedMs(nowMs: Long): Long {
        val activeDelta = activeSegmentStartMs?.let { max(0L, nowMs - it) } ?: 0L
        return accumulatedElapsedMs + activeDelta
    }

    @Synchronized
    fun getCurrentRpm(nowMs: Long): Int {
        pruneOldJumps(nowMs)
        val last = lastJumpMs ?: return 0
        if ((nowMs - last) > 3_000L) {
            return 0
        }
        return computeCurrentRpm(nowMs)
    }

    @Synchronized
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
        val effectiveWindowMs = spanMs.coerceAtMost(rpmWindowMs)
        return ((count - 1) * 60_000.0 / effectiveWindowMs).roundToInt()
    }
}
