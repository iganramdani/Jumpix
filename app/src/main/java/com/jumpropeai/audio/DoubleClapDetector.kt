package com.jumpropeai.audio

/**
 * Jumpvex v4.2 — Acoustic Double-Clap Hands-Free Trigger (Zero-GC).
 *
 * Uses the existing 100 Hz STFT high-frequency transient flux (2.5–7 kHz) to recognize two sharp
 * hand-claps spaced 180–450 ms apart while in standby/paused mode, allowing hands-free Start/Pause
 * from 5+ meters even when the camera is off (Audio Eco / Pocket IMU).
 */
class DoubleClapDetector {

    private companion object {
        const val MIN_CLAP_FLUX = 3.2f
        const val MIN_HIGH_TO_MID_RATIO = 1.6f
        const val CLAP_MIN_GAP_MS = 180L
        const val CLAP_MAX_GAP_MS = 480L
        const val TRIGGER_COOLDOWN_MS = 2_000L
        const val SINGLE_CLAP_REFRACTORY_MS = 110L
    }

    private var ambientHighEma = 0.25f
    private var lastClapMs = -10_000L
    private var lastTriggerMs = -10_000L

    fun reset() {
        ambientHighEma = 0.25f
        lastClapMs = -10_000L
        lastTriggerMs = -10_000L
    }

    /**
     * Evaluates a 100 Hz STFT [FluxSample]. Returns `true` when a clean double-clap is confirmed.
     */
    fun processFlux(sample: FluxSample): Boolean {
        val high = sample.wideHigh
        val mid = sample.mid.coerceAtLeast(0.05f)
        val ts = sample.timestampMs

        val threshold = (ambientHighEma * 5.2f).coerceAtLeast(MIN_CLAP_FLUX)
        val isSharpClap = high >= threshold && (high / mid) >= MIN_HIGH_TO_MID_RATIO

        if (!isSharpClap) {
            ambientHighEma += 0.03f * (high - ambientHighEma)
            return false
        }

        if (ts - lastTriggerMs < TRIGGER_COOLDOWN_MS) return false
        if (ts - lastClapMs < SINGLE_CLAP_REFRACTORY_MS) return false

        val gap = ts - lastClapMs
        return if (gap in CLAP_MIN_GAP_MS..CLAP_MAX_GAP_MS) {
            lastClapMs = -10_000L
            lastTriggerMs = ts
            true
        } else {
            lastClapMs = ts
            false
        }
    }
}
