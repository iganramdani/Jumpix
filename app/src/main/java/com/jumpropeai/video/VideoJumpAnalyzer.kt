package com.jumpropeai.video

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioFormat
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
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.jumpropeai.ai.AcousticFingerprint
import com.jumpropeai.ai.BodyLandmark
import com.jumpropeai.ai.FusionEngine
import com.jumpropeai.ai.IjrcSpeedSprintEngine
import com.jumpropeai.ai.IjrcSprintSummary
import com.jumpropeai.ai.JumpDetector
import com.jumpropeai.ai.JumpStyle
import com.jumpropeai.ai.KineticMove
import com.jumpropeai.ai.KineticTrickCoach
import com.jumpropeai.ai.LandmarkPoint
import com.jumpropeai.ai.NeuralVarEngine
import com.jumpropeai.ai.OpticalRopeTracker
import com.jumpropeai.ai.PlyometricAnalyzer
import com.jumpropeai.ai.PlyometricSnapshot
import com.jumpropeai.ai.PoseFrame
import com.jumpropeai.ai.RichTelemetrySeries
import com.jumpropeai.ai.RopeAngularVelocityEngine
import com.jumpropeai.audio.BeatSyncEngine
import com.jumpropeai.audio.FluxSample
import com.jumpropeai.audio.StftAudioEngine
import com.jumpropeai.debug.SessionDiagnosticLogger
import com.jumpropeai.metrics.MetricsTracker
import com.jumpropeai.metrics.RopeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumMap
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

enum class StudioPrecisionTier(val label: String, val badge: String) {
    HEAVY_VAR("Heavy VAR", "🔬 Heavy VAR"),
    FAST_HYBRID("Fast 30fps", "⚡ Fast 30fps")
}

enum class VideoStudioMode(val label: String) {
    AUTO_HYBRID("Auto Hybrid"),
    IJRC_COMPETITION("IJRC Speed"),
    FREESTYLE_TRICKS("Freestyle"),
    SHUFFLE_FOOTWORK("Shuffle")
}

data class VideoTelemetryEvent(
    val timestampMs: Long,
    val jumpNumber: Int,
    val currentRpm: Int,
    val currentStreak: Int,
    val move: KineticMove,
    val calories: Double
)

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
    val detectedBpm: Int = 0,
    val beatSyncScore: Int = 0,
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
    val detectedBpm: Int = 0,
    val beatSyncScore: Int = 0,
    val comboCount: Int = 0,
    val moveCounts: Map<KineticMove, Int> = emptyMap(),
    val telemetryEvents: List<VideoTelemetryEvent> = emptyList(),
    val ijrcSummary: IjrcSprintSummary? = null,
    val telemetrySeries: RichTelemetrySeries = RichTelemetrySeries(),
    val plyometrics: PlyometricSnapshot = PlyometricSnapshot(),
    val poseFrames: List<PoseFrame> = emptyList(),
    val studioMode: VideoStudioMode = VideoStudioMode.AUTO_HYBRID,
    val precisionTier: StudioPrecisionTier = StudioPrecisionTier.HEAVY_VAR,
    val visualPassTimestampsMs: LongArray = LongArray(0),
    val highlightStartMs: Long = 0L,
    val highlightEndMs: Long = 0L,
    val highlightSummary: String = "",
    val savedDebugLogPath: String? = null,
    val debugLogFile: File? = null
)

/**
 * Dual-Tier Hardware-Accelerated Video Studio & Neural VAR Analyzer (AI Engine v17):
 * - `HEAVY_VAR`: ML Kit Accurate Heavy 33-Landmark Pose + Rotation-Aware Sub-Foot Visual Rope Tracker
 *   + Harmonic-Percussive 200 Hz Audio Isolator + Bidirectional Temporal Neural VAR (`var_bitemporal_v7.jvxmodel`).
 * - `FAST_HYBRID`: ML Kit Fast Stream Pose + Causal TCN (`live_causal_tcn_v7.jvxmodel`) for rapid batch queues.
 */
class VideoJumpAnalyzer(private val context: Context) {

    suspend fun analyzeVideo(
        uri: Uri,
        fileName: String,
        enableDebugLog: Boolean = true,
        isShuffleMode: Boolean = false,
        studioMode: VideoStudioMode = if (isShuffleMode) VideoStudioMode.SHUFFLE_FOOTWORK else VideoStudioMode.AUTO_HYBRID,
        precisionTier: StudioPrecisionTier = StudioPrecisionTier.HEAVY_VAR,
        userWeightKg: Double = 65.0,
        ropeType: RopeType = RopeType.SPEED_CABLE,
        onProgress: (VideoAnalysisProgress) -> Unit
    ): VideoAnalysisResult = withContext(Dispatchers.Default) {
        val effectiveShuffle = isShuffleMode || studioMode == VideoStudioMode.SHUFFLE_FOOTWORK
        val useHeavyVar = precisionTier == StudioPrecisionTier.HEAVY_VAR
        onProgress(VideoAnalysisProgress("Scanning 200 Hz HPSS audio spectrum...", 0.04f, 0))

        // 1. O(1)-RAM Streaming Audio Decode -> Anti-aliased 200 Hz (5 ms hop) 5-Band STFT Flux + Buzzer Tones
        val audioFlux200Hz = decodeVideoAudioStreamingToFlux(uri, highRes200Hz = true)
        val audioFlux100Hz = if (audioFlux200Hz.isEmpty()) {
            emptyList()
        } else {
            ArrayList<FluxSample>((audioFlux200Hz.size + 1) / 2).also { list ->
                for (i in audioFlux200Hz.indices step 2) list.add(audioFlux200Hz[i])
            }
        }
        val hadAudio = audioFlux100Hz.size >= 20

        ensureActive()
        onProgress(
            VideoAnalysisProgress(
                stage = if (useHeavyVar) {
                    "Heavy VAR Pose + Rope Vision decoding..."
                } else {
                    "Fast 30fps Hybrid AI decoding..."
                },
                fraction = 0.10f,
                currentJumps = 0
            )
        )

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val poseDetector = if (useHeavyVar) {
            PoseDetection.getClient(
                AccuratePoseDetectorOptions.Builder()
                    .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
                    .build()
            )
        } else {
            PoseDetection.getClient(
                PoseDetectorOptions.Builder()
                    .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                    .build()
            )
        }

        val jumpDetector = JumpDetector()
        val fusionEngine = FusionEngine()
        val kineticTrickCoach = KineticTrickCoach()
        val opticalRopeTracker = OpticalRopeTracker()
        val causalTcn = NeuralVarEngine.CausalJumpTcnLite(context)
        val plyometricAnalyzer = PlyometricAnalyzer()
        val ijrcEngine = IjrcSpeedSprintEngine()
        val beatSyncEngine = BeatSyncEngine()
        val metricsTracker = MetricsTracker(userWeightKg = userWeightKg).apply {
            updateAthleteProfile(userWeightKg, ropeType)
        }
        val videoLogger = if (enableDebugLog) {
            SessionDiagnosticLogger().also {
                it.startSession(
                    context = context,
                    modeLabel = "Video Studio (${studioMode.label} • ${precisionTier.label})",
                    sensorLabel = if (useHeavyVar) "Neural VAR v17 (Heavy Pose + Rope ROI + 200Hz HPSS)" else "Fast Hybrid v17",
                    filePrefix = "Video_${fileName.substringBeforeLast('.')}"
                )
            }
        } else {
            null
        }
        val jumpTimestamps = ArrayList<Long>(512)
        val visualPassTimestamps = ArrayList<Long>(512)
        val telemetryEvents = ArrayList<VideoTelemetryEvent>(512)
        val collectedPoses = ArrayList<PoseFrame>(1024)
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

                    val minFrameStepMs = when (studioMode) {
                        VideoStudioMode.IJRC_COMPETITION -> 14L // Up to 60 FPS native!
                        VideoStudioMode.AUTO_HYBRID -> 24L      // Full 30–40 FPS native (zero 15 FPS aliasing!)
                        else -> 28L                             // Full 30 FPS native
                    }
                    val shouldProcess = !isEos && bufferInfo.size > 0 && (currentMs - lastProcessedMs >= minFrameStepMs)

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

                            codec.releaseOutputBuffer(outIdx, false)

                            val inputImage = InputImage.fromByteBuffer(
                                ByteBuffer.wrap(nv21),
                                imgW,
                                imgH,
                                rotationDegrees,
                                InputImage.IMAGE_FORMAT_NV21
                            )

                            val poseTask = poseDetector.process(inputImage)
                            val nowWallMs = System.nanoTime() / 1_000_000L
                            val wantUiFrame = (nowWallMs - lastUiEmitWallMs >= 45L)
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
                            if (collectedPoses.size < 18_000) {
                                collectedPoses.add(poseFrame)
                            }

                            val aiStartNs = if (videoLogger != null) System.nanoTime() else 0L
                            val det = jumpDetector.processFrame(poseFrame, isCountingActive = true)
                            plyometricAnalyzer.processFrame(timestampMs = currentMs, isAirborne = det.isAirborne)

                            val ropeSignal = if (useHeavyVar) {
                                val sig = opticalRopeTracker.processNv21Frame(
                                    nv21 = nv21,
                                    imgW = imgW,
                                    imgH = imgH,
                                    rotationDegrees = rotationDegrees,
                                    effW = effW,
                                    effH = effH,
                                    poseFrame = poseFrame,
                                    isAirborne = det.isAirborne
                                )
                                if (sig.ropePassConfirmed) {
                                    visualPassTimestamps.add(currentMs)
                                }
                                sig
                            } else {
                                com.jumpropeai.ai.OpticalRopeSignal()
                            }

                            val liveNeural = causalTcn.processLiveFrame(poseFrame, audioFlux100Hz)

                            var acceptedThisFrame = 0
                            val cand = det.candidate
                            if (cand != null) {
                                val decision = fusionEngine.evaluateCandidate(
                                    candidate = cand,
                                    audioHistory = audioFlux100Hz,
                                    useAudioFusion = hadAudio,
                                    neuralVarProb = liveNeural.combinedVarProb,
                                    neuralActiveProb = liveNeural.activeSkippingProb
                                )
                                acceptedThisFrame = decision.acceptedJumps
                            }

                            if (hadAudio && (currentMs / 500L) != (prevProcMs.coerceAtLeast(0L) / 500L)) {
                                beatSyncEngine.updateFromAudio(audioFlux100Hz, currentMs)
                            }

                            val kineticRes = kineticTrickCoach.processFrame(
                                frame = poseFrame,
                                isAirborne = det.isAirborne,
                                jumpAccepted = acceptedThisFrame > 0,
                                jumpAmplitude = cand?.amplitude ?: 0f,
                                audioHistory = audioFlux100Hz,
                                isShuffleMode = effectiveShuffle,
                                jumpPeakTimeMs = cand?.peakTimeMs ?: currentMs,
                                visualRopePassesInFlight = ropeSignal.passesInCurrentFlight
                            )

                            val lastEvMs = jumpTimestamps.lastOrNull() ?: -10_000L
                            val totalMovesThisFrame = when {
                                acceptedThisFrame > 0 -> acceptedThisFrame
                                effectiveShuffle && kineticRes.shuffleStepDetected && (currentMs - lastEvMs) >= 270L -> 1
                                else -> 0
                            }

                            if (totalMovesThisFrame > 0) {
                                val prevEvMs = jumpTimestamps.lastOrNull() ?: 0L
                                val baseEventMs = if (cand != null) {
                                    (cand.peakTimeMs + 65L).coerceIn(prevEvMs + 1L, currentMs.coerceAtLeast(prevEvMs + 1L))
                                } else {
                                    max(currentMs, prevEvMs + 1L)
                                }
                                val spanAvailable = (baseEventMs - prevEvMs).coerceAtLeast(totalMovesThisFrame.toLong())
                                val stepSpacing = (spanAvailable / totalMovesThisFrame).coerceIn(1L, 150L)

                                repeat(totalMovesThisFrame) { idx ->
                                    val rawEvMs = baseEventMs - (totalMovesThisFrame - 1 - idx) * stepSpacing
                                    val monotonicEvMs = max(rawEvMs, (jumpTimestamps.lastOrNull() ?: -1L) + 1L)
                                    metricsTracker.recordJump(monotonicEvMs)
                                    jumpTimestamps.add(monotonicEvMs)
                                    telemetryEvents.add(
                                        VideoTelemetryEvent(
                                            timestampMs = monotonicEvMs,
                                            jumpNumber = metricsTracker.jumpCount,
                                            currentRpm = metricsTracker.getCurrentRpm(monotonicEvMs),
                                            currentStreak = metricsTracker.getActiveStreak(monotonicEvMs),
                                            move = kineticRes.currentMove,
                                            calories = metricsTracker.estimatedCalories
                                        )
                                    )
                                }
                                if (hadAudio) {
                                    beatSyncEngine.recordMoveOnBeat(cand?.peakTimeMs ?: currentMs)
                                }
                            }

                            val beatSnap = beatSyncEngine.snapshot()

                            if (videoLogger != null) {
                                val aiUs = ((System.nanoTime() - aiStartNs) / 1000L).toInt().coerceAtLeast(1)
                                val dtFps = if (prevProcMs >= 0L && currentMs > prevProcMs) {
                                    (1000.0 / (currentMs - prevProcMs)).roundToInt()
                                } else {
                                    30
                                }
                                val rt = Runtime.getRuntime()
                                val heapMb = ((rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)).toInt()
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
                                    vYawR = kineticTrickCoach.lastVYawR,
                                    neuralProb = liveNeural.combinedVarProb,
                                    ropeVisEnergy = if (ropeSignal.ropePassConfirmed) 1f else 0f,
                                    cleanWhipEnergy = liveNeural.cleanWhipEnergy
                                )
                            }

                            if (wantUiFrame || totalMovesThisFrame > 0) {
                                if (previewBmp != null) {
                                    lastDisplayedBitmap = previewBmp
                                }
                                lastUiEmitWallMs = nowWallMs
                                val frac = 0.10f + 0.85f * (currentMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                                onProgress(
                                    VideoAnalysisProgress(
                                        stage = "Analyzing @ Full FPS (${(frac * 100).toInt()}%)",
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
                                        detectedBpm = beatSnap.detectedBpm,
                                        beatSyncScore = beatSnap.beatSyncScore,
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

            // Run AI Engine v16 Specialist IJRC Speed-Sprint & Rope Velocity Pass!
            val runIjrcCheck = studioMode == VideoStudioMode.IJRC_COMPETITION || studioMode == VideoStudioMode.AUTO_HYBRID
            val ijrcSummary = if (runIjrcCheck && !effectiveShuffle) {
                ijrcEngine.analyzeOfflineSession(
                    poseFrames = collectedPoses,
                    audioFlux200Hz = audioFlux200Hz,
                    forceCompetitionMode = (studioMode == VideoStudioMode.IJRC_COMPETITION)
                )
            } else {
                null
            }

            val useIjrcResult = ijrcSummary != null && ijrcSummary.isCompetitionSprintDetected &&
                (studioMode == VideoStudioMode.IJRC_COMPETITION ||
                    ((!jumpDetector.hadSpeedSprint || ijrcSummary.buzzerWindow.detected) &&
                        ijrcSummary.totalAlternatingSteps > metricsTracker.jumpCount * 1.30))

            if (useIjrcResult && ijrcSummary.stepEvents.isNotEmpty()) {
                metricsTracker.reset()
                metricsTracker.start(0L)
                jumpTimestamps.clear()
                telemetryEvents.clear()
                for (se in ijrcSummary.stepEvents) {
                    metricsTracker.recordJump(se.timestampMs)
                    jumpTimestamps.add(se.timestampMs)
                    telemetryEvents.add(
                        VideoTelemetryEvent(
                            timestampMs = se.timestampMs,
                            jumpNumber = se.stepNumber,
                            currentRpm = se.instantStepSpm,
                            currentStreak = metricsTracker.getActiveStreak(se.timestampMs),
                            move = KineticMove.BOXER_STEP,
                            calories = metricsTracker.estimatedCalories
                        )
                    )
                }
                metricsTracker.pause(finalDur)
            }

            val totalJumps = metricsTracker.jumpCount
            // Active-window Average RPM: prevents judge pre-start & celebration silence from cutting competition SPM in half!
            val activeSpanMs = when {
                useIjrcResult && ijrcSummary.activeSprintDurationMs >= 1_000L ->
                    ijrcSummary.activeSprintDurationMs
                jumpTimestamps.size >= 4 ->
                    (jumpTimestamps.last() - jumpTimestamps.first() + 500L).coerceIn(1_000L, finalDur)
                else -> finalDur
            }
            val avgRpm = if (totalJumps > 0) (totalJumps * 60_000.0 / activeSpanMs).roundToInt() else 0
            val peakRpm = if (useIjrcResult) {
                max(metricsTracker.peakRpm, ijrcSummary.peakStepSpm)
            } else {
                metricsTracker.peakRpm
            }
            val finalMoveCounts = if (useIjrcResult) {
                mapOf(KineticMove.BOXER_STEP to ijrcSummary.totalAlternatingSteps)
            } else {
                kineticTrickCoach.snapshotMoveCounts()
            }

            val biNeuralPreds = if (useHeavyVar && collectedPoses.isNotEmpty()) {
                NeuralVarEngine.evaluateOfflineSequence(collectedPoses, audioFlux200Hz, context)
            } else {
                null
            }
            if (!useIjrcResult && !effectiveShuffle && !jumpDetector.hadSpeedSprint && biNeuralPreds != null) {
                val rescuedTimes = NeuralVarEngine.rescueMissedRhythmicJumps(collectedPoses, biNeuralPreds, jumpTimestamps)
                if (rescuedTimes.isNotEmpty()) {
                    val merged = (jumpTimestamps + rescuedTimes).sorted()
                    metricsTracker.reset()
                    metricsTracker.start(0L)
                    jumpTimestamps.clear()
                    val oldByTime = telemetryEvents.associateBy { it.timestampMs }
                    telemetryEvents.clear()
                    for (tEv in merged) {
                        metricsTracker.recordJump(tEv)
                        jumpTimestamps.add(tEv)
                        val prevEv = oldByTime[tEv]
                        telemetryEvents.add(
                            VideoTelemetryEvent(
                                timestampMs = tEv,
                                jumpNumber = metricsTracker.jumpCount,
                                currentRpm = metricsTracker.getCurrentRpm(tEv),
                                currentStreak = metricsTracker.getActiveStreak(tEv),
                                move = prevEv?.move ?: KineticMove.REGULAR,
                                calories = metricsTracker.estimatedCalories
                            )
                        )
                    }
                    metricsTracker.pause(finalDur)
                }
            }
            val totalJumpsFinal = metricsTracker.jumpCount
            val avgRpmFinal = if (totalJumpsFinal > 0) (totalJumpsFinal * 60_000.0 / activeSpanMs).roundToInt() else 0
            val visPassArr = visualPassTimestamps.toLongArray()

            val richSeries = RopeAngularVelocityEngine.buildTelemetrySeries(
                durationMs = finalDur,
                poseFrames = collectedPoses,
                audioFlux = audioFlux200Hz,
                telemetryEvents = telemetryEvents,
                ijrcSummary = if (useIjrcResult) ijrcSummary else null,
                neuralPredictions = biNeuralPreds,
                visualPassTimestampsMs = visPassArr
            )

            val formSnap = kineticTrickCoach.currentFormSnapshot()
            val finalBeatSnap = beatSyncEngine.snapshot()
            val (hlStart, hlEnd, hlSummary) = computePeakHighlightWindow(jumpTimestamps, finalDur)
            val activeEngineName = when {
                useIjrcResult -> "IJRC-VAR-v17"
                effectiveShuffle -> "Shuffle-VAR-v17"
                useHeavyVar -> "Heavy-VAR-v17"
                else -> "Fast-Hybrid-v17"
            }
            val buzzerLabel = if (useIjrcResult && ijrcSummary.buzzerWindow.detected) {
                String.format(
                    Locale.US,
                    "%.2fs-%.2fs",
                    ijrcSummary.buzzerWindow.startBuzzerMs / 1000.0,
                    ijrcSummary.buzzerWindow.endBuzzerMs / 1000.0
                )
            } else {
                "None"
            }
            val finalTrips = if (useIjrcResult) ijrcSummary.detectedTrips else fusionEngine.totalTrips
            videoLogger?.recordFinalSummary(
                activeEngine = activeEngineName,
                finalJumps = totalJumpsFinal,
                avgRpm = avgRpmFinal,
                peakRpm = peakRpm,
                rightFootJudgeScore = if (useIjrcResult) ijrcSummary.rightFootJudgeScore else 0,
                buzzerWindowLabel = buzzerLabel,
                trips = finalTrips,
                visualRopePasses = visPassArr.size,
                neuralConfPct = richSeries.meanNeuralConfidencePct
            )
            val savedLogPath = videoLogger?.autoSaveToDebugFolder(context)
            val savedLogFile = videoLogger?.exportToFile(context)

            VideoAnalysisResult(
                fileName = fileName,
                durationMs = finalDur,
                jumpCount = totalJumpsFinal,
                averageRpm = avgRpmFinal,
                peakRpm = peakRpm,
                estimatedCalories = metricsTracker.estimatedCalories,
                bestStreak = metricsTracker.bestStreak,
                trips = if (useIjrcResult) ijrcSummary.detectedTrips else fusionEngine.totalTrips,
                pllBridged = fusionEngine.totalPllBridged,
                style = if (useIjrcResult) JumpStyle.BOXER_STEP else fusionEngine.latestStyle,
                fingerprint = fusionEngine.currentFingerprint(),
                hadAudioTrack = hadAudio,
                finalPreviewBitmap = lastDisplayedBitmap,
                sourceUri = uri,
                formScore = formSnap.overallScore,
                formTip = formSnap.coachingTip,
                detectedBpm = if (useIjrcResult) 0 else finalBeatSnap.detectedBpm,
                beatSyncScore = if (useIjrcResult) 0 else finalBeatSnap.beatSyncScore,
                comboCount = if (useIjrcResult) 0 else kineticTrickCoach.comboCount,
                moveCounts = finalMoveCounts,
                telemetryEvents = telemetryEvents,
                ijrcSummary = if (useIjrcResult) ijrcSummary else null,
                telemetrySeries = richSeries,
                plyometrics = plyometricAnalyzer.snapshot(),
                poseFrames = collectedPoses,
                studioMode = studioMode,
                precisionTier = precisionTier,
                visualPassTimestampsMs = visPassArr,
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

        if (vPixelStride == 2 && uPixelStride == 2 && vRowStride == width && uRowStride == width &&
            vBuf.remaining() >= chromaH * width - 1
        ) {
            if (vBuf.limit() >= 2 && uBuf.limit() >= 1 && vBuf.get(1) == uBuf.get(0)) {
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
     * O(1)-RAM Streaming Audio Decoder -> Anti-Aliased 100 Hz STFT Flux.
     * Supports both `ENCODING_PCM_16BIT` and `ENCODING_PCM_FLOAT` decoders without allocating full-audio arrays!
     */
    private fun decodeVideoAudioStreamingToFlux(
        uri: Uri,
        highRes200Hz: Boolean = false
    ): List<FluxSample> {
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
            var sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var pcmEncoding = if (audioFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                audioFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(audioFormat, null, null, 0)
                start()
            }

            val stftEngine = StftAudioEngine()
            var streamSession = stftEngine.StreamingOfflineSession(sampleRate, highRes200Hz = highRes200Hz)
            var monoChunk = FloatArray(8192)
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var totalMonoFrames = 0L
            val maxFramesCap = sampleRate.toLong() * 7200L // up to 2 hours safety cap

            while (!outputDone && totalMonoFrames < maxFramesCap) {
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
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFmt = codec.outputFormat
                    if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        val newSr = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (newSr != sampleRate && totalMonoFrames == 0L) {
                            sampleRate = newSr
                            streamSession = stftEngine.StreamingOfflineSession(sampleRate, highRes200Hz = highRes200Hz)
                        }
                    }
                    if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    }
                    if (newFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = newFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                } else if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val floats = outBuf.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                            val count = floats.remaining()
                            val framesInBuf = count / channels
                            if (monoChunk.size < framesInBuf) monoChunk = FloatArray(framesInBuf * 2)
                            var mLen = 0
                            var i = 0
                            while (i + channels <= count) {
                                var sum = 0f
                                for (ch in 0 until channels) {
                                    sum += floats.get(i + ch).coerceIn(-1f, 1f)
                                }
                                monoChunk[mLen++] = sum / channels
                                i += channels
                            }
                            streamSession.feedMonoChunk(monoChunk, mLen)
                            totalMonoFrames += mLen
                        } else {
                            val shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val count = shorts.remaining()
                            val framesInBuf = count / channels
                            if (monoChunk.size < framesInBuf) monoChunk = FloatArray(framesInBuf * 2)
                            var mLen = 0
                            var i = 0
                            while (i + channels <= count) {
                                var sum = 0f
                                for (ch in 0 until channels) {
                                    sum += shorts.get(i + ch) * (1f / 32768f)
                                }
                                monoChunk[mLen++] = sum / channels
                                i += channels
                            }
                            streamSession.feedMonoChunk(monoChunk, mLen)
                            totalMonoFrames += mLen
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
            }

            if (totalMonoFrames < 1024L) emptyList() else streamSession.finish()
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun Pose.toPoseFrame(timestampMs: Long, w: Int, h: Int): PoseFrame {
        val map = EnumMap<BodyLandmark, LandmarkPoint>(BodyLandmark::class.java)
        for ((mlType, bodyType) in LANDMARK_MAP) {
            val lm = getPoseLandmark(mlType) ?: continue
            map[bodyType] = LandmarkPoint(
                x = lm.position.x,
                y = lm.position.y,
                confidence = lm.inFrameLikelihood,
                z = lm.position3D.z
            )
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
