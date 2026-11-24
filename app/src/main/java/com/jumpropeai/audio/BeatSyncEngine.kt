package com.jumpropeai.audio

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

data class BeatSyncSnapshot(
    val detectedBpm: Int = 0,
    val beatConfidence: Float = 0f,
    val beatSyncScore: Int = 0,
    val syncedMovesCount: Int = 0
)

/**
 * Zero-GC Real-Time Music BPM & Shuffle Beat-Sync Engine (v14) powered by 100 Hz STFT Flux.
 *
 * Upgrades over v13:
 * - Unbiased lag normalization `(n / (n - lag))` eliminates systematic octave errors on 80–115 BPM tracks.
 * - Prioritizes the 60–250 Hz `BASS` kick-drum band when present so rope-slap transients in high
 *   bands do not self-lock as fake music beats.
 */
class BeatSyncEngine {

    private companion object {
        const val MIN_LAG = 34 // ~176 BPM at 100 Hz (340ms)
        const val MAX_LAG = 75 // 80 BPM at 100 Hz (750ms)
        const val WINDOW_SAMPLES = 360 // 3.6 seconds at 100 Hz
    }

    private val centeredFlux = FloatArray(WINDOW_SAMPLES)
    private var smoothedBpm = 0f
    private var lastBeatAnchorMs = 0L
    private var beatPeriodMs = 0f
    private var confidence = 0f

    private var syncAccum = 0f
    private var syncCount = 0
    private var lastUpdateMs = -10_000L

    fun reset() {
        smoothedBpm = 0f
        lastBeatAnchorMs = 0L
        beatPeriodMs = 0f
        confidence = 0f
        syncAccum = 0f
        syncCount = 0
        lastUpdateMs = -10_000L
    }

    fun snapshot(): BeatSyncSnapshot = BeatSyncSnapshot(
        detectedBpm = smoothedBpm.roundToInt(),
        beatConfidence = confidence,
        beatSyncScore = if (syncCount > 0) (syncAccum / syncCount).roundToInt().coerceIn(0, 100) else 0,
        syncedMovesCount = syncCount
    )

    /**
     * Updates BPM estimate from the latest 100 Hz STFT flux history (throttled to ~2 Hz for efficiency).
     */
    fun updateFromAudio(audioHistory: List<FluxSample>, nowMs: Long): BeatSyncSnapshot {
        if (audioHistory.size < 180 || (nowMs - lastUpdateMs) < 500L) {
            return snapshot()
        }
        lastUpdateMs = nowMs

        val n = min(WINDOW_SAMPLES, audioHistory.size)
        val startOffset = audioHistory.size - n
        var bassSum = 0f
        for (i in 0 until n) {
            bassSum += audioHistory[startOffset + i].bass
        }
        val hasBassContent = (bassSum / n) > 1e-4f

        var sum = 0f
        for (i in 0 until n) {
            val s = audioHistory[startOffset + i]
            val v = if (hasBassContent) {
                s.bass * 0.58f + s.mid * 0.32f + s.wideHigh * 0.10f
            } else {
                s.mid * 0.70f + s.wideHigh * 0.30f
            }
            centeredFlux[i] = v
            sum += v
        }
        val mean = sum / n
        var varianceSum = 0f
        for (i in 0 until n) {
            val d = centeredFlux[i] - mean
            centeredFlux[i] = d
            varianceSum += d * d
        }
        val variance = varianceSum / n
        if (variance < 1e-6f) {
            confidence *= 0.8f
            return snapshot()
        }

        var bestLag = -1
        var bestCorr = 0f
        for (lag in MIN_LAG..MAX_LAG) {
            var corr = 0f
            val limit = n - lag
            var i = 0
            while (i < limit) {
                corr += centeredFlux[i] * centeredFlux[i + lag]
                i++
            }
            // Unbiased autocorrelation normalized by overlap count `limit`
            val unbiasedNorm = (corr / limit.toFloat()) / variance
            if (unbiasedNorm > bestCorr) {
                bestCorr = unbiasedNorm
                bestLag = lag
            }
        }

        if (bestLag > 0 && bestCorr >= 0.14f) {
            val periodMs = bestLag * 10f
            val rawBpm = 60_000f / periodMs
            smoothedBpm = if (smoothedBpm <= 1f) rawBpm else smoothedBpm * 0.72f + rawBpm * 0.28f
            beatPeriodMs = 60_000f / smoothedBpm
            confidence = (bestCorr * 2.0f).coerceIn(0f, 1f)

            // Anchor beat phase to strongest low/mid onset in the last 1.2s
            val anchorSearchStart = (audioHistory.size - 120).coerceAtLeast(0)
            var peakVal = -1f
            var peakTs = lastBeatAnchorMs
            for (i in anchorSearchStart until audioHistory.size) {
                val s = audioHistory[i]
                val v = if (hasBassContent) s.bass * 0.65f + s.mid * 0.35f else s.mid
                if (v > peakVal) {
                    peakVal = v
                    peakTs = s.timestampMs
                }
            }
            if (peakTs > 0L) lastBeatAnchorMs = peakTs
        } else {
            confidence *= 0.85f
        }

        return snapshot()
    }

    /**
     * Scores how closely a detected jump peak or Shuffle Dance step landed on the musical beat grid.
     */
    fun recordMoveOnBeat(moveTimestampMs: Long) {
        if (beatPeriodMs < 300f || confidence < 0.25f || lastBeatAnchorMs <= 0L) return
        val delta = abs(moveTimestampMs - lastBeatAnchorMs).toFloat()
        val phaseMod = delta % beatPeriodMs
        val distToNearestBeat = min(phaseMod, beatPeriodMs - phaseMod)
        val halfBeat = beatPeriodMs * 0.5f
        val score = ((1f - (distToNearestBeat / halfBeat)) * 100f).coerceIn(0f, 100f)
        syncAccum += score
        syncCount++
    }
}
