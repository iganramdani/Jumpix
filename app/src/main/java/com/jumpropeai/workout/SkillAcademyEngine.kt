package com.jumpropeai.workout

import com.jumpropeai.data.WorkoutSession

data class SkillNode(
    val id: String,
    val tier: Int,
    val title: String,
    val requirementText: String,
    val unlocked: Boolean,
    val progressPct: Int
)

data class AdaptiveWeekPlan(
    val weekNumber: Int,
    val focusTitle: String,
    val targetRpm: Int,
    val dailyJumpTarget: Int,
    val hiitWorkSec: Int,
    val hiitRestSec: Int
)

/**
 * Jumpvex v5.0 — AI-Verified Interactive Skill Tree DAG & 6-Week Adaptive Training Engine.
 *
 * Unlike manual checklist apps, every Skill Tree node unlocks ONLY when real on-device AI
 * workout sessions verify the physical reps, form score, and zero-trip streak criteria.
 */
object SkillAcademyEngine {

    fun evaluateSkillTree(sessions: List<WorkoutSession>): List<SkillNode> {
        val totalJumps = sessions.sumOf { it.jumpCount }
        val bestStreak = sessions.maxOfOrNull { it.bestStreak } ?: 0
        val bestBoxerClean = sessions.filter { it.formScore >= 80 && it.trips == 0 }
            .maxOfOrNull { it.moveBreakdown["Boxer Step"] ?: 0 } ?: 0
        val totalCrissCross = sessions.sumOf { it.moveBreakdown["Criss-Cross"] ?: 0 }
        val bestDuSession = sessions.maxOfOrNull { it.moveBreakdown["Double Under"] ?: 0 } ?: 0
        val totalShuffle = sessions.sumOf {
            (it.moveBreakdown["Running Man"] ?: 0) +
                (it.moveBreakdown["T-Step"] ?: 0) +
                (it.moveBreakdown["Charleston"] ?: 0)
        }
        val best30s = sessions.filter { it.modeType == WorkoutModeType.IJRU_SPEED_30S }
            .maxOfOrNull { it.jumpCount } ?: 0

        return listOf(
            SkillNode(
                id = "rookie_100",
                tier = 1,
                title = "Tier 1 • Clean 100 Streak",
                requirementText = "100 unbroken jumps in a single streak",
                unlocked = bestStreak >= 100,
                progressPct = ((bestStreak / 100f) * 100f).toInt().coerceIn(0, 100)
            ),
            SkillNode(
                id = "rhythm_boxer",
                tier = 2,
                title = "Tier 2 • Rhythm Boxer",
                requirementText = "15+ Boxer Steps with Form ≥ 80% & 0 Trips",
                unlocked = bestBoxerClean >= 15,
                progressPct = ((bestBoxerClean / 15f) * 100f).toInt().coerceIn(0, 100)
            ),
            SkillNode(
                id = "freestyle_ninja",
                tier = 3,
                title = "Tier 3 • Freestyle Ninja",
                requirementText = "50 total Criss-Crosses OR 20 Double Unders in 1 session",
                unlocked = totalCrissCross >= 50 || bestDuSession >= 20,
                progressPct = maxOf(
                    ((totalCrissCross / 50f) * 100f).toInt(),
                    ((bestDuSession / 20f) * 100f).toInt()
                ).coerceIn(0, 100)
            ),
            SkillNode(
                id = "shuffle_master",
                tier = 4,
                title = "Tier 4 • Melbourne Shuffle Master",
                requirementText = "200 AI-verified Shuffle steps (Running Man / T-Step / Charleston)",
                unlocked = totalShuffle >= 200,
                progressPct = ((totalShuffle / 200f) * 100f).toInt().coerceIn(0, 100)
            ),
            SkillNode(
                id = "ijru_legend",
                tier = 5,
                title = "Tier 5 • IJRU Speed Legend",
                requirementText = "90+ jumps in IJRU 30s Sprint & 10,000 lifetime jumps",
                unlocked = best30s >= 90 && totalJumps >= 10_000,
                progressPct = (((best30s.coerceAtMost(90) / 90f) * 50f) +
                    ((totalJumps.coerceAtMost(10_000) / 10_000f) * 50f)).toInt().coerceIn(0, 100)
            )
        )
    }

    fun buildAdaptivePlan(sessions: List<WorkoutSession>): List<AdaptiveWeekPlan> {
        val recent = sessions.take(7)
        val avgRpm = if (recent.isNotEmpty()) {
            recent.map { it.averageRpm }.average().toInt().coerceIn(110, 200)
        } else 125
        val avgForm = if (recent.isNotEmpty()) {
            recent.map { it.formScore }.filter { it > 0 }.let { l -> if (l.isNotEmpty()) l.average().toInt() else 80 }
        } else 80
        val stepBoost = if (avgForm >= 85) 8 else 4

        return listOf(
            AdaptiveWeekPlan(1, "Foundation & Soft Landings", avgRpm, 500, 30, 30),
            AdaptiveWeekPlan(2, "Boxer Step Endurance", avgRpm + stepBoost, 750, 40, 20),
            AdaptiveWeekPlan(3, "Criss-Cross & Rhythm Flow", avgRpm + stepBoost * 2, 1_000, 45, 20),
            AdaptiveWeekPlan(4, "Double-Under Power Prep", avgRpm + stepBoost * 2, 1_200, 45, 15),
            AdaptiveWeekPlan(5, "IJRU 30s Sprint Intervals", (avgRpm + stepBoost * 3).coerceAtMost(230), 1_500, 30, 15),
            AdaptiveWeekPlan(6, "Peak Competition Test", (avgRpm + stepBoost * 4).coerceAtMost(240), 1_800, 60, 15)
        )
    }
}
