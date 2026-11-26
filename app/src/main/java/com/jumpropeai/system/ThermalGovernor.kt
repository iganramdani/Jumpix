package com.jumpropeai.system

import android.content.Context
import android.os.Build
import android.os.PowerManager

enum class ThermalTier(val label: String, val recommendEcoFps: Boolean, val disableGlowEffects: Boolean) {
    COOL("Cool", false, false),
    WARM("Warm (Auto-Eco)", true, false),
    HOT("Hot (Thermal Guard)", true, true)
}

/**
 * Jumpvex v6.0 — Pre-Throttling Thermal Governor.
 *
 * Listens to Android's thermal headroom / status (API 29+) so long 30-min outdoor HIIT sessions
 * proactively dial down visual shader glow & analysis FPS BEFORE the OS throttles CPU clocks.
 */
class ThermalGovernor(context: Context?) {

    private val powerManager = context?.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    @Volatile
    var currentTier: ThermalTier = ThermalTier.COOL
        private set

    fun start(onTierChanged: (ThermalTier) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = powerManager ?: return
        currentTier = mapAndroidThermalStatus(pm.currentThermalStatus)
        onTierChanged(currentTier)
        val l = PowerManager.OnThermalStatusChangedListener { status ->
            val mapped = mapAndroidThermalStatus(status)
            if (mapped != currentTier) {
                currentTier = mapped
                onTierChanged(mapped)
            }
        }
        listener = l
        runCatching { pm.addThermalStatusListener(l) }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = powerManager ?: return
        listener?.let { runCatching { pm.removeThermalStatusListener(it) } }
        listener = null
    }

    companion object {
        fun mapAndroidThermalStatus(status: Int): ThermalTier = when {
            status >= PowerManager.THERMAL_STATUS_SEVERE -> ThermalTier.HOT
            status >= PowerManager.THERMAL_STATUS_MODERATE -> ThermalTier.WARM
            else -> ThermalTier.COOL
        }
    }
}
