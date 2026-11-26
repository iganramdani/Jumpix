package com.jumpropeai.workout

import kotlin.math.roundToInt

enum class GhostProfile(val label: String, val targetRpm: Int) {
    OFF("Off", 0),
    CRUISER_160("Pro Cruiser (160 RPM)", 160),
    BOXER_190("Boxer Rhythm (190 RPM)", 190),
    IJRU_WORLD_230("IJRU Sprint (230 RPM)", 230),
    PERSONAL_BEST("Personal Best Ghost", 150)
}

data class GhostPacerSnapshot(
    val isActive: Boolean = false,
    val profileLabel: String = "Off",
    val ghostJumpsExpected: Int = 0,
    val deltaJumps: Int = 0,
    val statusText: String = ""
)

/**
 * Jumpvex v5.0 — Real-Time Ghost Rival & Personal-Best Delta Pacer (Zero-GC).
 */
class GhostPacerEngine {

    var activeProfile: GhostProfile = GhostProfile.OFF
        private set
    private var customPbRpm: Int = 150

    fun configure(profile: GhostProfile, personalBestRpm: Int = 150) {
        activeProfile = profile
        customPbRpm = personalBestRpm.coerceIn(90, 320)
    }

    fun evaluate(elapsedMs: Long, actualJumps: Int): GhostPacerSnapshot {
        if (activeProfile == GhostProfile.OFF) return GhostPacerSnapshot()
        val rpm = if (activeProfile == GhostProfile.PERSONAL_BEST) customPbRpm else activeProfile.targetRpm
        val expected = ((elapsedMs / 60_000.0) * rpm).roundToInt().coerceAtLeast(0)
        val delta = actualJumps - expected
        val text = when {
            delta > 0 -> "+$delta AHEAD (${activeProfile.label})"
            delta < 0 -> "$delta BEHIND (${activeProfile.label})"
            else -> "DEAD EVEN (${activeProfile.label})"
        }
        return GhostPacerSnapshot(
            isActive = true,
            profileLabel = activeProfile.label,
            ghostJumpsExpected = expected,
            deltaJumps = delta,
            statusText = text
        )
    }
}
