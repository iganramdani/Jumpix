package com.jumpropeai.ai

import kotlin.math.abs
import kotlin.math.roundToInt

data class PlyometricSnapshot(
    val lastJumpHeightCm: Float = 0f,
    val avgJumpHeightCm: Float = 0f,
    val lastGctMs: Int = 0,
    val avgGctMs: Int = 0,
    val reactiveStrengthIndex: Float = 0f,
    val lrSymmetryPct: Int = 100,
    val efficiencyLabel: String = "Calibrating Force-Plate..."
)

/**
 * Jumpvex v4.5 — Virtual Plyometric Force-Plate (Zero-GC).
 *
 * Derives sports-science lab metrics strictly from smartphone camera + 100 Hz audio timestamps:
 * - Flight Time (t_f, ms) & Ballistic Jump Height: h = (1/8) * g * t_f^2 (cm)
 * - Ground Contact Time (GCT, ms) between consecutive airborne phases
 * - Reactive Strength Index: RSI = h(m) / GCT(s)
 * - Left / Right foot-lift symmetry (0–100%) to flag shin-splint overload risk.
 */
class PlyometricAnalyzer {

    private companion object {
        const val GRAVITY_MS2 = 9.80665f
        const val MIN_FLIGHT_MS = 90L
        const val MAX_FLIGHT_MS = 650L
        const val MIN_GCT_MS = 70L
        const val MAX_GCT_MS = 600L
        const val RING_SIZE = 32
    }

    private var wasAirborne = false
    private var takeoffMs = -1L
    private var lastLandingMs = -1L

    private val heightRing = FloatArray(RING_SIZE)
    private var heightHead = 0
    private var heightCount = 0

    private val gctRing = IntArray(RING_SIZE)
    private var gctHead = 0
    private var gctCount = 0

    private var symSum = 0f
    private var symCount = 0

    private var lastHeightCm = 0f
    private var lastGctMs = 0

    fun reset() {
        wasAirborne = false
        takeoffMs = -1L
        lastLandingMs = -1L
        heightHead = 0
        heightCount = 0
        gctHead = 0
        gctCount = 0
        symSum = 0f
        symCount = 0
        lastHeightCm = 0f
        lastGctMs = 0
    }

    /**
     * Feeds airborne state & left/right ankle heights per frame (Zero-GC).
     */
    fun processFrame(
        timestampMs: Long,
        isAirborne: Boolean,
        leftAnkleY: Float = Float.NaN,
        rightAnkleY: Float = Float.NaN,
        bodyHeightNorm: Float = 0.45f
    ): PlyometricSnapshot {
        if (isAirborne && !wasAirborne) {
            // Takeoff edge
            takeoffMs = timestampMs
            if (lastLandingMs > 0L) {
                val gct = (timestampMs - lastLandingMs).toInt()
                if (gct in MIN_GCT_MS..MAX_GCT_MS) {
                    lastGctMs = gct
                    gctRing[gctHead] = gct
                    gctHead = (gctHead + 1) % RING_SIZE
                    if (gctCount < RING_SIZE) gctCount++
                }
            }
        } else if (!isAirborne && wasAirborne && takeoffMs > 0L) {
            // Landing edge
            val flightMs = timestampMs - takeoffMs
            lastLandingMs = timestampMs
            if (flightMs in MIN_FLIGHT_MS..MAX_FLIGHT_MS) {
                val tfSec = flightMs / 1000f
                // h = 1/8 * g * t_f^2 in meters -> * 100 for cm
                val heightCm = (0.125f * GRAVITY_MS2 * tfSec * tfSec * 100f).coerceIn(1.0f, 55.0f)
                lastHeightCm = heightCm
                heightRing[heightHead] = heightCm
                heightHead = (heightHead + 1) % RING_SIZE
                if (heightCount < RING_SIZE) heightCount++
            }
        }
        wasAirborne = isAirborne

        if (isAirborne && !leftAnkleY.isNaN() && !rightAnkleY.isNaN() && bodyHeightNorm > 0.1f) {
            val asymRatio = abs(leftAnkleY - rightAnkleY) / bodyHeightNorm
            val frameSym = (100f - asymRatio * 420f).coerceIn(45f, 100f)
            symSum += frameSym
            symCount++
        }

        return snapshot()
    }

    fun snapshot(): PlyometricSnapshot {
        val avgH = if (heightCount > 0) {
            var s = 0f
            for (i in 0 until heightCount) s += heightRing[i]
            s / heightCount
        } else 0f

        val avgGct = if (gctCount > 0) {
            var s = 0
            for (i in 0 until gctCount) s += gctRing[i]
            s / gctCount
        } else 0

        val rsi = if (avgH > 0f && avgGct > 0) {
            (avgH / 100f) / (avgGct / 1000f)
        } else 0f

        val sym = if (symCount > 0) (symSum / symCount).roundToInt().coerceIn(0, 100) else 100

        val label = when {
            heightCount < 3 -> "Calibrating Force-Plate..."
            avgGct in 1..155 && avgH <= 6.5f -> "Elite Elastic Spring (GCT ${avgGct}ms)"
            avgH > 11.5f -> "High Clearance (${String.format("%.1f", avgH)}cm) — Relax jump height"
            avgGct > 225 -> "Heavy Landing (${avgGct}ms) — Stay on balls of feet"
            else -> "Balanced Plyometrics (RSI ${String.format("%.2f", rsi)})"
        }

        return PlyometricSnapshot(
            lastJumpHeightCm = lastHeightCm,
            avgJumpHeightCm = avgH,
            lastGctMs = lastGctMs,
            avgGctMs = avgGct,
            reactiveStrengthIndex = rsi,
            lrSymmetryPct = sym,
            efficiencyLabel = label
        )
    }
}
