package com.jumpropeai.ai

import com.jumpropeai.audio.AcousticBand
import com.jumpropeai.audio.FluxSample
import com.jumpropeai.audio.StftAudioEngine
import kotlin.math.abs
import kotlin.math.roundToInt

enum class JumpStyle(val label: String) {
    REGULAR("Regular"),
    BOXER_STEP("Boxer Step"),
    POWER("Power / High")
}

data class AcousticFingerprint(
    val band: AcousticBand = AcousticBand.WIDE_HIGH,
    val lagMs: Long = 80L,
    val isCalibrated: Boolean = false,
    val currentSnr: Float = 0f,
    val audioWeight: Float = 0f
) {
    fun shortBadge(): String = if (isCalibrated) {
        "${band.label} • ${lagMs}ms"
    } else {
        "Kalibrasi suara..."
    }
}

data class FusionDecision(
    val acceptedJumps: Int,
    val pllBridgedJumps: Int,
    val tripDetected: Boolean,
    val jumpStyle: JumpStyle,
    val fingerprint: AcousticFingerprint,
    val recommendEcoLowFps: Boolean
)

/**
 * Fusion v6 Master Engine with Continuous Self-Learning Acoustic Fingerprint:
 *
 * 1. Continuous Self-Learning:
 *    - Initial calibration after 3 confirmed jumps scans 3 sub-bands (Mid / High1 / High2)
 *      to find the highest-SNR rope-whip frequency band & exact physical apex-to-slap lag.
 *    - Continuously re-evaluates every 10 jumps with EMA lag smoothing so switching ropes,
 *      changing floor surfaces, or sudden background music adapts automatically!
 * 2. SNR-Gated Adaptive Bayesian Scoring:
 *    - Clean rope sound (SNR >= 5.5x) -> full Vision + Audio + Rhythm verification.
 *    - Music / noisy / mat (SNR <= 3.5x) -> smoothly drops audio weight to 0 (100% Vision+Rhythm).
 * 3. Phase-Locked Loop (PLL) Occlusion Bridging & Trip Detection.
 * 4. Full-Body Style Classification (Regular / Boxer Step / Power) & Dynamic-FPS Eco hint.
 */
class FusionEngine {

    private var activeBand: AcousticBand = AcousticBand.WIDE_HIGH
    private var learnedLagMs: Float = 80f
    private var isCalibrated: Boolean = false
    private var lastSnr: Float = 0f
    private var lastAudioWeight: Float = 0f

    private val acceptedPeakTimes = ArrayDeque<Long>()
    private val intervals = ArrayDeque<Long>()
    private var lastAcceptedTimeMs: Long? = null
    private var currentStreakInEngine = 0
    private var totalAcceptedSinceLastAdapt = 0

    var totalTrips: Int = 0
        private set
    var totalPllBridged: Int = 0
        private set
    var latestStyle: JumpStyle = JumpStyle.REGULAR
        private set

    fun reset() {
        activeBand = AcousticBand.WIDE_HIGH
        learnedLagMs = 80f
        isCalibrated = false
        lastSnr = 0f
        lastAudioWeight = 0f
        acceptedPeakTimes.clear()
        intervals.clear()
        lastAcceptedTimeMs = null
        currentStreakInEngine = 0
        totalAcceptedSinceLastAdapt = 0
        totalTrips = 0
        totalPllBridged = 0
        latestStyle = JumpStyle.REGULAR
    }

    fun currentFingerprint(): AcousticFingerprint = AcousticFingerprint(
        band = activeBand,
        lagMs = learnedLagMs.roundToInt().toLong(),
        isCalibrated = isCalibrated,
        currentSnr = lastSnr,
        audioWeight = lastAudioWeight
    )

    fun evaluateCandidate(
        candidate: JumpCandidate,
        audioHistory: List<FluxSample>,
        useAudioFusion: Boolean
    ): FusionDecision {
        val pt = candidate.peakTimeMs
        val amp = candidate.amplitude
        val hasAudio = useAudioFusion && audioHistory.size >= 20

        // 1. Continuous Self-Learning: initial calibration at 3 jumps, then re-adapt every 10 jumps
        if (hasAudio && acceptedPeakTimes.size >= 3 && (!isCalibrated || totalAcceptedSinceLastAdapt >= 10)) {
            adaptAcousticFingerprint(audioHistory)
            totalAcceptedSinceLastAdapt = 0
        }

        // 2. PLL Occlusion Gap Bridging (strict >= 1.90x tempo prevents false bridging on slow Double-Unders!)
        var pllBridged = 0
        var prevT = lastAcceptedTimeMs
        if (hasAudio && intervals.size >= 3 && prevT != null) {
            val tempo = StftAudioEngine.medianLong(intervals.toList())
            val gap = pt - prevT
            if (gap in (tempo * 1.90).toLong()..(tempo * 3.6).toLong()) {
                var predT = prevT + tempo
                val minMargin = maxOf(280L, (tempo * 0.65).toLong())
                while (predT <= pt - minMargin) {
                    if (hasStrongAudioHitAt(audioHistory, predT + learnedLagMs.toLong(), halfWindowMs = 70L)) {
                        pllBridged++
                        totalPllBridged++
                        currentStreakInEngine++
                        prevT = predT
                        lastAcceptedTimeMs = predT
                    }
                    predT += tempo
                }
            }
        }

        val dt = prevT?.let { pt - it } ?: Long.MAX_VALUE

        // 3. Trip detection when a rhythmic streak of >= 4 breaks abruptly
        var tripDetected = false
        if (intervals.size >= 3 && currentStreakInEngine >= 4) {
            val tempo = StftAudioEngine.medianLong(intervals.toList())
            if (dt > (tempo * 1.85).toLong()) {
                tripDetected = true
                totalTrips++
                currentStreakInEngine = 0
            }
        }

        // 4. Vision Kinematic Score (penalize extreme >0.75 isolated walks unless on-rhythm)
        val isIsolated = dt > 1500L
        val vScore = when {
            isIsolated && amp > 0.72f -> 0.45f
            amp in 0.065f..0.85f -> 0.86f
            amp in 0.055f..0.065f || amp in 0.85f..1.05f -> 0.65f
            else -> 0.42f
        }

        // 5. Rhythm Tempo Consistency Score
        val onRhythm = if (intervals.size >= 2) {
            val tempo = StftAudioEngine.medianLong(intervals.toList())
            dt in (tempo * 0.62).toLong()..(tempo * 1.40).toLong()
        } else {
            dt in 260L..850L
        }
        val rScore = if (onRhythm) 1f else 0f

        // 6. Adaptive SNR-Gated Matched Filter using Self-Learned Band & Lag
        var wAudio = 0f
        var aScore = 0.5f
        if (hasAudio) {
            val bgSamples = audioHistory.filter { it.timestampMs in (pt - 3000L)..pt }
            if (bgSamples.size >= 15) {
                val bgVals = bgSamples.map { it.valueFor(activeBand) }
                val bgMed = StftAudioEngine.median(bgVals) + 1e-5f
                val bgP98 = StftAudioEngine.percentile(bgVals, 98f)
                val rollingSnr = bgP98 / bgMed
                lastSnr = rollingSnr
                wAudio = ((rollingSnr - 3.5f) / 2.0f).coerceIn(0f, 1f)
                lastAudioWeight = wAudio

                val lagLong = learnedLagMs.roundToInt().toLong()
                val winLo = pt + (lagLong - 80L).coerceAtLeast(0L)
                val winHi = pt + lagLong + 110L
                val impactVals = audioHistory
                    .filter { it.timestampMs in winLo..winHi }
                    .map { it.valueFor(activeBand) }

                if (impactVals.isNotEmpty()) {
                    val impPeak = impactVals.max()
                    val shortBg = bgSamples.takeLast(150).map { it.valueFor(activeBand) }
                    val sMed = StftAudioEngine.median(shortBg) + 1e-5f
                    val sMad = StftAudioEngine.median(shortBg.map { abs(it - sMed) }) + 1e-5f
                    val z = (impPeak - sMed) / sMad
                    val ratio = impPeak / sMed
                    aScore = when {
                        z >= 5.0f && ratio >= 2.2f && impPeak >= 0.005f -> 1.0f
                        z >= 2.5f && ratio >= 1.6f && impPeak >= 0.004f -> 0.8f
                        else -> 0.0f
                    }
                }
            }
        } else {
            lastSnr = 0f
            lastAudioWeight = 0f
        }

        // 7. Adaptive Bayesian Decision
        val finalScore = if (wAudio < 0.25f) {
            vScore + 0.15f * rScore
        } else {
            (1f - 0.45f * wAudio) * vScore + (0.45f * wAudio) * aScore + 0.18f * rScore
        }

        var acceptedCount = pllBridged
        if (finalScore >= 0.68f) {
            acceptedCount++
            acceptedPeakTimes.addLast(pt)
            while (acceptedPeakTimes.size > 8) acceptedPeakTimes.removeFirst()

            if (dt in 200L..950L) {
                intervals.addLast(dt)
                while (intervals.size > 6) intervals.removeFirst()
            }
            lastAcceptedTimeMs = pt
            currentStreakInEngine++
            totalAcceptedSinceLastAdapt++

            latestStyle = when {
                amp >= 0.38f -> JumpStyle.POWER
                candidate.ankleAlternation >= 0.22f -> JumpStyle.BOXER_STEP
                else -> JumpStyle.REGULAR
            }
        }

        // Dynamic-FPS Eco hint: safe to drop camera to ~15 FPS when PLL locked & Audio SNR >= 5.5x
        val recommendEcoLowFps = hasAudio && isCalibrated && intervals.size >= 3 && lastSnr >= 5.5f

        return FusionDecision(
            acceptedJumps = acceptedCount,
            pllBridgedJumps = pllBridged,
            tripDetected = tripDetected,
            jumpStyle = latestStyle,
            fingerprint = currentFingerprint(),
            recommendEcoLowFps = recommendEcoLowFps
        )
    }

    private fun adaptAcousticFingerprint(audioHistory: List<FluxSample>) {
        val recentPeaks = acceptedPeakTimes.takeLast(5)
        if (recentPeaks.size < 3) return

        var bestBand = activeBand
        var bestScore = -1f
        var bestLag = learnedLagMs

        for (band in listOf(AcousticBand.MID, AcousticBand.HIGH_1, AcousticBand.HIGH_2)) {
            val zList = mutableListOf<Float>()
            val lagList = mutableListOf<Float>()
            for (pt in recentPeaks) {
                val bg = audioHistory
                    .filter { it.timestampMs in (pt - 1500L)..pt }
                    .map { it.valueFor(band) }
                val seg = audioHistory.filter { it.timestampMs in pt..(pt + 220L) }
                if (bg.size < 10 || seg.isEmpty()) continue

                val peakSample = seg.maxByOrNull { it.valueFor(band) } ?: continue
                val med = StftAudioEngine.median(bg) + 1e-5f
                val mad = StftAudioEngine.median(bg.map { abs(it - med) }) + 1e-5f
                val z = (peakSample.valueFor(band) - med) / mad
                zList.add(z)
                lagList.add((peakSample.timestampMs - pt).toFloat().coerceIn(10f, 210f))
            }
            if (zList.isNotEmpty()) {
                val medZ = StftAudioEngine.median(zList)
                if (medZ > bestScore) {
                    bestScore = medZ
                    bestBand = band
                    bestLag = StftAudioEngine.median(lagList)
                }
            }
        }

        if (bestScore > 1.5f) {
            activeBand = bestBand
            // Smooth continuous EMA adaptation of lag after initial lock
            learnedLagMs = if (isCalibrated) {
                learnedLagMs * 0.65f + bestLag * 0.35f
            } else {
                bestLag
            }
            isCalibrated = true
        }
    }

    private fun hasStrongAudioHitAt(
        audioHistory: List<FluxSample>,
        targetMs: Long,
        halfWindowMs: Long
    ): Boolean {
        val bg = audioHistory
            .filter { it.timestampMs in (targetMs - 1500L)..(targetMs - 100L) }
            .map { it.valueFor(activeBand) }
        val seg = audioHistory
            .filter { it.timestampMs in (targetMs - halfWindowMs)..(targetMs + halfWindowMs) }
            .map { it.valueFor(activeBand) }
        if (bg.size < 10 || seg.isEmpty()) return false
        val med = StftAudioEngine.median(bg) + 1e-5f
        val mad = StftAudioEngine.median(bg.map { abs(it - med) }) + 1e-5f
        val pk = seg.max()
        return (pk - med) / mad >= 5.5f && (pk / med) >= 2.2f
    }
}
