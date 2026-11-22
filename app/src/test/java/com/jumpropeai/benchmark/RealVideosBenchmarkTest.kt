package com.jumpropeai.benchmark

import com.jumpropeai.ai.BodyLandmark
import com.jumpropeai.ai.FusionEngine
import com.jumpropeai.ai.JumpDetector
import com.jumpropeai.ai.LandmarkPoint
import com.jumpropeai.ai.PoseFrame
import com.jumpropeai.audio.FluxSample
import com.jumpropeai.audio.StftAudioEngine
import com.jumpropeai.metrics.MetricsTracker
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Benchmarks the ACTUAL production Kotlin engines (JumpDetector, StftAudioEngine, FusionEngine,
 * MetricsTracker) directly on all 19 real-world jump rope videos (poses + 16 kHz WAV audio).
 *
 * Automatically skipped on machines where the local scratch dataset is not present.
 */
class RealVideosBenchmarkTest {

    private val scratchDir = File(
        "/home/antigravity/.gemini/jetski/brain/v1/default/jetski_remote_agents_3p_devbox/9a6e143a-ce20-4a18-892b-302fad4c8ceb/scratch"
    )

    @Test
    fun benchmarkProductionKotlinEngineOn19RealVideos() {
        val tracesFile = File(scratchDir, "pose_traces_all19.json")
        val vidDir = File(scratchDir, "videos_all")
        Assume.assumeTrue("Local 19-video dataset not present; skipping benchmark", tracesFile.exists())

        val jsonText = tracesFile.readText()
        // Lightweight manual parsing of top-level keys using org.json? No, Android unit tests stub org.json,
        // so let's parse the clean structured JSON with a tiny zero-dependency helper or pre-dumped TSV!
        val manifestFile = File(scratchDir, "kotlin_bench_manifest.txt")
        Assume.assumeTrue("Run export helper first", manifestFile.exists())

        var totalVideoMs = 0L
        var totalStftNs = 0L
        var totalVisionNs = 0L
        var totalFusionNs = 0L
        var totalFrames = 0L
        var totalAudioFrames = 0L
        var totalVisionJumps = 0
        var totalFusionJumps = 0
        var totalPllRescues = 0
        var totalTrips = 0

        println("\n==================== PRODUCTION KOTLIN ENGINE BENCHMARK (19 REAL VIDEOS) ====================")
        println(
            String.format(
                Locale.US,
                "%-26s | %5s | %4s | %4s | %4s | %-10s | %-18s | %6s | %6s",
                "Video", "Dur", "Vis", "Fus6", "PLL+", "Style", "Self-Learned", "STFTms", "Vis+Fms"
            )
        )
        println("-".repeat(112))

        val lines = manifestFile.readLines().filter { it.isNotBlank() }
        for (line in lines) {
            val parts = line.split("\t")
            val fid = parts[0]
            val title = parts[1]
            val width = parts[2].toInt()
            val height = parts[3].toInt()
            val poseFile = File(parts[4])
            val wavFile = File(vidDir, "$fid.wav")

            // 1. Benchmark StftAudioEngine (production Kotlin Radix-2 256-pt FFT)
            val stftEngine = StftAudioEngine()
            var audioFlux: List<FluxSample> = emptyList()
            val tStftStart = System.nanoTime()
            if (wavFile.exists()) {
                val pcm = readWav16Mono(wavFile)
                if (pcm.size >= 1024) {
                    audioFlux = stftEngine.processOfflinePcm(pcm, 16_000)
                }
            }
            val stftNs = System.nanoTime() - tStftStart
            totalStftNs += stftNs
            totalAudioFrames += audioFlux.size

            // 2. Load pose frames for this video
            val frames = loadPoseTsv(poseFile, width, height)
            if (frames.isEmpty()) continue
            val durMs = frames.last().timestampMs
            totalVideoMs += durMs
            totalFrames += frames.size

            // 3. Benchmark JumpDetector + FusionEngine + MetricsTracker
            val jumpDetector = JumpDetector()
            val fusionEngine = FusionEngine()
            val metrics = MetricsTracker()
            metrics.start(0L)

            var visStandalone = 0
            var visNs = 0L
            var fusNs = 0L
            val hadAudio = audioFlux.size >= 20

            for (fr in frames) {
                val tv0 = System.nanoTime()
                val det = jumpDetector.processFrame(fr, isCountingActive = true)
                visNs += (System.nanoTime() - tv0)

                if (det.jumpDetected) visStandalone++
                val cand = det.candidate
                if (cand != null) {
                    val tf0 = System.nanoTime()
                    val dec = fusionEngine.evaluateCandidate(cand, audioFlux, useAudioFusion = hadAudio)
                    fusNs += (System.nanoTime() - tf0)
                    repeat(dec.acceptedJumps) {
                        metrics.recordJump(cand.landTimeMs)
                    }
                }
            }
            metrics.pause(durMs)

            totalVisionNs += visNs
            totalFusionNs += fusNs
            totalVisionJumps += visStandalone
            totalFusionJumps += metrics.jumpCount
            totalPllRescues += fusionEngine.totalPllBridged
            totalTrips += fusionEngine.totalTrips

            val fp = fusionEngine.currentFingerprint()
            val learnedStr = if (fp.isCalibrated) "${fp.band.label}@${fp.lagMs}ms" else if (hadAudio) "LowSNR Fallback" else "Silent"

            println(
                String.format(
                    Locale.US,
                    "%-26s | %4.1fs | %4d | %4d | +%3d | %-10s | %-18s | %5.1fms | %5.2fms",
                    title.take(26),
                    durMs / 1000.0,
                    visStandalone,
                    metrics.jumpCount,
                    fusionEngine.totalPllBridged,
                    fusionEngine.latestStyle.label,
                    learnedStr.take(18),
                    stftNs / 1e6,
                    (visNs + fusNs) / 1e6
                )
            )
        }

        val totalEngineMs = (totalStftNs + totalVisionNs + totalFusionNs) / 1e6
        val realtimeSpeedup = totalVideoMs / maxOf(totalEngineMs, 0.001)
        val usPerAudioFrame = (totalStftNs / 1000.0) / maxOf(totalAudioFrames, 1L)
        val usPerVideoFrame = ((totalVisionNs + totalFusionNs) / 1000.0) / maxOf(totalFrames, 1L)

        println("-".repeat(112))
        println(
            String.format(
                Locale.US,
                "SUMMARY (19 videos, %.1fs total video, %d pose frames, %d STFT audio frames):",
                totalVideoMs / 1000.0,
                totalFrames,
                totalAudioFrames
            )
        )
        println(
            String.format(
                Locale.US,
                "  Jumps: Vision-Only=%d | Kotlin Fusion v6=%d (PLL rescued=+%d, Trips=%d)",
                totalVisionJumps,
                totalFusionJumps,
                totalPllRescues,
                totalTrips
            )
        )
        println(
            String.format(
                Locale.US,
                "  CPU Speed: STFT=%.1f us/frame (100Hz) | Vision+Fusion=%.1f us/frame (30Hz)",
                usPerAudioFrame,
                usPerVideoFrame
            )
        )
        println(
            String.format(
                Locale.US,
                "  Total Engine Time: %.1f ms for %.1f s of video (%.0fx faster than real-time!)",
                totalEngineMs,
                totalVideoMs / 1000.0,
                realtimeSpeedup
            )
        )
        println("============================================================================================\n")
    }

    @Test
    fun benchmarkLowEndSmartphoneSimulation() {
        val manifestFile = File(scratchDir, "kotlin_bench_manifest.txt")
        val vidDir = File(scratchDir, "videos_all")
        Assume.assumeTrue("Local 19-video dataset not present; skipping simulation", manifestFile.exists())

        data class PhoneProfile(
            val name: String,
            val targetFps: Int,
            val jitterPx: Float,
            val dropRate: Float
        )

        val profiles = listOf(
            PhoneProfile("Flagship/Mid (30 FPS, 0px jitter)", 30, 0f, 0.00f),
            PhoneProfile("Budget Phone (15 FPS, +-3px, 5% drop)", 15, 3f, 0.05f),
            PhoneProfile("Low-End      (10 FPS, +-6px, 10% drop)", 10, 6f, 0.10f),
            PhoneProfile("Extreme Low  ( 8 FPS, +-8px, 15% drop)", 8, 8f, 0.15f)
        )

        val lines = manifestFile.readLines().filter { it.isNotBlank() }

        // Preload audio flux & raw frames once so all 4 profiles run on identical data
        data class VideoData(
            val title: String,
            val frames: List<PoseFrame>,
            val audioFlux: List<FluxSample>
        )

        val dataset = lines.mapNotNull { line ->
            val p = line.split("\t")
            val fid = p[0]; val title = p[1]; val w = p[2].toInt(); val h = p[3].toInt()
            val frames = loadPoseTsv(File(p[4]), w, h)
            if (frames.isEmpty()) return@mapNotNull null
            val wav = File(vidDir, "$fid.wav")
            val flux = if (wav.exists()) {
                val pcm = readWav16Mono(wav)
                if (pcm.size >= 1024) StftAudioEngine().processOfflinePcm(pcm, 16_000) else emptyList()
            } else {
                emptyList()
            }
            VideoData(title, frames, flux)
        }

        println("\n==================== LOW-END SMARTPHONE SIMULATION (19 REAL VIDEOS) ====================")
        println(
            String.format(
                Locale.US,
                "%-40s | %10s | %10s | %8s | %8s",
                "Simulated Device Profile", "VisionOnly", "Fusion v6", "PLL+Resc", "Retention"
            )
        )
        println("-".repeat(88))

        var baselineFusion = 0
        for (prof in profiles) {
            val minStepMs = (1000L / prof.targetFps)
            val rng = java.util.Random(42L)
            var sumVis = 0
            var sumFus = 0
            var sumPll = 0

            for (vid in dataset) {
                val jd = JumpDetector()
                val fe = FusionEngine()
                val hadAudio = vid.audioFlux.size >= 20
                var lastT = -1000L

                for (fr in vid.frames) {
                    if (fr.timestampMs - lastT < minStepMs) continue
                    lastT = fr.timestampMs

                    // Simulate low-end GC frame drop / lost tracking
                    val simulatedFrame = if (prof.dropRate > 0f && rng.nextFloat() < prof.dropRate) {
                        fr.copy(landmarks = emptyMap())
                    } else if (prof.jitterPx > 0f) {
                        val noisy = fr.landmarks.mapValues { (_, pt) ->
                            val jx = (rng.nextFloat() * 2f - 1f) * prof.jitterPx
                            val jy = (rng.nextFloat() * 2f - 1f) * prof.jitterPx
                            pt.copy(x = pt.x + jx, y = pt.y + jy)
                        }
                        fr.copy(landmarks = noisy)
                    } else {
                        fr
                    }

                    val det = jd.processFrame(simulatedFrame, isCountingActive = true)
                    if (det.jumpDetected) sumVis++
                    val cand = det.candidate
                    if (cand != null) {
                        val dec = fe.evaluateCandidate(cand, vid.audioFlux, useAudioFusion = hadAudio)
                        sumFus += dec.acceptedJumps
                        sumPll += dec.pllBridgedJumps
                    }
                }
            }

            if (baselineFusion == 0) baselineFusion = maxOf(sumFus, 1)
            val retentionPct = (sumFus * 100.0) / baselineFusion

            println(
                String.format(
                    Locale.US,
                    "%-40s | %10d | %10d | +%7d | %7.1f%%",
                    prof.name,
                    sumVis,
                    sumFus,
                    sumPll,
                    retentionPct
                )
            )
        }
        println("========================================================================================\n")
    }

    @Test
    fun experimentLowEndOptimization() {
        val manifestFile = File(scratchDir, "kotlin_bench_manifest.txt")
        val vidDir = File(scratchDir, "videos_all")
        Assume.assumeTrue("Local 19-video dataset not present", manifestFile.exists())

        data class PhoneProfile(val name: String, val fps: Int, val jitterPx: Float, val dropRate: Float)
        val profiles = listOf(
            PhoneProfile("Flagship/Mid (30 FPS, 0px)", 30, 0f, 0.00f),
            PhoneProfile("Budget Phone (15 FPS, +-3px, 5% drop)", 15, 3f, 0.05f),
            PhoneProfile("Low-End      (10 FPS, +-6px, 10% drop)", 10, 6f, 0.10f),
            PhoneProfile("Extreme Low  ( 8 FPS, +-8px, 15% drop)", 8, 8f, 0.15f)
        )

        data class VideoData(val frames: List<PoseFrame>, val audioFlux: List<FluxSample>)
        val dataset = manifestFile.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split("\t")
            val frames = loadPoseTsv(File(p[4]), p[2].toInt(), p[3].toInt())
            if (frames.isEmpty()) return@mapNotNull null
            val wav = File(vidDir, "${p[0]}.wav")
            val flux = if (wav.exists()) {
                val pcm = readWav16Mono(wav)
                if (pcm.size >= 1024) StftAudioEngine().processOfflinePcm(pcm, 16_000) else emptyList()
            } else emptyList()
            VideoData(frames, flux)
        }

        // Experimental FPS-aware detector + extended PLL audio bridging
        fun runLowEndOpt(vid: VideoData, prof: PhoneProfile, rng: java.util.Random): Pair<Int, Int> {
            val minStepMs = 1000L / prof.fps
            val hadAudio = vid.audioFlux.size >= 20
            val fe = FusionEngine()

            // FPS-aware adaptive Peak-Valley detector
            var prevFrameMs: Long? = null
            var avgDtMs = 33f
            var smoothedY: Float? = null
            var smoothedScale: Float? = null
            var warmup = 0
            var seekingValley = false
            var valleyY = 0f; var valleyT = 0L
            var peakY = 0f; var peakT = 0L
            var lastJumpT: Long? = null

            var totalJumps = 0
            var totalPll = 0
            var lastSampledT = -1000L

            for (fr in vid.frames) {
                if (fr.timestampMs - lastSampledT < minStepMs) continue
                lastSampledT = fr.timestampMs

                val sim = if (prof.dropRate > 0f && rng.nextFloat() < prof.dropRate) {
                    fr.copy(landmarks = emptyMap())
                } else if (prof.jitterPx > 0f) {
                    fr.copy(landmarks = fr.landmarks.mapValues { (_, pt) ->
                        pt.copy(
                            x = pt.x + (rng.nextFloat() * 2f - 1f) * prof.jitterPx,
                            y = pt.y + (rng.nextFloat() * 2f - 1f) * prof.jitterPx
                        )
                    })
                } else fr

                val nowMs = sim.timestampMs
                prevFrameMs?.let { pMs ->
                    val d = (nowMs - pMs).coerceIn(16L, 300L).toFloat()
                    avgDtMs = avgDtMs * 0.85f + d * 0.15f
                }
                prevFrameMs = nowMs

                if (sim.landmarks.isEmpty()) continue
                val nose = sim.landmarks[BodyLandmark.NOSE]?.takeIf { it.confidence >= 0.4f }
                val ls = sim.landmarks[BodyLandmark.LEFT_SHOULDER]?.takeIf { it.confidence >= 0.4f }
                val rs = sim.landmarks[BodyLandmark.RIGHT_SHOULDER]?.takeIf { it.confidence >= 0.4f }
                val lh = sim.landmarks[BodyLandmark.LEFT_HIP]?.takeIf { it.confidence >= 0.4f }
                val rh = sim.landmarks[BodyLandmark.RIGHT_HIP]?.takeIf { it.confidence >= 0.4f }
                val shY = when {
                    ls != null && rs != null -> (ls.y + rs.y) * 0.5f
                    ls != null -> ls.y
                    rs != null -> rs.y
                    else -> continue
                }
                val hipY = when {
                    lh != null && rh != null -> (lh.y + rh.y) * 0.5f
                    lh != null -> lh.y
                    rh != null -> rh.y
                    else -> null
                }
                val rawY = when {
                    nose != null && hipY != null -> 0.25f * nose.y + 0.50f * shY + 0.25f * hipY
                    hipY != null -> 0.60f * shY + 0.40f * hipY
                    else -> shY
                }
                val rawScale = if (hipY != null && kotlin.math.abs(hipY - shY) > 10f) {
                    kotlin.math.abs(hipY - shY)
                } else 100f

                // KEY UPGRADE 1: FPS-Aware Adaptive Smoothing (keep minProm=0.045f to reject jitter!)
                val lowFpsFactor = ((avgDtMs - 40f) / 60f).coerceIn(0f, 1f)
                val alpha = 0.55f + 0.33f * lowFpsFactor
                val minProm = 0.045f
                val dropFrac = 0.40f - 0.08f * lowFpsFactor

                val curY = smoothedY?.let { it + alpha * (rawY - it) } ?: rawY
                smoothedY = curY
                val curScale = smoothedScale?.let { it + 0.10f * (rawScale - it) } ?: rawScale
                smoothedScale = curScale

                if (warmup < 3) {
                    warmup++; valleyY = curY; valleyT = nowMs; peakY = curY; peakT = nowMs; continue
                }

                if (!seekingValley) {
                    if (curY >= valleyY || (nowMs - valleyT) > 580L) {
                        valleyY = curY; valleyT = nowMs
                    }
                    val rise = (valleyY - curY) / curScale
                    if (rise >= minProm) {
                        seekingValley = true; peakY = curY; peakT = nowMs
                    }
                } else {
                    if (curY <= peakY) {
                        peakY = curY; peakT = nowMs
                    }
                    val drop = (curY - peakY) / curScale
                    val amp = (valleyY - peakY) / curScale
                    val riseMs = peakT - valleyT
                    val dropMs = nowMs - peakT
                    if (dropMs > 480L) {
                        seekingValley = false; valleyY = curY; valleyT = nowMs
                    } else if (drop >= kotlin.math.max(minProm * 0.6f, amp * dropFrac)) {
                        val sinceLast = lastJumpT?.let { nowMs - it } ?: Long.MAX_VALUE
                        if (riseMs in 40L..580L && amp <= 1.05f && sinceLast >= 200L) {
                            val cand = com.jumpropeai.ai.JumpCandidate(peakT, nowMs, amp, 0f)
                            val dec = fe.evaluateCandidate(cand, vid.audioFlux, useAudioFusion = hadAudio)
                            totalJumps += dec.acceptedJumps
                            totalPll += dec.pllBridgedJumps
                            lastJumpT = nowMs
                        }
                        seekingValley = false; valleyY = curY; valleyT = nowMs
                    }
                }
            }
            return totalJumps to totalPll
        }

        println("\n================ EXPERIMENT: LOW-END OPTIMIZATION (BEFORE vs AFTER) ================")
        println(String.format(Locale.US, "%-38s | %10s | %10s | %8s", "Simulated Device Profile", "Before", "After Opt", "Retention"))
        println("-".repeat(78))

        val beforeCounts = listOf(763, 671, 569, 465)
        var baseAfter = 0
        profiles.forEachIndexed { idx, prof ->
            val rng = java.util.Random(42L)
            var sumJ = 0
            var sumPll = 0
            for (vid in dataset) {
                val (j, p) = runLowEndOpt(vid, prof, rng)
                sumJ += j; sumPll += p
            }
            if (idx == 0) baseAfter = maxOf(sumJ, 1)
            val ret = sumJ * 100.0 / baseAfter
            println(
                String.format(
                    Locale.US,
                    "%-38s | %10d | %10d | %7.1f%% (PLL+%d)",
                    prof.name, beforeCounts[idx], sumJ, ret, sumPll
                )
            )
        }
        println("====================================================================================\n")
    }

    private fun readWav16Mono(file: File): FloatArray {
        val bytes = file.readBytes()
        if (bytes.size <= 44) return FloatArray(0)
        // Standard 44-byte PCM WAV header from ffmpeg
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
        val list = ArrayList<PoseFrame>()
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val cols = line.split("\t")
            val t = cols[0].toLong()
            val map = mutableMapOf<BodyLandmark, LandmarkPoint>()
            for (i in 1 until cols.size) {
                val kv = cols[i].split(":")
                if (kv.size != 2) continue
                val lm = runCatching { BodyLandmark.valueOf(kv[0]) }.getOrNull() ?: continue
                val xyz = kv[1].split(",")
                if (xyz.size == 3) {
                    map[lm] = LandmarkPoint(xyz[0].toFloat(), xyz[1].toFloat(), xyz[2].toFloat())
                }
            }
            list.add(PoseFrame(map, t, w, h, isMirrored = false))
        }
        return list
    }
}
