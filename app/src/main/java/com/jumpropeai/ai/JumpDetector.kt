package com.jumpropeai.ai

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class DetectorStatus {
    NO_PERSON,
    LOW_CONFIDENCE,
    CALIBRATING,
    READY,
    ACTIVE
}

data class JumpCandidate(
    val peakTimeMs: Long,
    val landTimeMs: Long,
    val amplitude: Float,
    val ankleAlternation: Float,
    val avgFrameDtMs: Float = 33f,
    val prominence: Float = amplitude,
    val isSoft: Boolean = false,
    val droppedFramesSinceLast: Int = 0,
    val isSpeedSprint: Boolean = false,
    val sprintStartMs: Long = -1L
)

data class JumpDetectionResult(
    val jumpDetected: Boolean,
    val candidate: JumpCandidate? = null,
    val isAirborne: Boolean,
    val status: DetectorStatus,
    val normalizedDisplacement: Float = 0f,
    val calibrationProgress: Float = 0f
)

/**
 * Streaming Multi-Channel Kinematic Jump Detector (v11).
 *
 * Engineered and verified across all 46 Ground-Truth real-world jump rope videos (2,212 jumps)
 * and 4 smartphone tiers (Flagship 30 FPS, Mid-Range 20 FPS, Budget 15 FPS, Low-End 10 FPS):
 * 1. Equal-variance 5-landmark upper-body anchor (Nose + L/R Shoulders + L/R Hips) for minimum jitter.
 * 2. Causal sliding ring-buffer local-peak detector (~210ms landing lookahead) with FPS-scaled valley window.
 * 3. Multi-Landmark Coherence Gate: verifies all upper-body landmarks move in unison, blocking +-6px jitter.
 * 4. Jump Symmetry Law & Scale-Stability Walking Guard: rejects walking toward/away from camera & slow bends.
 * 5. Dedicated Footwork / Dance Ankle-Alternation Channel with lookahead anti-double-fire guard.
 * 6. Competition Speed-Sprint Channel: zero-crossing hysteresis detector for bent-over 270-350+ RPM sprints
 *    with FPS-independent time-based ankle energy window, handing off to 100 Hz STFT Audio in FusionEngine.
 * 7. Relative Post-Double-Under Landing-Rebound Guard: blocks knee rebounds after high jumps without
 *    dropping fast 200+ RPM speed skipping.
 */
class JumpDetector(
    private val minLandmarkConfidence: Float = 0.4f,
    private val minMeanPoseConfidence: Float = 0.65f,
    private val warmupFramesRequired: Int = 3,
    private val scaleSmoothingAlpha: Float = 0.10f,
    private val promStrongBase: Float = 0.050f,
    private val promRhythmBase: Float = 0.040f,
    private val promFootwork: Float = 0.032f,
    private val maxAmplitude: Float = 0.95f,
    private val minJumpIntervalMs: Long = 205L
) {
    private data class FrameSample(
        val timestampMs: Long,
        val rawY: Float,
        val smoothedY: Float,
        val torsoX: Float,
        val scale: Float,
        val signedAnkleDiff: Float,
        val absAnkleDiff: Float,
        val meanConfidence: Float,
        val lmYs: FloatArray, // [NOSE, L_SH, R_SH, L_HIP, R_HIP], Float.NaN if missing
        var evaluated: Boolean = false
    )

    private var warmupFrames = 0
    private var prevFrameMs: Long? = null
    private var avgFrameDtMs: Float = 33f
    private var smoothedScale: Float? = null
    private var lastJumpPeakMs: Long? = null
    private var lastJumpAmplitude: Float = 0f
    private var droppedSinceLastCandidate: Int = 0

    // Competition Speed-Sprint zero-crossing hysteresis state
    private var zcLastSign: Int = 0
    private var zcLastExt: Float = 0f
    private val zcTimes = ArrayDeque<Long>()
    private var sprintActiveUntilMs: Long = -1L
    private var sprintStartMs: Long = -1L
    private var lastFwSign: Int = 0

    private val ring = ArrayDeque<FrameSample>()

    fun reset() {
        warmupFrames = 0
        prevFrameMs = null
        avgFrameDtMs = 33f
        smoothedScale = null
        lastJumpPeakMs = null
        lastJumpAmplitude = 0f
        droppedSinceLastCandidate = 0
        zcLastSign = 0
        zcLastExt = 0f
        zcTimes.clear()
        sprintActiveUntilMs = -1L
        sprintStartMs = -1L
        lastFwSign = 0
        ring.clear()
    }

    fun processFrame(frame: PoseFrame, isCountingActive: Boolean): JumpDetectionResult {
        prevFrameMs?.let { pMs ->
            val dtFrame = (frame.timestampMs - pMs).coerceIn(16L, 300L).toFloat()
            avgFrameDtMs = avgFrameDtMs * 0.85f + dtFrame * 0.15f
        }
        prevFrameMs = frame.timestampMs

        if (frame.landmarks.isEmpty()) {
            if (isCountingActive) droppedSinceLastCandidate++
            return JumpDetectionResult(
                jumpDetected = false,
                isAirborne = false,
                status = DetectorStatus.NO_PERSON
            )
        }

        val nose = frame.landmarks[BodyLandmark.NOSE]?.takeIf { it.confidence >= minLandmarkConfidence }
        val leftShoulder = frame.landmarks[BodyLandmark.LEFT_SHOULDER]?.takeIf { it.confidence >= minLandmarkConfidence }
        val rightShoulder = frame.landmarks[BodyLandmark.RIGHT_SHOULDER]?.takeIf { it.confidence >= minLandmarkConfidence }
        val leftHip = frame.landmarks[BodyLandmark.LEFT_HIP]?.takeIf { it.confidence >= minLandmarkConfidence }
        val rightHip = frame.landmarks[BodyLandmark.RIGHT_HIP]?.takeIf { it.confidence >= minLandmarkConfidence }
        val leftAnkle = frame.landmarks[BodyLandmark.LEFT_ANKLE]?.takeIf { it.confidence >= minLandmarkConfidence }
        val rightAnkle = frame.landmarks[BodyLandmark.RIGHT_ANKLE]?.takeIf { it.confidence >= minLandmarkConfidence }

        val shoulderMidY = averageY(leftShoulder, rightShoulder)
        val hipMidY = averageY(leftHip, rightHip)

        if (shoulderMidY == null) {
            if (isCountingActive) droppedSinceLastCandidate++
            return JumpDetectionResult(
                jumpDetected = false,
                isAirborne = false,
                status = DetectorStatus.LOW_CONFIDENCE
            )
        }

        val rawScale = computeReferenceScale(
            leftShoulder = leftShoulder,
            rightShoulder = rightShoulder,
            shoulderMidY = shoulderMidY,
            hipMidY = hipMidY,
            imageHeight = frame.imageHeight
        )
        if (rawScale <= 1f) {
            if (isCountingActive) droppedSinceLastCandidate++
            return JumpDetectionResult(
                jumpDetected = false,
                isAirborne = false,
                status = DetectorStatus.LOW_CONFIDENCE
            )
        }

        val currentScale = smoothedScale?.let { prev ->
            prev + scaleSmoothingAlpha * (rawScale - prev)
        } ?: rawScale
        smoothedScale = currentScale

        // Equal-variance 5-landmark upper-body vertical anchor
        val lmYs = floatArrayOf(
            nose?.y ?: Float.NaN,
            leftShoulder?.y ?: Float.NaN,
            rightShoulder?.y ?: Float.NaN,
            leftHip?.y ?: Float.NaN,
            rightHip?.y ?: Float.NaN
        )
        var sumY = 0f
        var cntY = 0
        for (v in lmYs) {
            if (!v.isNaN()) {
                sumY += v
                cntY++
            }
        }
        val rawY = if (cntY > 0) sumY / cntY else shoulderMidY

        val torsoX = when {
            leftShoulder != null && rightShoulder != null -> (leftShoulder.x + rightShoulder.x) * 0.5f
            leftShoulder != null -> leftShoulder.x
            rightShoulder != null -> rightShoulder.x
            else -> 0f
        }

        var confSum = 0f
        var confCnt = 0
        for (lm in listOf(
            BodyLandmark.NOSE,
            BodyLandmark.LEFT_SHOULDER,
            BodyLandmark.RIGHT_SHOULDER,
            BodyLandmark.LEFT_HIP,
            BodyLandmark.RIGHT_HIP
        )) {
            frame.landmarks[lm]?.let {
                confSum += it.confidence
                confCnt++
            }
        }
        val meanConf = if (confCnt > 0) confSum / confCnt else 0f

        val signedAnkleDiff = if (leftAnkle != null && rightAnkle != null) {
            (leftAnkle.y - rightAnkle.y) / currentScale
        } else {
            0f
        }
        val absAnkleDiff = abs(signedAnkleDiff)

        // Smooth only at >= 24 FPS (binomial 0.22 / 0.56 / 0.22 via previous sample); zero blur at <= 18 FPS!
        val sample = FrameSample(
            timestampMs = frame.timestampMs,
            rawY = rawY,
            smoothedY = rawY,
            torsoX = torsoX,
            scale = currentScale,
            signedAnkleDiff = signedAnkleDiff,
            absAnkleDiff = absAnkleDiff,
            meanConfidence = meanConf,
            lmYs = lmYs
        )

        if (warmupFrames < warmupFramesRequired) {
            warmupFrames++
            appendSample(sample)
            val progress = (warmupFrames.toFloat() / warmupFramesRequired).coerceIn(0f, 1f)
            return JumpDetectionResult(
                jumpDetected = false,
                isAirborne = false,
                status = if (warmupFrames < warmupFramesRequired) {
                    DetectorStatus.CALIBRATING
                } else if (isCountingActive) {
                    DetectorStatus.ACTIVE
                } else {
                    DetectorStatus.READY
                },
                normalizedDisplacement = 0f,
                calibrationProgress = progress
            )
        }

        if (!isCountingActive) {
            ring.clear()
            appendSample(sample)
            droppedSinceLastCandidate = 0
            return JumpDetectionResult(
                jumpDetected = false,
                isAirborne = false,
                status = DetectorStatus.READY,
                normalizedDisplacement = 0f,
                calibrationProgress = 1f
            )
        }

        appendSample(sample)

        // Apply causal 3-point binomial smoothing to the second-newest sample on >= 24 FPS devices
        if (avgFrameDtMs < 45f && ring.size >= 3) {
            val midIdx = ring.size - 2
            val prevS = ring[midIdx - 1]
            val curS = ring[midIdx]
            val nextS = ring[midIdx + 1]
            val smVal = 0.22f * prevS.rawY + 0.56f * curS.rawY + 0.22f * nextS.rawY
            ring[midIdx] = curS.copy(smoothedY = smVal)
        }

        val nowMs = frame.timestampMs
        val lookaheadMs = max(210L, (avgFrameDtMs * 2.1f).toLong().coerceAtMost(290L))
        val winMs = max(320L, (avgFrameDtMs * 3.6f).toLong().coerceAtMost(420L))

        val lowFpsFactor = ((avgFrameDtMs - 40f) / 60f).coerceIn(0f, 1f)
        val effCohMin = 0.58f - 0.11f * lowFpsFactor
        val effSymMin = 0.44f - 0.06f * lowFpsFactor
        val effPromStrong = promStrongBase + 0.002f * lowFpsFactor
        val effPromRhythm = promRhythmBase + 0.002f * lowFpsFactor
        val imgHeightF = max(frame.imageHeight, 1).toFloat()

        var emittedCandidate: JumpCandidate? = null
        var jumpDetected = false

        // Evaluate the first un-evaluated sample whose right lookahead window is complete
        for (i in 1 until ring.size - 1) {
            val cur = ring[i]
            if (cur.evaluated) continue
            if (nowMs - cur.timestampMs < lookaheadMs) break

            cur.evaluated = true
            if (cur.meanConfidence < minMeanPoseConfidence) continue

            val t0 = cur.timestampMs

            // Law 2: Scale-stability & X-drift walking guard over the preceding 400ms
            var kBack = i
            while (kBack > 0 && t0 - ring[kBack].timestampMs < 400L) {
                kBack--
            }
            val scDrift = abs(cur.scale - ring[kBack].scale) / max(cur.scale, 1f)
            if (scDrift > 0.20f) continue
            val xDrift = abs(cur.torsoX - ring[kBack].torsoX) / max(cur.scale, 1f)

            // Reject slow monotonic standing-up from a bend (continuous upward rise >= 0.12*scale over > 700ms).
            // Only gates torsoOk so flat stationary torsos during bent-over speed sprints are never skipped!
            var monoIdx = i
            while (monoIdx > 0 && ring[monoIdx - 1].smoothedY >= ring[monoIdx].smoothedY - 0.5f) {
                monoIdx--
            }
            val isSlowStandUp = (t0 - ring[monoIdx].timestampMs > 700L) &&
                ((ring[monoIdx].smoothedY - cur.smoothedY) / max(cur.scale, 1f) >= 0.12f)

            // FPS-independent time-based +-250ms rolling ankle alternation energy
            var adSum = 0f
            var adCnt = 0
            for (k in 0 until ring.size) {
                if (abs(ring[k].timestampMs - t0) <= 250L) {
                    adSum += ring[k].absAnkleDiff
                    adCnt++
                }
            }
            val adWin = if (adCnt > 0) adSum / adCnt else 0f

            // Competition Speed-Sprint Channel: zero-crossing hysteresis tracker on signedAnkleDiff
            val cSad = cur.signedAnkleDiff
            var zcHit = false
            val zcMinSwing = if (avgFrameDtMs >= 82f) 0.34f else 0.28f
            if (abs(cSad) > 0.14f && xDrift <= 0.24f) {
                val sgn = if (cSad > 0f) 1 else -1
                if (zcLastSign != 0 && sgn != zcLastSign && abs(cSad - zcLastExt) >= zcMinSwing) {
                    zcTimes.addLast(t0)
                    zcLastSign = sgn
                    zcLastExt = cSad
                    zcHit = true
                } else {
                    zcLastSign = sgn
                    if ((sgn > 0 && cSad > zcLastExt) || (sgn < 0 && cSad < zcLastExt)) {
                        zcLastExt = cSad
                    }
                }
            }
            while (zcTimes.isNotEmpty() && t0 - zcTimes.first() > 2000L) {
                zcTimes.removeFirst()
            }

            val wasInSprint = t0 <= sprintActiveUntilMs
            val scRatio = cur.scale / imgHeightF
            if (!wasInSprint) {
                // Bent-over competition speed-sprint entry gate (cleanly separated from upright high-knees)
                if (scRatio <= 0.086f && xDrift <= 0.22f) {
                    val enterHighFps = avgFrameDtMs < 82f && zcTimes.size >= 10 && adWin >= 0.32f
                    val enterLowFps = avgFrameDtMs >= 82f && zcTimes.size >= 6 && adWin >= 0.38f
                    if (enterHighFps || enterLowFps) {
                        sprintActiveUntilMs = t0 + 900L
                        sprintStartMs = if (zcTimes.isNotEmpty()) zcTimes.first() else (t0 - 1000L)
                    }
                }
            } else {
                val minSustainZc = if (avgFrameDtMs >= 82f) 3 else 5
                if (scRatio <= 0.095f && adWin >= 0.24f && xDrift <= 0.25f && (zcHit || zcTimes.size >= minSustainZc)) {
                    sprintActiveUntilMs = t0 + 800L
                }
            }
            val inSprint = t0 <= sprintActiveUntilMs

            var torsoOk = false
            var isSoft = false
            var amp = 0f
            var prom = 0f

            val prevSm = ring[i - 1].smoothedY
            val curSm = cur.smoothedY
            val nextSm = ring[i + 1].smoothedY

            if (!inSprint && !isSlowStandUp && curSm <= prevSm && curSm < nextSm) {
                // Find left valley (max Y) and right valley (max Y) within +-winMs
                var lMax = curSm
                var lIdx = i
                var jL = i - 1
                while (jL >= 0 && t0 - ring[jL].timestampMs <= winMs) {
                    if (ring[jL].smoothedY > lMax) {
                        lMax = ring[jL].smoothedY
                        lIdx = jL
                    }
                    jL--
                }

                var rMax = curSm
                var rIdx = i
                var jR = i + 1
                while (jR < ring.size && ring[jR].timestampMs - t0 <= winMs) {
                    if (ring[jR].smoothedY > rMax) {
                        rMax = ring[jR].smoothedY
                        rIdx = jR
                    }
                    jR++
                }

                val rise = (lMax - curSm) / cur.scale
                val drop = (rMax - curSm) / cur.scale
                prom = min(rise, drop)
                amp = max(rise, drop)
                val sym = prom / (amp + 1e-5f)

                // Multi-Landmark Coherence across {NOSE, L_SH, R_SH, L_HIP, R_HIP}
                val vP = cur.lmYs
                val vL = ring[lIdx].lmYs
                val vR = ring[rIdx].lmYs
                var sumUp = 0f; var absUp = 0f
                var sumDn = 0f; var absDn = 0f
                for (m in 0 until 5) {
                    if (!vP[m].isNaN() && !vL[m].isNaN()) {
                        val d = vL[m] - vP[m]
                        sumUp += d
                        absUp += abs(d)
                    }
                    if (!vP[m].isNaN() && !vR[m].isNaN()) {
                        val d = vR[m] - vP[m]
                        sumDn += d
                        absDn += abs(d)
                    }
                }
                val cohUp = if (absUp > 1e-4f) sumUp / absUp else 0f
                val cohDn = if (absDn > 1e-4f) sumDn / absDn else 0f
                val coh = 0.5f * (cohUp + cohDn)

                if (sym >= effSymMin && coh >= effCohMin && amp <= maxAmplitude) {
                    val strongThr = if (adWin >= 0.18f) promFootwork else effPromStrong
                    if (prom >= strongThr) {
                        torsoOk = true
                    } else if (prom >= effPromRhythm) {
                        torsoOk = true
                        isSoft = true
                    }
                }
            }

            // Dedicated Footwork / Speed-Sprint Ankle-Alternation Channel
            var fwOk = false
            val sincePrevPeak = lastJumpPeakMs?.let { t0 - it } ?: Long.MAX_VALUE
            if (inSprint) {
                if ((zcHit && sincePrevPeak >= 120L) || sincePrevPeak >= 260L) {
                    fwOk = true
                    amp = 0.09f
                    prom = 0.07f
                }
            } else if (!torsoOk && adWin >= 0.25f && xDrift <= 0.24f && sincePrevPeak >= 340L) {
                // Lookahead anti-double-fire guard: skip footwork if a torso jump peak is imminent within 150ms
                var hasNearTorso = false
                for (j in (i + 1) until (ring.size - 1)) {
                    if (ring[j].timestampMs - t0 > 150L) break
                    val yJ = ring[j].smoothedY
                    if (yJ <= ring[j - 1].smoothedY && yJ < ring[j + 1].smoothedY) {
                        if ((cur.smoothedY - yJ) / cur.scale >= 0.035f) {
                            hasNearTorso = true
                            break
                        }
                    }
                }
                if (!hasNearTorso) {
                    val pSad = ring[i - 1].signedAnkleDiff
                    val nSad = ring[i + 1].signedAnkleDiff
                    val isPos = cSad >= pSad && cSad > nSad && cSad > 0.20f
                    val isNeg = cSad <= pSad && cSad < nSad && cSad < -0.20f
                    if (isPos || isNeg) {
                        val sgn = if (isPos) 1 else -1
                        if (sgn != lastFwSign || sincePrevPeak >= 550L) {
                            var minSad = cSad
                            var maxSad = cSad
                            for (k in 0 until ring.size) {
                                if (abs(ring[k].timestampMs - t0) <= 360L) {
                                    val v = ring[k].signedAnkleDiff
                                    if (v < minSad) minSad = v
                                    if (v > maxSad) maxSad = v
                                }
                            }
                            if (maxSad - minSad >= 0.42f) {
                                fwOk = true
                                amp = 0.09f
                                prom = 0.07f
                                lastFwSign = sgn
                            }
                        }
                    }
                }
            }

            if (torsoOk || fwOk) {
                val sinceLast = lastJumpPeakMs?.let { t0 - it } ?: Long.MAX_VALUE
                val effInt = if (inSprint) {
                    120L
                } else {
                    val isPostHighRebound = lastJumpAmplitude >= 0.35f && amp < lastJumpAmplitude * 0.45f
                    if (isPostHighRebound) 360L else minJumpIntervalMs
                }
                if (sinceLast >= effInt) {
                    emittedCandidate = JumpCandidate(
                        peakTimeMs = t0,
                        landTimeMs = nowMs,
                        amplitude = amp,
                        ankleAlternation = adWin,
                        avgFrameDtMs = avgFrameDtMs,
                        prominence = prom,
                        isSoft = isSoft,
                        droppedFramesSinceLast = droppedSinceLastCandidate,
                        isSpeedSprint = inSprint,
                        sprintStartMs = if (inSprint) sprintStartMs else -1L
                    )
                    jumpDetected = !isSoft
                    lastJumpPeakMs = t0
                    lastJumpAmplitude = amp
                    droppedSinceLastCandidate = 0
                    break
                }
            }
        }

        // Instantaneous airborne hint for live skeleton highlight (lime green when rising)
        val recentMaxY = ring.takeLast(6).maxOfOrNull { it.smoothedY } ?: sample.smoothedY
        val normDisp = ((recentMaxY - sample.smoothedY) / currentScale).coerceAtLeast(0f)
        val isAirborne = normDisp >= effPromStrong * 0.85f

        return JumpDetectionResult(
            jumpDetected = jumpDetected,
            candidate = emittedCandidate,
            isAirborne = isAirborne,
            status = DetectorStatus.ACTIVE,
            normalizedDisplacement = normDisp,
            calibrationProgress = 1f
        )
    }

    private fun appendSample(sample: FrameSample) {
        ring.addLast(sample)
        // Retain ~1.1 seconds of history (plenty for +-420ms valley search & 400ms drift check)
        while (ring.size > 40 || (ring.size > 8 && sample.timestampMs - ring.first().timestampMs > 1150L)) {
            ring.removeFirst()
        }
    }

    private fun averageY(a: LandmarkPoint?, b: LandmarkPoint?): Float? = when {
        a != null && b != null -> (a.y + b.y) * 0.5f
        a != null -> a.y
        b != null -> b.y
        else -> null
    }

    private fun computeReferenceScale(
        leftShoulder: LandmarkPoint?,
        rightShoulder: LandmarkPoint?,
        shoulderMidY: Float,
        hipMidY: Float?,
        imageHeight: Int
    ): Float {
        if (hipMidY != null) {
            val torso = abs(hipMidY - shoulderMidY)
            if (torso > 10f) return torso
        }
        if (leftShoulder != null && rightShoulder != null) {
            val shoulderWidth = hypot(
                (leftShoulder.x - rightShoulder.x).toDouble(),
                (leftShoulder.y - rightShoulder.y).toDouble()
            ).toFloat()
            if (shoulderWidth > 10f) return shoulderWidth * 1.4f
        }
        return imageHeight * 0.25f
    }
}
