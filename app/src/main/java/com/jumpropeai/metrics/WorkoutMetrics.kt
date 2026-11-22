package com.jumpropeai.metrics

import com.jumpropeai.ai.DetectorStatus

data class WorkoutMetrics(
    val jumpCount: Int = 0,
    val elapsedMs: Long = 0L,
    val currentRpm: Int = 0,
    val peakRpm: Int = 0,
    val estimatedCalories: Double = 0.0,
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    val isAirborne: Boolean = false,
    val detectorStatus: DetectorStatus = DetectorStatus.NO_PERSON,
    val calibrationProgress: Float = 0f
)
