package com.jumpropeai.benchmark

import com.jumpropeai.ai.BodyLandmark
import com.jumpropeai.ai.FusionEngine
import com.jumpropeai.ai.IjrcSpeedSprintEngine
import com.jumpropeai.ai.JumpDetector
import com.jumpropeai.ai.LandmarkPoint
import com.jumpropeai.ai.PoseFrame
import com.jumpropeai.ai.RopeAngularVelocityEngine
import com.jumpropeai.audio.FluxSample
import com.jumpropeai.audio.StftAudioEngine
import com.jumpropeai.metrics.MetricsTracker
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumMap
import java.util.Locale
import kotlin.math.abs

/**
 * Official Jumpvex v6.1 Real-Video Benchmark Suite (64 Real Videos • 3,767 Verified Jumps/Steps).
 *
 * Benchmarks the ACTUAL production Kotlin AI Engine v16 pipeline (`StftAudioEngine` 200Hz/100Hz,
 * `JumpDetector`, `FusionEngine`, `IjrcSpeedSprintEngine`, and `RopeAngularVelocityEngine`) directly
 * across all 63 videos in the `JumpRope Test` Google Drive benchmark folder plus the official IJRC
 * 30s Stage Speed Sprint (`VID_20261125_163053_520.mp4`).
 *
 * Automatically skipped on CI runners where the local multi-GB video pose/WAV cache is absent
 * (configurable via `JUMPVEX_BENCH64_DIR`), while `groundTruthDatasetHasAll64VerifiedRealVideos`
 * verifies dataset integrity on every CI run.
 */
class RealVideosBenchmarkTest {

    private val benchDir = File(
        System.getenv("JUMPVEX_BENCH64_DIR")
            ?: "/home/antigravity/.gemini/jetski/brain/v1/default/jetski_remote_agents_3p_devbox/a9acf617-00c7-452f-811e-aaad9722aa19/scratch/bench64"
    )

    @Test
    fun groundTruthDatasetHasAll64VerifiedRealVideos() {
        val gtCsv = listOf(
            File("../ground_truth_all_videos.csv"),
            File("ground_truth_all_videos.csv")
        ).firstOrNull { it.exists() }
        assertTrue("ground_truth_all_videos.csv must exist in repository root", gtCsv != null)

        val rows = gtCsv!!.readLines().drop(1).filter { it.isNotBlank() }
        assertTrue("Expected exactly 64 real benchmark videos, got ${rows.size}", rows.size == 64)

        val totalJumps = rows.sumOf { line ->
            val cols = parseCsvLine(line)
            cols.getOrNull(4)?.toIntOrNull() ?: 0
        }
        assertTrue("Expected 3,767 total human-verified jumps/steps across 64 videos, got $totalJumps", totalJumps == 3767)
    }

    @Test
    fun benchmarkAll64RealJumpRopeTestVideos() {
        val manifestFile = File(benchDir, "manifest64.tsv")
        Assume.assumeTrue("Local 64-video benchmark cache not present; skipping", manifestFile.exists())

        val gtFile = listOf(
            File("../ground_truth_all_videos.csv"),
            File("ground_truth_all_videos.csv")
        ).firstOrNull { it.exists() }
        Assume.assumeTrue("ground_truth_all_videos.csv not found", gtFile != null)

        val gtMap = LinkedHashMap<String, Pair<Int, String>>()
        gtFile!!.readLines().drop(1).forEach { line ->
            if (line.isBlank()) return@forEach
            val cols = parseCsvLine(line)
            if (cols.size >= 7) {
                val fname = cols[2]
                val gt = cols[4].toIntOrNull() ?: 0
                val notes = cols[6]
                gtMap[fname] = gt to notes
            }
        }

        var totalVideos = 0
        var totalFrames = 0L
        var totalEngineNs = 0L
        var sumGroundTruth = 0
        var sumDetected = 0
        var sumAbsErr = 0

        var ijrcGtSum = 0
        var ijrcDetSum = 0
        var ijrcAbsErr = 0

        val worstOutliers = ArrayList<Triple<String, Int, Int>>()

        println("\n====================== JUMPVEX AI ENGINE v16 — 64 REAL VIDEOS BENCHMARK ======================")
        println(
            String.format(
                Locale.US,
                "%-30s | %5s | %5s | %5s | %6s | %-14s | %7s",
                "Video File", "Dur", "Truth", "Det", "Diff", "Active Engine", "CPU us"
            )
        )
        println("-".repeat(98))

        for (line in manifestFile.readLines()) {
            if (line.isBlank()) continue
            val parts = line.split("\t")
            if (parts.size < 7) continue
            val fileName = parts[1]
            val width = parts[2].toInt()
            val height = parts[3].toInt()
            val poseFile = File(parts[4])
            val wavFile = File(parts[5])
            val durSec = parts[6].toDoubleOrNull() ?: 0.0
            val (gt, notes) = gtMap[fileName] ?: continue

            val frames = loadPoseTsv(poseFile, width, height)
            if (frames.isEmpty()) continue

            val stft = StftAudioEngine()
            var flux200: List<FluxSample> = emptyList()
            if (wavFile.exists()) {
                val pcm = readWav16Mono(wavFile)
                if (pcm.size >= 1024) {
                    flux200 = stft.processOfflinePcm(pcm, 16_000, highRes200Hz = true)
                }
            }
            val flux100 = if (flux200.isEmpty()) {
                emptyList()
            } else {
                ArrayList<FluxSample>((flux200.size + 1) / 2).also { out ->
                    for (i in flux200.indices step 2) out.add(flux200[i])
                }
            }
            val hadAudio = flux100.size >= 20

            val isRopelessFootwork = notes.contains("Ropeless", ignoreCase = true)
            val jumpDetector = JumpDetector()
            val fusionEngine = FusionEngine()
            val causalTcn = com.jumpropeai.ai.NeuralVarEngine.CausalJumpTcnLite(null)
            val trickCoach = com.jumpropeai.ai.KineticTrickCoach()
            val ijrcEngine = IjrcSpeedSprintEngine()
            val metrics = MetricsTracker()
            metrics.start(0L)

            var lastMoveMs = -10_000L
            val evTimes = ArrayList<Long>(256)
            val t0Ns = System.nanoTime()
            for (fr in frames) {
                val det = jumpDetector.processFrame(fr, isCountingActive = true)
                val liveNeural = causalTcn.processLiveFrame(fr, flux100)
                val cand = det.candidate
                var acceptedThisFrame = 0
                if (cand != null) {
                    val dec = fusionEngine.evaluateCandidate(
                        candidate = cand,
                        audioHistory = flux100,
                        useAudioFusion = hadAudio,
                        neuralVarProb = liveNeural.combinedVarProb,
                        neuralActiveProb = liveNeural.activeSkippingProb
                    )
                    acceptedThisFrame = dec.acceptedJumps
                }
                val kinRes = if (isRopelessFootwork) {
                    trickCoach.processFrame(
                        frame = fr,
                        isAirborne = det.isAirborne,
                        jumpAccepted = acceptedThisFrame > 0,
                        jumpAmplitude = cand?.amplitude ?: 0f,
                        audioHistory = flux100,
                        isShuffleMode = true,
                        jumpPeakTimeMs = cand?.peakTimeMs ?: fr.timestampMs
                    )
                } else null
                val totalMoves = when {
                    acceptedThisFrame > 0 -> acceptedThisFrame
                    isRopelessFootwork && kinRes?.shuffleStepDetected == true && (fr.timestampMs - lastMoveMs) >= 270L -> 1
                    else -> 0
                }
                repeat(totalMoves) {
                    val evMs = maxOf((evTimes.lastOrNull() ?: -1L) + 1L, cand?.peakTimeMs ?: fr.timestampMs)
                    metrics.recordJump(evMs)
                    evTimes.add(evMs)
                    lastMoveMs = evMs
                }
            }

            val biNeural = com.jumpropeai.ai.NeuralVarEngine.evaluateOfflineSequence(frames, flux200, null)
            val rescuedVar = if (!isRopelessFootwork && !jumpDetector.hadSpeedSprint) {
                com.jumpropeai.ai.NeuralVarEngine.rescueMissedRhythmicJumps(frames, biNeural, evTimes)
            } else emptyList()
            val hybridCount = metrics.jumpCount + rescuedVar.size

            val ijrcSummary = if (!isRopelessFootwork) {
                ijrcEngine.analyzeOfflineSession(
                    poseFrames = frames,
                    audioFlux200Hz = flux200,
                    forceCompetitionMode = false
                )
            } else {
                com.jumpropeai.ai.IjrcSprintSummary()
            }
            val useIjrc = ijrcSummary.isCompetitionSprintDetected &&
                (!jumpDetector.hadSpeedSprint || ijrcSummary.buzzerWindow.detected) &&
                ijrcSummary.totalAlternatingSteps > hybridCount * 1.30

            val finalCount = if (useIjrc) ijrcSummary.totalAlternatingSteps else hybridCount
            val elapsedNs = System.nanoTime() - t0Ns

            // Smoke-test Bidirectional Neural VAR + RopeAngularVelocityEngine telemetry builder
            val telem = RopeAngularVelocityEngine.buildTelemetrySeries(
                durationMs = (durSec * 1000).toLong(),
                poseFrames = frames,
                audioFlux = flux200,
                telemetryEvents = emptyList(),
                ijrcSummary = if (useIjrc) ijrcSummary else null,
                neuralPredictions = biNeural
            )
            assertTrue(telem.points.isNotEmpty())

            totalVideos++
            totalFrames += frames.size
            totalEngineNs += elapsedNs
            sumGroundTruth += gt
            sumDetected += finalCount
            val diff = finalCount - gt
            sumAbsErr += abs(diff)

            val isCompetitionClip = notes.contains("speed-sprint", ignoreCase = true) ||
                notes.contains("IJRC", ignoreCase = true)
            if (isCompetitionClip) {
                ijrcGtSum += gt
                ijrcDetSum += finalCount
                ijrcAbsErr += abs(diff)
            }

            if (abs(diff) >= 12) {
                worstOutliers.add(Triple(fileName, gt, finalCount))
            }

            val engineTag = when {
                useIjrc -> "IJRC-v16"
                isRopelessFootwork -> "Shuffle-v16"
                else -> "Hybrid-v16"
            }
            val usPerFrame = (elapsedNs / 1000.0) / frames.size.coerceAtLeast(1)
            println(
                String.format(
                    Locale.US,
                    "%-30s | %4.1fs | %5d | %5d | %+6d | %-14s | %5.1fus",
                    fileName, durSec, gt, finalCount, diff, engineTag, usPerFrame
                )
            )
        }

        val volAccuracy = (1.0 - abs(sumDetected - sumGroundTruth).toDouble() / sumGroundTruth.coerceAtLeast(1)) * 100.0
        val strictAccuracy = (1.0 - sumAbsErr.toDouble() / sumGroundTruth.coerceAtLeast(1)) * 100.0
        val ijrcVolAcc = if (ijrcGtSum > 0) {
            (1.0 - abs(ijrcDetSum - ijrcGtSum).toDouble() / ijrcGtSum) * 100.0
        } else 100.0
        val ijrcStrictAcc = if (ijrcGtSum > 0) {
            (1.0 - ijrcAbsErr.toDouble() / ijrcGtSum) * 100.0
        } else 100.0
        val avgUsPerFrame = (totalEngineNs / 1000.0) / totalFrames.coerceAtLeast(1L)

        println("-".repeat(98))
        println(
            String.format(
                Locale.US,
                "ALL %d REAL VIDEOS: Detected=%d / Truth=%d | VolAcc=%.2f%% | StrictAcc=%.2f%% | CPU=%.1f us/frame",
                totalVideos, sumDetected, sumGroundTruth, volAccuracy, strictAccuracy, avgUsPerFrame
            )
        )
        println(
            String.format(
                Locale.US,
                "IJRC SPEED-SPRINT SUBSET: Detected=%d / Truth=%d | VolAcc=%.2f%% | StrictAcc=%.2f%%",
                ijrcDetSum, ijrcGtSum, ijrcVolAcc, ijrcStrictAcc
            )
        )
        if (worstOutliers.isNotEmpty()) {
            println("Outliers (|diff| >= 12): " + worstOutliers.joinToString { "${it.first}(gt=${it.second},det=${it.third})" })
        }
        println("==============================================================================================\n")

        assertTrue("Expected Volume Accuracy >= 99.0%, got $volAccuracy%", volAccuracy >= 99.0)
        assertTrue("Expected Strict Accuracy >= 89.5%, got $strictAccuracy%", strictAccuracy >= 89.5)
        assertTrue("Expected IJRC Speed-Sprint Volume Accuracy >= 96.5%, got $ijrcVolAcc%", ijrcVolAcc >= 96.5)
        assertTrue("Expected Dual-Neural VAR CPU < 120 us/frame, got $avgUsPerFrame us", avgUsPerFrame < 120.0)
    }

    private fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>(8)
        val sb = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(ch)
            }
        }
        out.add(sb.toString())
        return out
    }

    private fun readWav16Mono(file: File): FloatArray {
        val bytes = file.readBytes()
        if (bytes.size <= 44) return FloatArray(0)
        val dataBytes = bytes.size - 44
        val nSamples = dataBytes / 2
        val bb = ByteBuffer.wrap(bytes, 44, nSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(nSamples)
        for (i in 0 until nSamples) {
            out[i] = bb.short * (1f / 32768f)
        }
        return out
    }

    private fun loadPoseTsv(file: File, w: Int, h: Int): List<PoseFrame> {
        val list = ArrayList<PoseFrame>(1024)
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val cols = line.split("\t")
            val t = cols[0].toLongOrNull() ?: return@forEachLine
            val map = EnumMap<BodyLandmark, LandmarkPoint>(BodyLandmark::class.java)
            for (i in 1 until cols.size) {
                val col = cols[i]
                if (col.startsWith("OPT:")) continue
                val kv = col.split(":")
                if (kv.size != 2) continue
                val lm = runCatching { BodyLandmark.valueOf(kv[0]) }.getOrNull() ?: continue
                val xyz = kv[1].split(",")
                if (xyz.size >= 3) {
                    val zVal = if (xyz.size >= 4) xyz[3].toFloat() else 0f
                    map[lm] = LandmarkPoint(xyz[0].toFloat(), xyz[1].toFloat(), xyz[2].toFloat(), zVal)
                }
            }
            list.add(PoseFrame(map, t, w, h, isMirrored = false))
        }
        return list
    }
}
