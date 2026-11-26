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
        "Calibrating audio..."
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
 * Fusion v11 Master Engine with Continuous Self-Learning Acoustic Fingerprint & Speed-Sprint Audio Hybrid:
 *
 * 1. 1-Jump Pending Confirmation after long pauses (>1250ms): eliminates isolated walking steps
 *    and static/outro glitches while keeping active streaks 100% real-time.
 * 2. Post-Streak Outro Guard: ignores weak post-gap ripples after strong jump streaks.
 * 3. Rhythmic Soft-Candidate Gating: rescues low-amplitude fast skipping hops strictly on-rhythm
 *    without letting flat-footed side swings through.
 * 4. Vision-Gated 100 Hz STFT Audio Speed-Sprint Counter: resolves 270-350+ RPM bent-over competition
 *    speed sprints uniformly across 30, 20, 15, and 10 FPS phones without camera Nyquist aliasing.
 * 5. Continuous Self-Learning 3-Band STFT Audio + Nyquist-Shannon Aliasing & Drop-Aware PLL:
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
    private var lastSprintAudioTimeMs: Long = -1L

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
        lastSprintAudioTimeMs = -1L
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

        // 0. Vision-Gated 100 Hz STFT Audio Speed-Sprint Counter (O(log N) binary search, zero-GC):
        // During bent-over competition speed sprints (>270 RPM), 100 Hz STFT audio resolves every
        // rope+foot slap uniformly across Flagship (30 FPS) to Low-End (10 FPS) with zero Nyquist aliasing.
        if (candidate.isSpeedSprint && hasAudio) {
            if (lastSprintAudioTimeMs < 0L || (pt - lastSprintAudioTimeMs) > 1500L) {
                var baseStart = if (candidate.sprintStartMs > 0L) candidate.sprintStartMs else (pt - 1200L)
                lastAcceptedTimeMs?.let { lastAcc ->
                    baseStart = maxOf(baseStart, lastAcc + 120L)
                }
                lastSprintAudioTimeMs = maxOf(baseStart, pt - 1800L)
            }
            val bgStartIdx = findIndexAtOrAfter(audioHistory, maxOf(0L, pt - 3000L))
            val bgEndIdx = findIndexAfter(audioHistory, pt + 100L)
            val bgCount = bgEndIdx - bgStartIdx
            var sprintAccepted = 0
            if (bgCount >= 30) {
                val (med, mad) = computeMedianAndMad(audioHistory, bgStartIdx, bgEndIdx, AcousticBand.MID)
                val scanFrom = lastSprintAudioTimeMs + 10L
                val scanStart = maxOf(1, findIndexAtOrAfter(audioHistory, scanFrom))
                val scanEnd = minOf(audioHistory.size - 1, findIndexAfter(audioHistory, pt))
                for (k in scanStart until scanEnd) {
                    val tk = audioHistory[k].timestampMs
                    val v0 = audioHistory[k].valueFor(AcousticBand.MID)
                    if (v0 >= audioHistory[k - 1].valueFor(AcousticBand.MID) &&
                        v0 > audioHistory[k + 1].valueFor(AcousticBand.MID)
                    ) {
                        if ((v0 - med) / mad >= 1.10f && (tk - lastSprintAudioTimeMs) >= 144L) {
                            sprintAccepted++
                            currentStreakInEngine++
                            lastSprintAudioTimeMs = tk
                            lastAcceptedTimeMs = tk
                        }
                    }
                }
            }
            pendingJump = null
            latestStyle = JumpStyle.BOXER_STEP
            return buildDecision(sprintAccepted, pllBridged = 0, tripDetected = false, hasAudio = true)
        }

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
            if (gapFromPend in 170L..1050L) {
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
            if (dtSoft !in (medTempo * 0.70).toLong()..(medTempo * 1.32).toLong()) {
                return buildDecision(acceptedCount, pllBridged, tripDetected, hasAudio)
            }
        }

        // 5. Nyquist-Shannon Aliasing, Drop-Aware & Single-Beat Dance/Trick Audio PLL Bridge
        val isExtremeLowFps = candidate.avgFrameDtMs >= 82f
        val hadDrop = candidate.droppedFramesSinceLast >= 1
        if (intervals.size >= 3 && currentStreakInEngine >= 4) {
            val medTempo = StftAudioEngine.medianLong(intervals.toList())
            val gap = pt - prevTime
            val cv = computeIntervalCv(intervals.toList(), medTempo)
            val isTightDoubleBeat = medTempo in 220L..540L && cv <= 0.22f &&
                gap in (medTempo * 1.76).toLong()..(medTempo * 2.26).toLong()
            if (isExtremeLowFps || hadDrop || (hasAudio && isTightDoubleBeat)) {
                val maxMult = if (isExtremeLowFps) 6.0 else 2.5
                if (medTempo <= 540L && gap in (medTempo * 1.65).toLong()..(medTempo * maxMult).toLong()) {
                    var predT = prevTime + medTempo
                    var bridgedHere = 0
                    val minMargin = maxOf(175L, (medTempo * 0.55).toLong())
                    while (predT <= pt - minMargin) {
                        var ok = hasAudio && hasStrongAudioHitAt(
                            audioHistory = audioHistory,
                            targetTimeMs = predT + learnedLagMs.toLong(),
                            zThr = if (isTightDoubleBeat) 1.55f else 2.1f,
                            ratioThr = if (isTightDoubleBeat) 1.30f else 1.45f
                        )
                        if (!ok && isExtremeLowFps && medTempo <= 410L && cv <= 0.24f &&
                            bridgedHere < 3 && gap <= (medTempo * 3.35).toLong()
                        ) {
                            ok = true
                        }
                        if (!ok && hadDrop && bridgedHere == 0 && gap in (medTempo * 1.72).toLong()..(medTempo * 2.30).toLong()) {
                            ok = true
                        }
                        if (ok && (isExtremeLowFps || bridgedHere == 0)) {
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

        if (dt != null && dt in 170L..950L) {
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

    private var scratchBuf = FloatArray(512)

    private fun hasStrongAudioHitAt(
        audioHistory: List<FluxSample>,
        targetTimeMs: Long,
        zThr: Float = 2.8f,
        ratioThr: Float = 1.65f
    ): Boolean {
        val bgStart = findIndexAtOrAfter(audioHistory, targetTimeMs - 2500L)
        val bgEnd = findIndexAfter(audioHistory, targetTimeMs)
        if (bgEnd - bgStart < 15) return false

        val (med, mad) = computeMedianAndMad(audioHistory, bgStart, bgEnd, activeBand)

        val pStart = findIndexAtOrAfter(audioHistory, targetTimeMs - 75L)
        val pEnd = findIndexAfter(audioHistory, targetTimeMs + 95L)
        var peakVal = 0f
        for (i in pStart until pEnd) {
            val v = audioHistory[i].valueFor(activeBand)
            if (v > peakVal) peakVal = v
        }
        val z = (peakVal - med) / mad
        return z >= zThr && (peakVal / med) >= ratioThr && peakVal >= 0.004f
    }

    private fun computeMedianAndMad(
        audioHistory: List<FluxSample>,
        startIdx: Int,
        endIdx: Int,
        band: AcousticBand
    ): Pair<Float, Float> {
        val n = endIdx - startIdx
        if (n <= 0) return 1e-5f to 1e-5f
        if (scratchBuf.size < n) scratchBuf = FloatArray(n * 2)
        for (i in 0 until n) {
            scratchBuf[i] = audioHistory[startIdx + i].valueFor(band)
        }
        java.util.Arrays.sort(scratchBuf, 0, n)
        val mid = n / 2
        val rawMed = if (n % 2 == 1) scratchBuf[mid] else 0.5f * (scratchBuf[mid - 1] + scratchBuf[mid])
        val med = rawMed + 1e-5f
        for (i in 0 until n) {
            scratchBuf[i] = abs(audioHistory[startIdx + i].valueFor(band) - med)
        }
        java.util.Arrays.sort(scratchBuf, 0, n)
        val rawMad = if (n % 2 == 1) scratchBuf[mid] else 0.5f * (scratchBuf[mid - 1] + scratchBuf[mid])
        return med to (rawMad + 1e-5f)
    }

    private fun findIndexAtOrAfter(list: List<FluxSample>, targetMs: Long): Int {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].timestampMs < targetMs) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun findIndexAfter(list: List<FluxSample>, targetMs: Long): Int {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].timestampMs <= targetMs) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
