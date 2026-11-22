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
    val bestStreak: Int
)

data class CumulativeStats(
    val totalSessions: Int = 0,
    val totalJumps: Int = 0,
    val totalDurationMs: Long = 0L,
    val totalCalories: Double = 0.0,
    val allTimeBestStreak: Int = 0
)
