package com.jumpropeai.ai

import com.jumpropeai.audio.FluxSample
import com.jumpropeai.video.VideoTelemetryEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class TelemetryChartPoint(
    val timeMs: Long,
    val stepSps: Float,
    val ropeRps: Float,
    val legOscillator: Float,
    val whipEnergy: Float,
    val isTripZone: Boolean,
    val neuralVarProb: Float = 0f,
    val cleanWhipEnergy: Float = 0f,
    val visualRopePass: Boolean = false
)

data class SplitIntervalBar(
    val label: String,
    val startSec: Int,
    val endSec: Int,
    val rightFootSteps: Int,
    val totalSteps: Int,
    val ropeTurns: Int,
    val avgSps: Float
)

data class RichTelemetrySeries(
    val points: List<TelemetryChartPoint> = emptyList(),
    val splits5s: List<SplitIntervalBar> = emptyList(),
    val fatigueDropPct: Float = 0f,
    val peakStepSps: Float = 0f,
    val peakRopeRps: Float = 0f,
    val totalRopeTurns: Int = 0,
    val verifiedVisualRopePasses: Int = 0,
    val meanNeuralConfidencePct: Int = 0
)

/**
 * Jumpvex AI Engine v17 — Step-Speed, Rope-Rotation Velocity, Neural VAR Probability & Visual Rope Telemetry.
 */
object RopeAngularVelocityEngine {

    fun buildTelemetrySeries(
        durationMs: Long,
        poseFrames: List<PoseFrame>,
        audioFlux: List<FluxSample>,
        telemetryEvents: List<VideoTelemetryEvent>,
        ijrcSummary: IjrcSprintSummary?,
        neuralPredictions: Array<NeuralFramePrediction>? = null,
        visualPassTimestampsMs: LongArray? = null
    ): RichTelemetrySeries {
        if (durationMs <= 500L) return RichTelemetrySeries()

        val stepSampleMs = 200L
        val totalBuckets = ((durationMs / stepSampleMs).toInt() + 1).coerceIn(2, 2400)

        // Normalize acoustic whip envelope (2.5–6.5 kHz wideHigh)
        val maxWhip = (audioFlux.maxOfOrNull { it.wideHigh } ?: 1f).coerceAtLeast(0.05f)

        val points = ArrayList<TelemetryChartPoint>(totalBuckets)
        var peakStepSps = 0f
        var peakRopeRps = 0f
        var totalRopeTurns = 0
        var neuralPeakSum = 0f
        var neuralPeakCnt = 0

        for (ev in telemetryEvents) {
            val mult = when (ev.move) {
                KineticMove.TRIPLE_UNDER -> 3
                KineticMove.DOUBLE_UNDER, KineticMove.DOUBLE_UNDER_CROSS -> 2
                else -> 1
            }
            totalRopeTurns += mult
        }
        if (ijrcSummary != null && ijrcSummary.isCompetitionSprintDetected && ijrcSummary.verifiedRopeTurns > totalRopeTurns) {
            totalRopeTurns = ijrcSummary.verifiedRopeTurns
        }

        var poseIdx = 0
        var fluxIdx = 0
        var vPassIdx = 0

        for (b in 0 until totalBuckets) {
            val tCenter = b * stepSampleMs
            val winLo = tCenter - 650L
            val winHi = tCenter + 650L
            val winSec = 1.30f

            // Count steps & rope turns inside sliding 1.3s Hann-like rate window
            var stepsInWin = 0f
            var turnsInWin = 0f
            if (ijrcSummary != null && ijrcSummary.isCompetitionSprintDetected && ijrcSummary.stepEvents.isNotEmpty()) {
                for (se in ijrcSummary.stepEvents) {
                    val dt = abs(se.timestampMs - tCenter)
                    if (dt <= 650L) {
                        val w = 1f - (dt / 750f) * 0.25f
                        stepsInWin += w
                        turnsInWin += w
                    }
                }
            } else {
                for (ev in telemetryEvents) {
                    if (ev.timestampMs in winLo..winHi) {
                        stepsInWin += 1f
                        val mult = when (ev.move) {
                            KineticMove.TRIPLE_UNDER -> 3f
                            KineticMove.DOUBLE_UNDER, KineticMove.DOUBLE_UNDER_CROSS -> 2f
                            else -> 1f
                        }
                        turnsInWin += mult
                    }
                }
            }

            // Sample local acoustic whip burst energy
            while (fluxIdx < audioFlux.size && audioFlux[fluxIdx].timestampMs < tCenter - 100L) {
                fluxIdx++
            }
            var whipPeak = 0f
            var fk = fluxIdx
            while (fk < audioFlux.size && audioFlux[fk].timestampMs <= tCenter + 100L) {
                val normW = (audioFlux[fk].wideHigh / maxWhip).coerceIn(0f, 1f)
                if (normW > whipPeak) whipPeak = normW
                fk++
            }

            // Sample leg phase wave & bidirectional Neural VAR probability around tCenter
            while (poseIdx + 1 < poseFrames.size && poseFrames[poseIdx + 1].timestampMs <= tCenter - 100L) {
                poseIdx++
            }
            val legWave = if (poseFrames.isNotEmpty()) computeLegPhaseAt(poseFrames[poseIdx.coerceAtMost(poseFrames.size - 1)]) else 0f

            var localNeuralProb = 0f
            var localCleanWhip = whipPeak
            if (neuralPredictions != null && neuralPredictions.size == poseFrames.size && poseFrames.isNotEmpty()) {
                var pk = poseIdx
                while (pk < poseFrames.size && poseFrames[pk].timestampMs <= tCenter + 110L) {
                    val np = neuralPredictions[pk]
                    if (np.combinedVarProb > localNeuralProb) localNeuralProb = np.combinedVarProb
                    val cw = (np.cleanWhipEnergy * 0.65f).coerceIn(0f, 1f)
                    if (cw > localCleanWhip) localCleanWhip = cw
                    pk++
                }
            } else if (stepsInWin > 0.5f) {
                localNeuralProb = (0.72f + 0.24f * whipPeak).coerceIn(0f, 0.99f)
            }
            if (localNeuralProb >= 0.45f) {
                neuralPeakSum += localNeuralProb
                neuralPeakCnt++
            }

            var hasVisPass = false
            if (visualPassTimestampsMs != null && visualPassTimestampsMs.isNotEmpty()) {
                while (vPassIdx < visualPassTimestampsMs.size && visualPassTimestampsMs[vPassIdx] < tCenter - 100L) {
                    vPassIdx++
                }
                if (vPassIdx < visualPassTimestampsMs.size && visualPassTimestampsMs[vPassIdx] <= tCenter + 100L) {
                    hasVisPass = true
                }
            }

            val stepSps = (stepsInWin / winSec).coerceAtLeast(0f)
            val rawRopeRps = (turnsInWin / winSec).coerceAtLeast(0f)
            val ropeRps = if (audioFlux.size >= 40 && rawRopeRps > 0.5f && whipPeak < 0.06f && !hasVisPass) {
                rawRopeRps * 0.45f
            } else {
                rawRopeRps
            }

            if (stepSps > peakStepSps) peakStepSps = stepSps
            if (ropeRps > peakRopeRps) peakRopeRps = ropeRps

            val isTrip = stepSps >= 1.5f && ropeRps < stepSps * 0.60f
            points.add(
                TelemetryChartPoint(
                    timeMs = tCenter,
                    stepSps = stepSps,
                    ropeRps = ropeRps,
                    legOscillator = legWave,
                    whipEnergy = whipPeak,
                    isTripZone = isTrip,
                    neuralVarProb = localNeuralProb,
                    cleanWhipEnergy = localCleanWhip,
                    visualRopePass = hasVisPass
                )
            )
        }

        // Build 5-second competition split bars (relative to buzzer start if IJRC, else 0s)
        val baseOffsetMs = if (ijrcSummary?.buzzerWindow?.detected == true) {
            ijrcSummary.buzzerWindow.startBuzzerMs
        } else {
            telemetryEvents.firstOrNull()?.timestampMs?.let { max(0L, it - 200L) } ?: 0L
        }
        val effectiveSpanMs = if (ijrcSummary?.buzzerWindow?.detected == true) {
            (ijrcSummary.buzzerWindow.endBuzzerMs - baseOffsetMs).coerceAtLeast(5_000L)
        } else {
            (durationMs - baseOffsetMs).coerceAtLeast(5_000L)
        }
        val numSplits = ((effectiveSpanMs + 4_999L) / 5_000L).toInt().coerceIn(1, 12)
        val splits = ArrayList<SplitIntervalBar>(numSplits)

        for (s in 0 until numSplits) {
            val sLo = baseOffsetMs + s * 5_000L
            val sHi = min(baseOffsetMs + effectiveSpanMs, sLo + 5_000L)
            val spanSec = max(1f, (sHi - sLo) / 1000f)
            var rFoot = 0
            var totSteps = 0
            var rTurns = 0
            if (ijrcSummary != null && ijrcSummary.isCompetitionSprintDetected && ijrcSummary.stepEvents.isNotEmpty()) {
                for (se in ijrcSummary.stepEvents) {
                    if (se.timestampMs in sLo until sHi) {
                        totSteps++
                        rTurns++
                        if (se.isRightFoot) rFoot++
                    }
                }
            } else {
                for (ev in telemetryEvents) {
                    if (ev.timestampMs in sLo until sHi) {
                        totSteps++
                        rFoot = (totSteps + 1) / 2
                        rTurns += when (ev.move) {
                            KineticMove.TRIPLE_UNDER -> 3
                            KineticMove.DOUBLE_UNDER, KineticMove.DOUBLE_UNDER_CROSS -> 2
                            else -> 1
                        }
                    }
                }
            }
            val startSecLabel = s * 5
            val endSecLabel = startSecLabel + 5
            splits.add(
                SplitIntervalBar(
                    label = "${startSecLabel}–${endSecLabel}s",
                    startSec = startSecLabel,
                    endSec = endSecLabel,
                    rightFootSteps = rFoot,
                    totalSteps = totSteps,
                    ropeTurns = rTurns,
                    avgSps = totSteps / spanSec
                )
            )
        }

        val activeSplits = splits.filter { it.totalSteps >= 3 }
        val fatigueDrop = if (activeSplits.size >= 2) {
            val firstSps = activeSplits.first().avgSps.coerceAtLeast(0.1f)
            val lastSps = activeSplits.last().avgSps
            (((firstSps - lastSps) / firstSps) * 100f).coerceIn(-50f, 80f)
        } else {
            0f
        }

        val meanConfPct = if (neuralPeakCnt > 0) {
            ((neuralPeakSum / neuralPeakCnt) * 100f).roundToInt().coerceIn(0, 100)
        } else 0

        return RichTelemetrySeries(
            points = points,
            splits5s = splits,
            fatigueDropPct = fatigueDrop,
            peakStepSps = peakStepSps,
            peakRopeRps = peakRopeRps,
            totalRopeTurns = totalRopeTurns,
            verifiedVisualRopePasses = visualPassTimestampsMs?.size ?: 0,
            meanNeuralConfidencePct = meanConfPct
        )
    }

    private fun computeLegPhaseAt(fr: PoseFrame): Float {
        val lm = fr.landmarks
        val lHip = lm[BodyLandmark.LEFT_HIP] ?: return 0f
        val rHip = lm[BodyLandmark.RIGHT_HIP] ?: return 0f
        val lKnee = lm[BodyLandmark.LEFT_KNEE]
        val rKnee = lm[BodyLandmark.RIGHT_KNEE]
        val lAnk = lm[BodyLandmark.LEFT_ANKLE]
        val rAnk = lm[BodyLandmark.RIGHT_ANKLE]
        val scale = max(fr.imageHeight * 0.22f, 1f)
        val kd = if (lKnee != null && rKnee != null) (lKnee.y - rKnee.y) / scale else 0f
        val ad = if (lAnk != null && rAnk != null) (lAnk.y - rAnk.y) / scale else 0f
        val hd = ((lHip.y - rHip.y) / scale) * 0.2f
        return ((kd * 0.6f + ad * 0.4f + hd) * 3.2f).coerceIn(-1f, 1f)
    }
}
