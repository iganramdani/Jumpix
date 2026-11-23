package com.jumpropeai.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.jumpropeai.ai.AcousticFingerprint
import com.jumpropeai.ai.BodyLandmark
import com.jumpropeai.ai.FusionEngine
import com.jumpropeai.ai.JumpDetector
import com.jumpropeai.ai.JumpStyle
import com.jumpropeai.ai.KineticMove
import com.jumpropeai.ai.KineticTrickCoach
import com.jumpropeai.ai.LandmarkPoint
import com.jumpropeai.ai.PoseFrame
import com.jumpropeai.audio.BeatSyncEngine
import com.jumpropeai.audio.FluxSample
import com.jumpropeai.audio.StftAudioEngine
import com.jumpropeai.debug.SessionDiagnosticLogger
import com.jumpropeai.metrics.MetricsTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

data class VideoAnalysisProgress(
    val stage: String,
    val fraction: Float,
    val currentJumps: Int,
    val currentTimeMs: Long = 0L,
    val durationMs: Long = 0L,
    val currentRpm: Int = 0,
    val estimatedCalories: Double = 0.0,
    val currentStreak: Int = 0,
    val isAirborne: Boolean = false,
    val style: JumpStyle = JumpStyle.REGULAR,
    val currentMove: KineticMove = KineticMove.REGULAR,
    val fingerprint: AcousticFingerprint = AcousticFingerprint(),
    val previewBitmap: Bitmap? = null,
    val latestPose: PoseFrame? = null
)

data class VideoAnalysisResult(
    val fileName: String,
    val durationMs: Long,
    val jumpCount: Int,
    val averageRpm: Int,
    val peakRpm: Int,
    val estimatedCalories: Double,
    val bestStreak: Int,
    val trips: Int,
    val pllBridged: Int,
    val style: JumpStyle,
    val fingerprint: AcousticFingerprint,
    val hadAudioTrack: Boolean,
    val finalPreviewBitmap: Bitmap? = null,
    val sourceUri: Uri? = null,
    val formScore: Int = 0,
    val formTip: String = "",
    val moveCounts: Map<KineticMove, Int> = emptyMap(),
    val highlightStartMs: Long = 0L,
    val highlightEndMs: Long = 0L,
    val highlightSummary: String = "",
    val savedDebugLogPath: String? = null,
    val debugLogFile: File? = null
)

/**
 * High-speed hardware-accelerated video jump rope & footwork analyzer with real-time CSV debug logging.
 */
class VideoJumpAnalyzer(private val context: Context) {

    suspend fun analyzeVideo(
        uri: Uri,
        fileName: String,
        enableDebugLog: Boolean = true,
        onProgress: (VideoAnalysisProgress) -> Unit
    ): VideoAnalysisResult = withContext(Dispatchers.Default) {
        onProgress(VideoAnalysisProgress("Scanning audio spectrum...", 0.04f, 0))

        // 1. Fast primitive-array MP4 audio decode -> 100 Hz multi-band STFT flux
        val audioFlux = decodeMp4AudioToFlux(uri)
        val hadAudio = audioFlux.size >= 20

        ensureActive()
        onProgress(
            VideoAnalysisProgress(
                stage = if (hadAudio) "Audio ready • Starting fast decoder..." else "Silent video • Starting fast decoder...",
                fraction = 0.10f,
                currentJumps = 0
            )
        )

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val poseDetector = PoseDetection.getClient(
            PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build()
        )

        val jumpDetector = JumpDetector()
        val fusionEngine = FusionEngine()
        val kineticTrickCoach = KineticTrickCoach()
        val beatSyncEngine = BeatSyncEngine()
        val metricsTracker = MetricsTracker()
        val videoLogger = if (enableDebugLog) {
            SessionDiagnosticLogger().also {
                it.startSession(
                    context = context,
                    modeLabel = "Video Studio",
                    sensorLabel = if (hadAudio) "Fusion AI" else "Video Only",
                    filePrefix = "Video_${fileName.substringBeforeLast('.')}"
                )
            }
        } else {
            null
        }
        val jumpTimestamps = ArrayList<Long>(256)
        var lastDisplayedBitmap: Bitmap? = null

        try {
            extractor.setDataSource(context, uri, null)
            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = fmt
                    break
                }
            }
            if (videoTrackIndex < 0 || videoFormat == null) {
                throw IllegalArgumentException("Video track not found in file")
            }

            extractor.selectTrack(videoTrackIndex)
            val mime = videoFormat.getString(MediaFormat.KEY_MIME)!!
            val durationMs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
                (videoFormat.getLong(MediaFormat.KEY_DURATION) / 1000L).coerceAtLeast(1000L)
            } else {
                10_000L
            }
            val rotationDegrees = if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                videoFormat.getInteger(MediaFormat.KEY_ROTATION)
            } else {
                0
            }

            // Request universal flexible YUV420 output for zero-copy hardware decoding
            videoFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(videoFormat, null, null, 0)
                start()
            }

            metricsTracker.start(0L)

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastProcessedMs = -1000L
            var lastUiEmitWallMs = 0L
            var lastFrameMs = 0L

            // Reusable buffers to eliminate per-frame GC pauses
            var nv21Buffer: ByteArray? = null
            var previewArgbBuffer: IntArray? = null

            while (!outputDone) {
                ensureActive()

                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(5_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 5_000L)
                if (outIdx >= 0) {
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val currentMs = (bufferInfo.presentationTimeUs / 1000L).coerceAtLeast(0L)

                    // Sample at ~15.4 FPS (every >= 65ms): full accuracy up to 240 RPM + smoother low-end speed
                    val shouldProcess = !isEos && bufferInfo.size > 0 && (currentMs - lastProcessedMs >= 65L)

                    if (shouldProcess) {
                        val prevProcMs = lastProcessedMs
                        lastProcessedMs = currentMs
                        lastFrameMs = currentMs

                        val outputImage = runCatching { codec.getOutputImage(outIdx) }.getOrNull()
                        if (outputImage != null) {
                            val imgW = outputImage.width
                            val imgH = outputImage.height
                            val neededSize = imgW * imgH * 3 / 2
                            val nv21 = if (nv21Buffer != null && nv21Buffer.size == neededSize) {
                                nv21Buffer
                            } else {
                                ByteArray(neededSize).also { nv21Buffer = it }
                            }
                            yuv420ToNv21(outputImage, nv21)
                            outputImage.close()

                            // Release hardware buffer immediately BEFORE running ML Kit!
                            codec.releaseOutputBuffer(outIdx, false)

                            val inputImage = InputImage.fromByteBuffer(
                                ByteBuffer.wrap(nv21),
                                imgW,
                                imgH,
                                rotationDegrees,
                                InputImage.IMAGE_FORMAT_NV21
                            )

                            // Start ML Kit asynchronously AND build fast RGB_565 preview concurrently (both read-only on nv21!)
                            val poseTask = poseDetector.process(inputImage)
                            val nowWallMs = System.nanoTime() / 1_000_000L
                            val wantUiFrame = (nowWallMs - lastUiEmitWallMs >= 32L)
                            val previewBmp = if (wantUiFrame) {
                                val (bmp, reusedArr) = nv21ToFastPreviewBitmap(
                                    nv21 = nv21,
                                    width = imgW,
                                    height = imgH,
                                    rotationDegrees = rotationDegrees,
                                    reuseArgb = previewArgbBuffer
                                )
                                previewArgbBuffer = reusedArr
                                bmp
                            } else {
                                null
                            }

                            val pose = awaitTask(poseTask)

                            val isRotated = rotationDegrees == 90 || rotationDegrees == 270
                            val effW = if (isRotated) imgH else imgW
                            val effH = if (isRotated) imgW else imgH
                            val poseFrame = pose.toPoseFrame(currentMs, effW, effH)

                            val aiStartNs = if (videoLogger != null) System.nanoTime() else 0L
                            val det = jumpDetector.processFrame(poseFrame, isCountingActive = true)
                            var jumpJustCounted = false
                            val cand = det.candidate
                            if (cand != null) {
                                val decision = fusionEngine.evaluateCandidate(
                                    candidate = cand,
                                    audioHistory = audioFlux,
                                    useAudioFusion = hadAudio
                                )
                                if (decision.acceptedJumps > 0) {
                                    jumpJustCounted = true
                                    repeat(decision.acceptedJumps) {
                                        metricsTracker.recordJump(cand.landTimeMs)
                                        jumpTimestamps.add(cand.landTimeMs)
                                    }
                                    if (hadAudio) {
                                        beatSyncEngine.recordMoveOnBeat(cand.landTimeMs)
                                    }
                                }
                            }

                            // Update STFT Music BPM & Beat-Sync every ~500ms during Video Studio analysis
                            if (hadAudio && (currentMs / 500L) != (prevProcMs.coerceAtLeast(0L) / 500L)) {
                                beatSyncEngine.updateFromAudio(audioFlux, currentMs)
                            }

                            // Run AI v13.5 Trick & Form Coach in parallel
                            val kineticRes = kineticTrickCoach.processFrame(
                                frame = poseFrame,
                                isAirborne = det.isAirborne,
                                jumpAccepted = jumpJustCounted,
                                jumpAmplitude = cand?.amplitude ?: 0f,
                                audioHistory = audioFlux,
                                isShuffleMode = false
                            )

                            if (videoLogger != null) {
                                val aiUs = ((System.nanoTime() - aiStartNs) / 1000L).toInt().coerceAtLeast(1)
                                val dtFps = if (prevProcMs >= 0L && currentMs > prevProcMs) {
                                    (1000.0 / (currentMs - prevProcMs)).roundToInt()
                                } else {
                                    15
                                }
                                val rt = Runtime.getRuntime()
                                val heapMb = ((rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)).toInt()
                                val beatSnap = beatSyncEngine.snapshot()
                                videoLogger.recordFrame(
                                    tsMs = currentMs,
                                    fps = dtFps,
                                    aiUs = aiUs,
                                    heapMb = heapMb,
                                    jumps = metricsTracker.jumpCount,
                                    isAirborne = det.isAirborne,
                                    moveName = kineticRes.currentMove.name,
                                    formScore = kineticRes.formSnapshot.overallScore,
                                    bpm = beatSnap.detectedBpm,
                                    syncPct = beatSnap.beatSyncScore,
                                    shRatio = kineticTrickCoach.lastShRatio,
                                    ankSep = kineticTrickCoach.lastAnkSep,
                                    ankDy = kineticTrickCoach.lastAnkDy,
                                    knAsym = kineticTrickCoach.lastKnAsym,
                                    vLxTimesVRx = kineticTrickCoach.lastVLxTimesVRx,
                                    hipVx = kineticTrickCoach.lastHipVx,
                                    hipStd = kineticTrickCoach.lastHipStd,
                                    lPitch = kineticTrickCoach.lastLPitch,
                                    rPitch = kineticTrickCoach.lastRPitch,
                                    vYawL = kineticTrickCoach.lastVYawL,
                                    vYawR = kineticTrickCoach.lastVYawR
                                )
                            }

                            // Emit smooth live preview + skeleton on EVERY processed frame (capped at ~30 FPS wall-clock)
                            if (wantUiFrame || jumpJustCounted) {
                                if (previewBmp != null) {
                                    lastDisplayedBitmap = previewBmp
                                }
                                lastUiEmitWallMs = nowWallMs
                                val frac = 0.10f + 0.90f * (currentMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                                onProgress(
                                    VideoAnalysisProgress(
                                        stage = "Analyzing (${(frac * 100).toInt()}%)",
                                        fraction = frac,
                                        currentJumps = metricsTracker.jumpCount,
                                        currentTimeMs = currentMs,
                                        durationMs = durationMs,
                                        currentRpm = metricsTracker.getCurrentRpm(currentMs),
                                        estimatedCalories = metricsTracker.estimatedCalories,
                                        currentStreak = metricsTracker.getActiveStreak(currentMs),
                                        isAirborne = det.isAirborne,
                                        style = fusionEngine.latestStyle,
                                        currentMove = kineticRes.currentMove,
                                        fingerprint = fusionEngine.currentFingerprint(),
                                        previewBitmap = lastDisplayedBitmap,
                                        latestPose = poseFrame
                                    )
                                )
                            }
                        } else {
                            codec.releaseOutputBuffer(outIdx, false)
                        }
                    } else {
                        codec.releaseOutputBuffer(outIdx, false)
                    }

                    if (isEos) {
                        outputDone = true
                    }
                }
            }

            val finalDur = maxOf(durationMs, lastFrameMs, 1000L)
            metricsTracker.pause(finalDur)
            val totalJumps = metricsTracker.jumpCount
            val avgRpm = (totalJumps * 60_000.0 / finalDur).roundToInt()
            val formSnap = kineticTrickCoach.currentFormSnapshot()
            val (hlStart, hlEnd, hlSummary) = computePeakHighlightWindow(jumpTimestamps, finalDur)
            val savedLogPath = videoLogger?.autoSaveToDebugFolder(context)
            val savedLogFile = videoLogger?.exportToFile(context)

            VideoAnalysisResult(
                fileName = fileName,
                durationMs = finalDur,
                jumpCount = totalJumps,
                averageRpm = avgRpm,
                peakRpm = metricsTracker.peakRpm,
                estimatedCalories = metricsTracker.estimatedCalories,
                bestStreak = metricsTracker.bestStreak,
                trips = fusionEngine.totalTrips,
                pllBridged = fusionEngine.totalPllBridged,
                style = fusionEngine.latestStyle,
                fingerprint = fusionEngine.currentFingerprint(),
                hadAudioTrack = hadAudio,
                finalPreviewBitmap = lastDisplayedBitmap,
                sourceUri = uri,
                formScore = formSnap.overallScore,
                formTip = formSnap.coachingTip,
                moveCounts = kineticTrickCoach.snapshotMoveCounts(),
                highlightStartMs = hlStart,
                highlightEndMs = hlEnd,
                highlightSummary = hlSummary,
                savedDebugLogPath = savedLogPath,
                debugLogFile = savedLogFile
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { poseDetector.close() }
        }
    }

    /**
     * O(N) sliding-window scan that locates the densest 10-second Peak-Action highlight segment.
     */
    private fun computePeakHighlightWindow(
        jumpTimestamps: List<Long>,
        totalDurationMs: Long
    ): Triple<Long, Long, String> {
        if (jumpTimestamps.size < 3 || totalDurationMs <= 2000L) {
            return Triple(0L, 0L, "")
        }
        val windowMs = minOf(10_000L, totalDurationMs)
        var left = 0
        var bestCount = 0
        var bestStartMs = 0L

        for (right in jumpTimestamps.indices) {
            while (jumpTimestamps[right] - jumpTimestamps[left] > windowMs) {
                left++
            }
            val count = right - left + 1
            if (count > bestCount) {
                bestCount = count
                bestStartMs = (jumpTimestamps[left] - 600L).coerceAtLeast(0L)
            }
        }
        val bestEndMs = (bestStartMs + windowMs).coerceAtMost(totalDurationMs)
        val actualDurSec = ((bestEndMs - bestStartMs).coerceAtLeast(1000L)) / 1000.0
        val burstRpm = (bestCount * 60.0 / actualDurSec).roundToInt()
        val startFmt = formatShortTime(bestStartMs)
        val endFmt = formatShortTime(bestEndMs)
        return Triple(bestStartMs, bestEndMs, "$startFmt–$endFmt • $bestCount jumps ($burstRpm RPM)")
    }

    private fun formatShortTime(ms: Long): String {
        val sec = (ms / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%d:%02d", sec / 60L, sec % 60L)
    }

    private suspend fun awaitTask(
        task: com.google.android.gms.tasks.Task<Pose>
    ): Pose = suspendCancellableCoroutine { cont ->
        task.addOnSuccessListener { pose ->
            if (cont.isActive) cont.resume(pose)
        }.addOnFailureListener { err ->
            if (cont.isActive) cont.resumeWithException(err)
        }
    }

    /**
     * Universal YUV_420_888 -> NV21 converter with fast bulk row copies for semi-planar hardware decoders.
     */
    private fun yuv420ToNv21(image: Image, out: ByteArray) {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        var pos = 0

        if (yPixelStride == 1 && yRowStride == width) {
            yBuf.position(0)
            yBuf.get(out, 0, width * height)
            pos = width * height
        } else {
            for (row in 0 until height) {
                val rowStart = row * yRowStride
                if (yPixelStride == 1) {
                    yBuf.position(rowStart)
                    yBuf.get(out, pos, width)
                    pos += width
                } else {
                    for (col in 0 until width) {
                        out[pos++] = yBuf.get(rowStart + col * yPixelStride)
                    }
                }
            }
        }

        val chromaH = height / 2
        val chromaW = width / 2
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        // Fast path for NV21 semi-planar (vBuf already has V,U interleaved with stride 2 and no row padding)
        if (vPixelStride == 2 && uPixelStride == 2 && vRowStride == width && uRowStride == width &&
            vBuf.remaining() >= chromaH * width - 1
        ) {
            val vPos0 = 0
            val uPos0 = 0
            // Verify vBuf[1] == uBuf[0] (standard NV21 memory layout)
            if (vBuf.limit() >= 2 && uBuf.limit() >= 1 && vBuf.get(vPos0 + 1) == uBuf.get(uPos0)) {
                val copyLen = (chromaH * width - 1).coerceAtMost(vBuf.limit())
                vBuf.position(0)
                vBuf.get(out, pos, copyLen)
                out[pos + copyLen] = uBuf.get((chromaH - 1) * uRowStride + (chromaW - 1) * uPixelStride)
                return
            }
        }

        for (row in 0 until chromaH) {
            val vRowStart = row * vRowStride
            val uRowStart = row * uRowStride
            for (col in 0 until chromaW) {
                out[pos++] = vBuf.get(vRowStart + col * vPixelStride)
                out[pos++] = uBuf.get(uRowStart + col * uPixelStride)
            }
        }
    }

    /**
     * Ultra-fast direct integer NV21 -> rotated RGB_565 downsampled preview (~1.5ms vs ~35ms JPEG).
     * Reuses [reuseArgb] across frames so there is zero per-frame ByteArrayOutputStream / GC stutter,
     * and uses RGB_565 (half GPU upload bandwidth on low-end phones).
     */
    private fun nv21ToFastPreviewBitmap(
        nv21: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        reuseArgb: IntArray?
    ): Pair<Bitmap?, IntArray?> {
        if (width <= 0 || height <= 0) return null to reuseArgb
        val maxSide = maxOf(width, height)
        val step = (maxSide / 420).coerceAtLeast(1)
        val dw = (width / step).coerceAtLeast(1)
        val dh = (height / step).coerceAtLeast(1)

        val isRotated90or270 = rotationDegrees == 90 || rotationDegrees == 270
        val outW = if (isRotated90or270) dh else dw
        val outH = if (isRotated90or270) dw else dh
        val totalPixels = outW * outH

        val argb = if (reuseArgb != null && reuseArgb.size >= totalPixels) {
            reuseArgb
        } else {
            IntArray(totalPixels)
        }

        val ySize = width * height
        val rot = ((rotationDegrees % 360) + 360) % 360

        for (y in 0 until dh) {
            val sy = y * step
            val yRowOffset = sy * width
            val uvRowOffset = ySize + (sy shr 1) * width
            for (x in 0 until dw) {
                val sx = x * step
                val yVal = (nv21[yRowOffset + sx].toInt() and 0xFF) - 16
                val uvIdx = uvRowOffset + (sx and 1.inv())
                val v = (nv21[uvIdx].toInt() and 0xFF) - 128
                val u = (nv21[uvIdx + 1].toInt() and 0xFF) - 128

                val y1192 = if (yVal > 0) 1192 * yVal else 0
                var r = (y1192 + 1634 * v) shr 10
                var g = (y1192 - 833 * v - 400 * u) shr 10
                var b = (y1192 + 2066 * u) shr 10
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255

                val dstIdx = when (rot) {
                    90 -> x * outW + (dh - 1 - y)
                    180 -> (dh - 1 - y) * outW + (dw - 1 - x)
                    270 -> (dw - 1 - x) * outW + y
                    else -> y * outW + x
                }
                argb[dstIdx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val bmp = runCatching {
            Bitmap.createBitmap(argb, 0, outW, outW, outH, Bitmap.Config.RGB_565)
        }.getOrNull()
        return bmp to argb
    }

    /**
     * Fast zero-boxing primitive FloatArray MP4 audio decoder -> 100 Hz STFT flux.
     */
    private fun decodeMp4AudioToFlux(uri: Uri): List<FluxSample> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(context, uri, null)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || audioFormat == null) return emptyList()

            extractor.selectTrack(audioTrackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return emptyList()
            val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(audioFormat, null, null, 0)
                start()
            }

            // Unlimited practical duration (up to 60 min); primitive FloatArray avoids boxed Float allocations!
            val maxMonoSamples = sampleRate * 3600
            var pcm = FloatArray(minOf(sampleRate * 30, maxMonoSamples))
            var pcmLen = 0
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone && pcmLen < maxMonoSamples) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(5_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        val readSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        if (readSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, readSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 5_000L)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val count = shorts.remaining()
                        val framesInBuf = count / channels
                        if (pcmLen + framesInBuf > pcm.size) {
                            val newCap = minOf(maxMonoSamples, maxOf(pcm.size * 2, pcmLen + framesInBuf))
                            pcm = pcm.copyOf(newCap)
                        }
                        var i = 0
                        while (i + channels <= count && pcmLen < pcm.size) {
                            var sum = 0f
                            for (ch in 0 until channels) {
                                sum += shorts.get(i + ch) * (1f / 32768f)
                            }
                            pcm[pcmLen++] = sum / channels
                            i += channels
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
            }

            if (pcmLen < 1024) return emptyList()
            val trimmed = if (pcmLen == pcm.size) pcm else pcm.copyOf(pcmLen)
            StftAudioEngine().processOfflinePcm(trimmed, sampleRate)
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun Pose.toPoseFrame(timestampMs: Long, w: Int, h: Int): PoseFrame {
        val map = mutableMapOf<BodyLandmark, LandmarkPoint>()
        for ((mlType, bodyType) in LANDMARK_MAP) {
            val lm = getPoseLandmark(mlType) ?: continue
            map[bodyType] = LandmarkPoint(lm.position.x, lm.position.y, lm.inFrameLikelihood)
        }
        return PoseFrame(map, timestampMs, w, h, isMirrored = false)
    }

    companion object {
        private val LANDMARK_MAP = listOf(
            PoseLandmark.NOSE to BodyLandmark.NOSE,
            PoseLandmark.LEFT_SHOULDER to BodyLandmark.LEFT_SHOULDER,
            PoseLandmark.RIGHT_SHOULDER to BodyLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_ELBOW to BodyLandmark.LEFT_ELBOW,
            PoseLandmark.RIGHT_ELBOW to BodyLandmark.RIGHT_ELBOW,
            PoseLandmark.LEFT_WRIST to BodyLandmark.LEFT_WRIST,
            PoseLandmark.RIGHT_WRIST to BodyLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_HIP to BodyLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_HIP to BodyLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_KNEE to BodyLandmark.LEFT_KNEE,
            PoseLandmark.RIGHT_KNEE to BodyLandmark.RIGHT_KNEE,
            PoseLandmark.LEFT_ANKLE to BodyLandmark.LEFT_ANKLE,
            PoseLandmark.RIGHT_ANKLE to BodyLandmark.RIGHT_ANKLE,
            PoseLandmark.LEFT_HEEL to BodyLandmark.LEFT_HEEL,
            PoseLandmark.RIGHT_HEEL to BodyLandmark.RIGHT_HEEL,
            PoseLandmark.LEFT_FOOT_INDEX to BodyLandmark.LEFT_FOOT_INDEX,
            PoseLandmark.RIGHT_FOOT_INDEX to BodyLandmark.RIGHT_FOOT_INDEX
        )
    }
}
