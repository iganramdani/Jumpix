package com.jumpropeai.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
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
import com.jumpropeai.ai.LandmarkPoint
import com.jumpropeai.ai.PoseFrame
import com.jumpropeai.audio.FluxSample
import com.jumpropeai.audio.StftAudioEngine
import com.jumpropeai.metrics.MetricsTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
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
    val finalPreviewBitmap: Bitmap? = null
)

/**
 * Offline MP4 jump rope analyzer powered by Fusion v6 Master Engine.
 * Streams live video frames, AI skeleton PoseFrames, live jump count, RPM, calories,
 * streak, style, and self-learned acoustic fingerprint as the video is processed.
 */
class VideoJumpAnalyzer(private val context: Context) {

    suspend fun analyzeVideo(
        uri: Uri,
        fileName: String,
        onProgress: (VideoAnalysisProgress) -> Unit
    ): VideoAnalysisResult = withContext(Dispatchers.Default) {
        onProgress(VideoAnalysisProgress("Membaca spektrum audio video...", 0.05f, 0))

        // 1. Decode MP4 audio track -> STFT 100 Hz multi-band flux
        val audioFlux = decodeMp4AudioToFlux(uri)
        val hadAudio = audioFlux.size >= 20

        ensureActive()
        onProgress(
            VideoAnalysisProgress(
                stage = if (hadAudio) "Audio STFT siap • Memulai AI pose..." else "Video bisu • Memulai AI pose...",
                fraction = 0.12f,
                currentJumps = 0
            )
        )

        val retriever = MediaMetadataRetriever()
        val poseDetector = PoseDetection.getClient(
            PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build()
        )

        val jumpDetector = JumpDetector()
        val fusionEngine = FusionEngine()
        val metricsTracker = MetricsTracker()
        var lastDisplayedBitmap: Bitmap? = null

        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(1000L) ?: 1000L

            metricsTracker.start(0L)

            // 20 FPS analysis (every 50ms) -> captures fast 200+ RPM skipping cleanly
            val stepMs = 50L
            var currentMs = 0L
            var stepIndex = 0L

            while (currentMs <= durationMs) {
                ensureActive()

                val bitmap = extractScaledFrame(retriever, currentMs * 1000L)
                var latestPoseFrame: PoseFrame? = null
                var isAirborne = false
                var jumpJustCounted = false

                if (bitmap != null) {
                    val pose = detectPoseSuspend(poseDetector, bitmap)
                    val poseFrame = pose.toPoseFrame(currentMs, bitmap.width, bitmap.height)
                    latestPoseFrame = poseFrame

                    val det = jumpDetector.processFrame(poseFrame, isCountingActive = true)
                    isAirborne = det.isAirborne
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
                            }
                        }
                    }
                }

                // Emit live visual frame every 2nd step (10 FPS smooth preview) or immediately on jump!
                val shouldEmitPreview = bitmap != null && (stepIndex % 2L == 0L || jumpJustCounted || currentMs + stepMs > durationMs)
                if (shouldEmitPreview) {
                    lastDisplayedBitmap = bitmap
                    val frac = 0.12f + 0.88f * (currentMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    onProgress(
                        VideoAnalysisProgress(
                            stage = "Menganalisis (${(frac * 100).toInt()}%)",
                            fraction = frac,
                            currentJumps = metricsTracker.jumpCount,
                            currentTimeMs = currentMs,
                            durationMs = durationMs,
                            currentRpm = metricsTracker.getCurrentRpm(currentMs),
                            estimatedCalories = metricsTracker.estimatedCalories,
                            currentStreak = metricsTracker.getActiveStreak(currentMs),
                            isAirborne = isAirborne,
                            style = fusionEngine.latestStyle,
                            fingerprint = fusionEngine.currentFingerprint(),
                            previewBitmap = bitmap,
                            latestPose = latestPoseFrame
                        )
                    )
                } else {
                    // Recycle non-emitted frame immediately to keep memory tiny
                    bitmap?.recycle()
                }

                stepIndex++
                currentMs += stepMs
            }

            metricsTracker.pause(durationMs)
            val totalJumps = metricsTracker.jumpCount
            val avgRpm = if (durationMs >= 1000L) {
                (totalJumps * 60_000.0 / durationMs).roundToInt()
            } else {
                0
            }

            VideoAnalysisResult(
                fileName = fileName,
                durationMs = durationMs,
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
                finalPreviewBitmap = lastDisplayedBitmap
            )
        } finally {
            runCatching { retriever.release() }
            runCatching { poseDetector.close() }
        }
    }

    private fun extractScaledFrame(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    360,
                    640
                )
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            }
        }.getOrNull()
    }

    private suspend fun detectPoseSuspend(
        detector: com.google.mlkit.vision.pose.PoseDetector,
        bitmap: Bitmap
    ): Pose = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { pose ->
                if (cont.isActive) cont.resume(pose)
            }
            .addOnFailureListener { err ->
                if (cont.isActive) cont.resumeWithException(err)
            }
    }

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

            val maxMonoSamples = sampleRate * 180
            val pcmList = ArrayList<Float>(sampleRate * 30)
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone && pcmList.size < maxMonoSamples) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
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

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val count = shorts.remaining()
                        var i = 0
                        while (i + channels <= count && pcmList.size < maxMonoSamples) {
                            var sum = 0f
                            for (ch in 0 until channels) {
                                sum += shorts.get(i + ch) / 32768f
                            }
                            pcmList.add(sum / channels)
                            i += channels
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
            }

            if (pcmList.size < 1024) return emptyList()
            val pcmArray = FloatArray(pcmList.size) { pcmList[it] }
            StftAudioEngine().processOfflinePcm(pcmArray, sampleRate)
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
            PoseLandmark.RIGHT_ANKLE to BodyLandmark.RIGHT_ANKLE
        )
    }
}
