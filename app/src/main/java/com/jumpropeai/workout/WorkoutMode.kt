package com.jumpropeai.workout

enum class WorkoutModeType(val label: String) {
    FREE("Bebas"),
    TARGET_JUMPS("Target Lompat"),
    TARGET_TIME("Target Waktu"),
    HIIT("HIIT")
}

enum class HiitPhase {
    WORK,
    REST
}

data class WorkoutConfig(
    val mode: WorkoutModeType = WorkoutModeType.FREE,
    val targetJumps: Int = 100,
    val targetTimeSeconds: Int = 180,
    val hiitWorkSeconds: Int = 30,
    val hiitRestSeconds: Int = 15,
    val hiitRounds: Int = 5,
    val milestoneInterval: Int = 25,
    val customLabel: String = ""
) {
    fun shortLabel(): String = when (mode) {
        WorkoutModeType.FREE -> {
            val name = customLabel.trim()
            if (name.isNotEmpty()) name else "Mode Bebas"
        }
        WorkoutModeType.TARGET_JUMPS -> "Target $targetJumps Lompat"
        WorkoutModeType.TARGET_TIME -> {
            val mins = targetTimeSeconds / 60
            val secs = targetTimeSeconds % 60
            when {
                mins > 0 && secs > 0 -> "Target ${mins}m ${secs}d"
                mins > 0 -> "Target $mins Menit"
                else -> "Target ${secs} Detik"
            }
        }
        WorkoutModeType.HIIT -> "HIIT ${hiitWorkSeconds}s/${hiitRestSeconds}s × $hiitRounds"
    }
}

data class WorkoutProgress(
    val progressFraction: Float = 0f,
    val remainingMs: Long? = null,
    val hiitPhase: HiitPhase = HiitPhase.WORK,
    val currentRound: Int = 1,
    val totalRounds: Int = 1,
    val isCompleted: Boolean = false,
    val isCountingAllowed: Boolean = true
)

/**
 * Pure Kotlin evaluator for workout modes (Free, Target Jumps, Target Time, HIIT Interval).
 */
object WorkoutModeEvaluator {

    fun evaluate(
        config: WorkoutConfig,
        jumpCount: Int,
        elapsedMs: Long
    ): WorkoutProgress {
        return when (config.mode) {
            WorkoutModeType.FREE -> WorkoutProgress(
                progressFraction = 0f,
                remainingMs = null,
                isCompleted = false,
                isCountingAllowed = true
            )

            WorkoutModeType.TARGET_JUMPS -> {
                val target = config.targetJumps.coerceAtLeast(1)
                val fraction = (jumpCount.toFloat() / target).coerceIn(0f, 1f)
                WorkoutProgress(
                    progressFraction = fraction,
                    remainingMs = null,
                    isCompleted = jumpCount >= target,
                    isCountingAllowed = jumpCount < target
                )
            }

            WorkoutModeType.TARGET_TIME -> {
                val targetMs = config.targetTimeSeconds.coerceAtLeast(1) * 1000L
                val remaining = (targetMs - elapsedMs).coerceAtLeast(0L)
                val fraction = (elapsedMs.toFloat() / targetMs).coerceIn(0f, 1f)
                WorkoutProgress(
                    progressFraction = fraction,
                    remainingMs = remaining,
                    isCompleted = elapsedMs >= targetMs,
                    isCountingAllowed = elapsedMs < targetMs
                )
            }

            WorkoutModeType.HIIT -> {
                val workMs = config.hiitWorkSeconds.coerceAtLeast(5) * 1000L
                val restMs = config.hiitRestSeconds.coerceAtLeast(5) * 1000L
                val rounds = config.hiitRounds.coerceAtLeast(1)
                // Total duration: N work intervals + (N - 1) rest intervals
                val cycleMs = workMs + restMs
                val totalMs = rounds * workMs + (rounds - 1) * restMs

                if (elapsedMs >= totalMs) {
                    return WorkoutProgress(
                        progressFraction = 1f,
                        remainingMs = 0L,
                        hiitPhase = HiitPhase.WORK,
                        currentRound = rounds,
                        totalRounds = rounds,
                        isCompleted = true,
                        isCountingAllowed = false
                    )
                }

                val roundIndex = (elapsedMs / cycleMs).toInt().coerceIn(0, rounds - 1)
                val inCycleMs = elapsedMs % cycleMs
                val isWork = inCycleMs < workMs
                val phaseRemainingMs = if (isWork) {
                    workMs - inCycleMs
                } else {
                    cycleMs - inCycleMs
                }
                val fraction = (elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f)

                WorkoutProgress(
                    progressFraction = fraction,
                    remainingMs = phaseRemainingMs,
                    hiitPhase = if (isWork) HiitPhase.WORK else HiitPhase.REST,
                    currentRound = roundIndex + 1,
                    totalRounds = rounds,
                    isCompleted = false,
                    isCountingAllowed = isWork
                )
            }
        }
    }
}
