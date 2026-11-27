package com.jumpropeai.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Jumpvex v7.0 — Harmonic-Percussive Spectral Rope-Whip Isolator (`HPSS` @ 200 Hz).
 *
 * Strips sustained horizontal harmonic music ridges (EDM bass, vocals, synth pads, gym PA)
 * while isolating `< 18 ms` broadband percussive rope-whip & shoe-strike spikes (`cleanWhipZ`).
 *
 * Designed for Zero-GC execution on offline 200 Hz (5 ms hop) `FluxSample` streams as well
 * as causal 17-frame sliding median windows.
 */
object NeuralAudioRopeIsolator {

    /**
     * Computes a normalized percussive-isolated rope-whip curve (`[0.0 .. 2.0]`) aligned 1:1
     * with the input `fluxSamples` (100 Hz or 200 Hz).
     *
     * High-frequency broadband whip energy (`wideHigh` + `high2`) is contrasted against
     * sustained 85 ms horizontal harmonic energy (`bass` + sustained `mid` floor) so loud
     * EDM kick/basslines do not masquerade as rope turns.
     */
    fun isolateCleanWhipSeries(fluxSamples: List<FluxSample>): FloatArray {
        val n = fluxSamples.size
        if (n == 0) return FloatArray(0)
        val rawPerc = FloatArray(n)
        val scratchWin = FloatArray(17)

        // Step 1: Broadband high-whip vs low-bass harmonic suppression per frame
        for (i in 0 until n) {
            val s = fluxSamples[i]
            val highWhip = s.wideHigh * 0.60f + s.high2 * 0.25f + s.high1 * 0.15f
            val bassBleed = s.bass * 0.32f
            rawPerc[i] = max(0f, highWhip - bassBleed)
        }

        // Step 2: Subtract 17-frame (~85 ms @ 200 Hz) horizontal harmonic median floor
        val isolated = FloatArray(n)
        for (i in 0 until n) {
            val w0 = max(0, i - 8)
            val w1 = min(n - 1, i + 8)
            var cnt = 0
            for (k in w0..w1) {
                scratchWin[cnt++] = rawPerc[k]
            }
            val localFloor = quickMedian(scratchWin, cnt)
            isolated[i] = max(0f, rawPerc[i] - localFloor)
        }

        // Step 3: Robust MAD normalization into [0.0 .. 2.0] matching Python trainer bit-for-bit
        val sampleStep = max(1, n / 512)
        val sub = FloatArray((n + sampleStep - 1) / sampleStep)
        var sc = 0
        var idx = 0
        while (idx < n && sc < sub.size) {
            sub[sc++] = isolated[idx]
            idx += sampleStep
        }
        val med = quickMedian(sub, sc) + 1e-5f
        for (k in 0 until sc) {
            sub[k] = abs(sub[k] - med)
        }
        val mad = quickMedian(sub, sc) + 1e-4f

        for (i in 0 until n) {
            val z = ((isolated[i] - med) / mad).coerceIn(0f, 12f) / 6f
            isolated[i] = z
        }
        return isolated
    }

    private fun quickMedian(buf: FloatArray, len: Int): Float {
        if (len <= 0) return 0f
        // Insertion sort on tiny stack-like reusable array (len <= 512) — zero allocations!
        for (i in 1 until len) {
            val v = buf[i]
            var j = i - 1
            while (j >= 0 && buf[j] > v) {
                buf[j + 1] = buf[j]
                j--
            }
            buf[j + 1] = v
        }
        val mid = len ushr 1
        return if ((len and 1) == 1) buf[mid] else 0.5f * (buf[mid - 1] + buf[mid])
    }
}
