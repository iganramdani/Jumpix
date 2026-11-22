package com.jumpropeai.ai

import com.jumpropeai.audio.AcousticBand
import com.jumpropeai.audio.FluxSample
import com.jumpropeai.audio.StftAudioEngine
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
 * Fusion v10 Master Engine with Continuous Self-Learning Acoustic Fingerprint:
 *
 * 1. 1-Jump Pending Confirmation after long pauses (>1250ms): eliminates isolated walking steps
 *    and static/outro glitches while keeping active streaks 100% real-time.
 * 2. Post-Streak Outro Guard: ignores weak post-gap ripples after strong jump streaks.
 * 3. Rhythmic Soft-Candidate Gating: rescues low-amplitude fast skipping hops strictly on-rhythm
 *    without letting flat-footed side swings through.
 * 4. Continuous Self-Learning 3-Band STFT Audio + Nyquist-Shannon Aliasing & Drop-Aware PLL:
 *    recovers missed fast jumps on Low-End 10 FPS smartphones without false-bridging pauses.
 */
class FusionEngine {

    private data class PendingJump(
        val peakTimeMs: Long,
        val amplitude: Float,
        val ankleAlternation: Float
    )

    private var activeBand: AcousticBand = AcousticBand.WIDE_HIGH
    private var learnedLagMs: Float = 80f
    private var isCalibrated: Boolean = false
    private var lastSnr: Float = 0f
    private var lastAudioWeight: Float = 0f

    private val acceptedPeakTimes = ArrayDeque<Long>()
    private val acceptedAmplitudes = ArrayDeque<Float>()
    private val intervals = ArrayDeque<Long>()
    private var lastAcceptedTimeMs: Long? = null
    private var pendingJump: PendingJump? = null
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
        acceptedAmplitudes.clear()
        intervals.clear()
        lastAcceptedTimeMs = null
        pendingJump = null
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
        val prom = candidate.prominence
        val hasAudio = useAudioFusion && audioHistory.size >= 20

        // 1. Continuous Self-Learning: initial calibration at 3 jumps, then re-adapt every 10 jumps
        if (hasAudio && acceptedPeakTimes.size >= 3 && (!isCalibrated || totalAcceptedSinceLastAdapt >= 10)) {
            adaptAcousticFingerprint(audioHistory)
            totalAcceptedSinceLastAdapt = 0
        }

        var acceptedCount = 0
        var pllBridged = 0
        var tripDetected = false

        // 2. Confirm or expire pending post-gap jump
        pendingJump?.let { pend ->
            val gapFromPend = pt - pend.peakTimeMs
            if (gapFromPend in 190L..1000L) {
                acceptedCount++
                recordAcceptedInternal(pend.peakTimeMs, pend.amplitude, pend.ankleAlternation, dt = null)
                currentStreakInEngine = 1
                pendingJump = null
            } else {
                pendingJump = null
            }
        }

        var prevT = lastAcceptedTimeMs

        // 3. Starting a new streak (first jump or after >1250ms pause): hold in pending until 2nd rhythmic jump confirms!
        if (prevT == null || (pt - prevT) > 1250L) {
            if (prevT != null && currentStreakInEngine >= 4) {
                tripDetected = true
                totalTrips++
            }
            currentStreakInEngine = 0

            if (!candidate.isSoft && amp <= 0.72f) {
                // Post-streak outro guard: ignore weak post-gap ripples after >= 8 strong jumps
                val isOutroRipple = acceptedAmplitudes.size >= 8 &&
                    StftAudioEngine.median(acceptedAmplitudes.toList()) >= 0.22f &&
                    prom < 0.16f
                if (!isOutroRipple) {
                    pendingJump = PendingJump(pt, amp, candidate.ankleAlternation)
                }
            }
            return buildDecision(acceptedCount, pllBridged, tripDetected, hasAudio)
        }

        var prevTime: Long = prevT

        // 4. Soft candidate gating: accept ONLY on-rhythm during an established streak >= 3
        if (candidate.isSoft) {
            if (currentStreakInEngine < 3 || intervals.size < 3) {
                return buildDecision(acceptedCount, pllBridged, tripDetected, hasAudio)
            }
            val medTempo = StftAudioEngine.medianLong(intervals.toList())
            val dtSoft = pt - prevTime
            if (dtSoft !in (medTempo * 0.72).toLong()..(medTempo * 1.28).toLong()) {
                return buildDecision(acceptedCount, pllBridged, tripDetected, hasAudio)
            }
        }

        // 5. Nyquist-Shannon Aliasing & Drop-Aware Audio PLL Bridge (Low-End 10 FPS or actual dropped frames)
        val isExtremeLowFps = candidate.avgFrameDtMs >= 82f
        val hadDrop = candidate.droppedFramesSinceLast >= 1
        if ((isExtremeLowFps || hadDrop) && intervals.size >= 3 && currentStreakInEngine >= 4) {
            val medTempo = StftAudioEngine.medianLong(intervals.toList())
            val gap = pt - prevTime
            val maxMult = if (isExtremeLowFps) 6.0 else 2.5
            if (medTempo <= 440L && gap in (medTempo * 1.65).toLong()..(medTempo * maxMult).toLong()) {
                val cv = computeIntervalCv(intervals.toList(), medTempo)
                var predT = prevTime + medTempo
                var bridgedHere = 0
                val minMargin = maxOf(195L, (medTempo * 0.55).toLong())
                while (predT <= pt - minMargin) {
                    var ok = hasAudio && hasStrongAudioHitAt(
                        audioHistory = audioHistory,
                        targetTimeMs = predT + learnedLagMs.toLong(),
                        zThr = 2.8f,
                        ratioThr = 1.65f
                    )
                    if (!ok && isExtremeLowFps && medTempo <= 410L && cv <= 0.25f &&
                        bridgedHere < 2 && gap <= (medTempo * 3.35).toLong()
                    ) {
                        ok = true
                    }
                    if (!ok && hadDrop && bridgedHere == 0 && gap in (medTempo * 1.72).toLong()..(medTempo * 2.30).toLong()) {
                        ok = true
                    }
                    if (ok) {
                        acceptedCount++
                        pllBridged++
                        bridgedHere++
                        totalPllBridged++
                        currentStreakInEngine++
                        prevTime = predT
                        lastAcceptedTimeMs = predT
                    }
                    predT += medTempo
                }
            }
        }

        // 6. Accept confirmed jump & update rhythm/style state
        val dt = pt - prevTime
        if (intervals.size >= 3 && currentStreakInEngine >= 4) {
            val tempo = StftAudioEngine.medianLong(intervals.toList())
            if (dt > (tempo * 1.85).toLong()) {
                tripDetected = true
                totalTrips++
                currentStreakInEngine = 0
            }
        }

        acceptedCount++
        recordAcceptedInternal(pt, amp, candidate.ankleAlternation, dt = dt)
        currentStreakInEngine++

        return buildDecision(acceptedCount, pllBridged, tripDetected, hasAudio)
    }

    private fun recordAcceptedInternal(
        peakTimeMs: Long,
        amplitude: Float,
        ankleAlternation: Float,
        dt: Long?
    ) {
        acceptedPeakTimes.addLast(peakTimeMs)
        while (acceptedPeakTimes.size > 8) acceptedPeakTimes.removeFirst()

        acceptedAmplitudes.addLast(amplitude)
        while (acceptedAmplitudes.size > 8) acceptedAmplitudes.removeFirst()

        if (dt != null && dt in 190L..950L) {
            intervals.addLast(dt)
            while (intervals.size > 6) intervals.removeFirst()
        }

        lastAcceptedTimeMs = peakTimeMs
        totalAcceptedSinceLastAdapt++

        latestStyle = when {
            amplitude >= 0.38f -> JumpStyle.POWER
            ankleAlternation >= 0.22f -> JumpStyle.BOXER_STEP
            else -> JumpStyle.REGULAR
        }
    }

    private fun buildDecision(
        acceptedCount: Int,
        pllBridged: Int,
        tripDetected: Boolean,
        hasAudio: Boolean
    ): FusionDecision {
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

    private fun computeIntervalCv(list: List<Long>, median: Long): Float {
        if (list.size < 2 || median <= 0L) return 1f
        val mean = list.average()
        var sumSq = 0.0
        for (v in list) {
            val d = v - mean
            sumSq += d * d
        }
        val std = sqrt(sumSq / list.size)
        return (std / median.toDouble()).toFloat()
    }

    private fun adaptAcousticFingerprint(audioHistory: List<FluxSample>) {
        val recentPeaks = acceptedPeakTimes.takeLast(5)
        if (recentPeaks.size < 3) return

        var bestBand = activeBand
        var bestLag = learnedLagMs
        var bestScore = -1f

        val candidateBands = listOf(AcousticBand.MID, AcousticBand.HIGH_1, AcousticBand.HIGH_2)
        val firstPeak = recentPeaks.first()
        val lastPeak = recentPeaks.last()

        val bgWindow = audioHistory.filter { it.timestampMs in (firstPeak - 1000L)..(lastPeak + 200L) }
        if (bgWindow.size < 15) return

        for (band in candidateBands) {
            val bgMed = StftAudioEngine.median(bgWindow.map { it.valueFor(band) }) + 1e-5f
            var lag = 20L
            while (lag <= 150L) {
                var hitSum = 0f
                var hitCount = 0
                for (pt in recentPeaks) {
                    val target = pt + lag
                    var peakVal = 0f
                    for (s in bgWindow) {
                        if (s.timestampMs in (target - 15L)..(target + 15L)) {
                            val v = s.valueFor(band)
                            if (v > peakVal) peakVal = v
                        }
                    }
                    hitSum += peakVal
                    hitCount++
                }
                val score = if (hitCount > 0) (hitSum / hitCount) / bgMed else 0f
                if (score > bestScore) {
                    bestScore = score
                    bestBand = band
                    bestLag = lag.toFloat()
                }
                lag += 10L
            }
        }

        if (bestScore > 1.5f) {
            activeBand = bestBand
            learnedLagMs = if (isCalibrated) {
                learnedLagMs * 0.65f + bestLag * 0.35f
            } else {
                bestLag
            }
            lastSnr = bestScore
            lastAudioWeight = ((bestScore - 3.5f) / 2.0f).coerceIn(0f, 1f)
            isCalibrated = true
        }
    }

    private fun hasStrongAudioHitAt(
        audioHistory: List<FluxSample>,
        targetTimeMs: Long,
        zThr: Float = 2.8f,
        ratioThr: Float = 1.65f
    ): Boolean {
        val bg = audioHistory.filter { it.timestampMs in (targetTimeMs - 2500L)..targetTimeMs }
        if (bg.size < 15) return false
        val bgVals = bg.map { it.valueFor(activeBand) }
        val med = StftAudioEngine.median(bgVals) + 1e-5f
        val mad = StftAudioEngine.median(bgVals.map { abs(it - med) }) + 1e-5f

        var peakVal = 0f
        for (s in audioHistory) {
            if (s.timestampMs in (targetTimeMs - 75L)..(targetTimeMs + 95L)) {
                val v = s.valueFor(activeBand)
                if (v > peakVal) peakVal = v
            }
        }
        val z = (peakVal - med) / mad
        return z >= zThr && (peakVal / med) >= ratioThr && peakVal >= 0.004f
    }
}
