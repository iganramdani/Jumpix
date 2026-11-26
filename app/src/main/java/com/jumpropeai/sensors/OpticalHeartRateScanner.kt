package com.jumpropeai.sensors

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class HeartRateScanSnapshot(
    val currentBpm: Int = 0,
    val peakBpm: Int = 0,
    val hrr60DropBpm: Int = 0,
    val readinessScore: Int = 0,
    val signalQualityPct: Int = 0,
    val elapsedSec: Int = 0,
    val isFingerDetected: Boolean = false,
    val latestWaveSample: Float = 0f,
    val statusMessage: String = "Place fingertip gently over rear camera lens"
)

/**
 * Jumpvex v4.2 — Zero-Watch Contact Optical Photoplethysmography (rPPG) Heart Rate & HRR-60 Engine.
 *
 * Extracts capillary blood-volume pulse directly from rear-camera + LED flash red/green channel
 * ratio using a Zero-GC band-pass filter (0.75 Hz – 3.67 Hz ≡ 45 – 220 BPM), adaptive IBI peak
 * detection, 60-second Heart Rate Recovery drop (HRR-60), and RMSSD Autonomic Readiness Score.
 */
class OpticalHeartRateScanner {

    private companion object {
        const val MIN_IBI_MS = 272L    // 220 BPM ceiling
        const val MAX_IBI_MS = 1333L   // 45 BPM floor
        const val IBI_BUF_SIZE = 16
        const val MIN_RED_THRESHOLD = 110f
    }

    // DC-removal high-pass + smoothing low-pass (Zero-GC)
    private var dcBaseline = 0f
    private var smoothSignal = 0f
    private var prevSignal = 0f
    private var prevPrevSignal = 0f
    private var signalVarEma = 0.0001f

    private var scanStartMs = -1L
    private var lastPeakMs = -1L
    private var initialStableBpm = 0
    private var peakBpm = 0
    private var latestBpm = 0

    private val ibiRing = LongArray(IBI_BUF_SIZE)
    private var ibiHead = 0
    private var ibiCount = 0

    fun reset() {
        dcBaseline = 0f
        smoothSignal = 0f
        prevSignal = 0f
        prevPrevSignal = 0f
        signalVarEma = 0.0001f
        scanStartMs = -1L
        lastPeakMs = -1L
        initialStableBpm = 0
        peakBpm = 0
        latestBpm = 0
        ibiHead = 0
        ibiCount = 0
    }

    /**
     * Feeds mean red & green channel intensities (`0..255`) from a 176x144 rear-camera frame.
     */
    fun processOpticalSample(
        meanRed: Float,
        meanGreen: Float,
        timestampMs: Long
    ): HeartRateScanSnapshot {
        val fingerPresent = meanRed >= MIN_RED_THRESHOLD && meanRed > meanGreen * 1.15f
        if (!fingerPresent) {
            return HeartRateScanSnapshot(
                currentBpm = latestBpm,
                peakBpm = peakBpm,
                hrr60DropBpm = computeHrr60(),
                readinessScore = computeReadinessScore(),
                signalQualityPct = 0,
                elapsedSec = if (scanStartMs > 0L) ((timestampMs - scanStartMs) / 1000L).toInt() else 0,
                isFingerDetected = false,
                statusMessage = "Cover rear camera lens + flash with your fingertip"
            )
        }

        if (scanStartMs < 0L) {
            scanStartMs = timestampMs
            dcBaseline = meanRed / (meanGreen + 1f)
            smoothSignal = 0f
            prevSignal = 0f
            prevPrevSignal = 0f
        }

        val ratio = meanRed / (meanGreen + 1f)
        // High-pass DC removal (~0.75 Hz cutoff at 30 FPS)
        dcBaseline += 0.14f * (ratio - dcBaseline)
        val ac = ratio - dcBaseline
        // Low-pass smoothing (~3.7 Hz cutoff at 30 FPS)
        smoothSignal += 0.42f * (ac - smoothSignal)
        signalVarEma += 0.08f * (smoothSignal * smoothSignal - signalVarEma)
        val std = sqrt(signalVarEma.coerceAtLeast(1e-8f))

        // Local peak detection on 3-point stencil (prevPrev < prev && prev >= curr)
        if (prevSignal > prevPrevSignal && prevSignal >= smoothSignal && prevSignal > std * 0.45f) {
            if (lastPeakMs > 0L) {
                val ibi = timestampMs - lastPeakMs
                if (ibi in MIN_IBI_MS..MAX_IBI_MS) {
                    val medianIbi = computeMedianIbi()
                    // Reject motion-artifact outliers (>35% sudden jump from rolling median)
                    val isPlausible = medianIbi <= 0L || abs(ibi - medianIbi) <= medianIbi * 0.35
                    if (isPlausible) {
                        ibiRing[ibiHead] = ibi
                        ibiHead = (ibiHead + 1) % IBI_BUF_SIZE
                        if (ibiCount < IBI_BUF_SIZE) ibiCount++

                        val cleanMedian = computeMedianIbi()
                        if (cleanMedian > 0L) {
                            latestBpm = (60_000.0 / cleanMedian.toDouble()).roundToInt().coerceIn(45, 220)
                            if (latestBpm > peakBpm) peakBpm = latestBpm
                            if (initialStableBpm == 0 && ibiCount >= 4) {
                                initialStableBpm = latestBpm
                            }
                        }
                    }
                }
            }
            lastPeakMs = timestampMs
        }

        prevPrevSignal = prevSignal
        prevSignal = smoothSignal

        val elapsedSec = ((timestampMs - scanStartMs) / 1000L).toInt().coerceAtLeast(0)
        val quality = ((ibiCount.toFloat() / 6f).coerceIn(0f, 1f) * 100f).roundToInt()
        val msg = when {
            ibiCount < 4 -> "Hold still... locking optical pulse ($elapsedSec s)"
            elapsedSec < 60 -> "Pulse locked: $latestBpm BPM • Keep steady for 60s HRR ($elapsedSec/60s)"
            else -> "60s Recovery Complete! HRR-60 Drop: ${computeHrr60()} BPM"
        }

        val normWave = (smoothSignal / (std * 2.4f)).coerceIn(-1f, 1f)
        return HeartRateScanSnapshot(
            currentBpm = latestBpm,
            peakBpm = peakBpm,
            hrr60DropBpm = computeHrr60(),
            readinessScore = computeReadinessScore(),
            signalQualityPct = quality,
            elapsedSec = elapsedSec,
            isFingerDetected = true,
            latestWaveSample = normWave,
            statusMessage = msg
        )
    }

    private fun computeHrr60(): Int {
        if (initialStableBpm <= 0 || latestBpm <= 0) return 0
        return (peakBpm.coerceAtLeast(initialStableBpm) - latestBpm).coerceAtLeast(0)
    }

    private fun computeReadinessScore(): Int {
        if (ibiCount < 5) return 0
        var sumSqDiff = 0.0
        var pairs = 0
        for (i in 1 until ibiCount) {
            val prevIdx = (ibiHead - i - 1 + IBI_BUF_SIZE) % IBI_BUF_SIZE
            val currIdx = (ibiHead - i + IBI_BUF_SIZE) % IBI_BUF_SIZE
            val diff = (ibiRing[currIdx] - ibiRing[prevIdx]).toDouble()
            sumSqDiff += diff * diff
            pairs++
        }
        if (pairs == 0) return 0
        val rmssdMs = sqrt(sumSqDiff / pairs)
        // Map typical athletic RMSSD (15..95 ms) into 0..100 readiness score
        return ((rmssdMs - 10.0) / 80.0 * 100.0).roundToInt().coerceIn(20, 100)
    }

    private fun computeMedianIbi(): Long {
        if (ibiCount == 0) return 0L
        val temp = LongArray(ibiCount)
        for (i in 0 until ibiCount) temp[i] = ibiRing[i]
        temp.sort()
        return temp[ibiCount / 2]
    }
}
