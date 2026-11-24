package com.jumpropeai.data

import com.jumpropeai.workout.WorkoutModeType

data class WorkoutSession(
    val id: String,
    val timestampMillis: Long,
    val modeLabel: String,
    val modeType: WorkoutModeType,
    val jumpCount: Int,
    val durationMs: Long,
    val averageRpm: Int,
    val peakRpm: Int,
    val estimatedCalories: Double,
    val bestStreak: Int,
    val trips: Int = 0,
    val formScore: Int = 0,
    val beatSyncScore: Int = 0,
    val detectedBpm: Int = 0,
    val comboCount: Int = 0,
    val ropeTypeLabel: String = "Speed Cable",
    val moveBreakdown: Map<String, Int> = emptyMap()
)

data class CumulativeStats(
    val totalSessions: Int = 0,
    val totalJumps: Int = 0,
    val totalDurationMs: Long = 0L,
    val totalCalories: Double = 0.0,
    val allTimeBestStreak: Int = 0,
    val activeDayStreak: Int = 0,
    val todayJumps: Int = 0,
    val highestSingleSessionJumps: Int = 0,
    val fastestPeakRpm: Int = 0,
    val bestFormScore: Int = 0,
    val best30sSprintJumps: Int = 0,
    val totalDoubleUnders: Int = 0
)
