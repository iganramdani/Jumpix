package com.jumpropeai.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jumpropeai.ai.AcousticFingerprint
import com.jumpropeai.ai.FusionEngine
import com.jumpropeai.ai.JumpDetector
import com.jumpropeai.ai.JumpStyle
import com.jumpropeai.ai.PoseFrame
import com.jumpropeai.audio.AudioCue
import com.jumpropeai.audio.StftAudioEngine
import com.jumpropeai.data.CumulativeStats
import com.jumpropeai.data.WorkoutHistoryRepository
import com.jumpropeai.data.WorkoutSession
import com.jumpropeai.metrics.MetricsTracker
import com.jumpropeai.metrics.WorkoutMetrics
import com.jumpropeai.video.VideoAnalysisProgress
import com.jumpropeai.video.VideoAnalysisResult
import com.jumpropeai.video.VideoJumpAnalyzer
import com.jumpropeai.workout.HiitPhase
import com.jumpropeai.workout.WorkoutConfig
import com.jumpropeai.workout.WorkoutModeEvaluator
import com.jumpropeai.workout.WorkoutModeType
import com.jumpropeai.workout.WorkoutProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

enum class SensorMode(val label: String) {
    FUSION("Fusion AI"),
    AUDIO_ONLY("Audio Eco"),
    CAMERA_ONLY("Kamera")
}

data class JumpRopeUiState(
    val isSessionRunning: Boolean = false,
    val lensFacing: Int = CameraSelector.LENS_FACING_FRONT,
    val sensorMode: SensorMode = SensorMode.FUSION,
    val hasAudioPermission: Boolean = false,
    val showSkeletonOverlay: Boolean = false,
    val isAudioEnabled: Boolean = true,
    val isEcoMode: Boolean = false,
    val isControlsVisible: Boolean = true,
    val recommendEcoLowFps: Boolean = false,
    val latestPose: PoseFrame? = null,
    val metrics: WorkoutMetrics = WorkoutMetrics(),
    val jumpStyle: JumpStyle = JumpStyle.REGULAR,
    val fingerprint: AcousticFingerprint = AcousticFingerprint(),
    val tripsCount: Int = 0,
    val workoutConfig: WorkoutConfig = WorkoutConfig(),
    val workoutProgress: WorkoutProgress = WorkoutProgress(),
    val completedSessionSummary: WorkoutSession? = null,
    val historySessions: List<WorkoutSession> = emptyList(),
    val cumulativeStats: CumulativeStats = CumulativeStats(),
    val showHistorySheet: Boolean = false,
    val showModeSheet: Boolean = false,
    // MP4 Video Analysis state
    val showVideoSheet: Boolean = false,
    val isAnalyzingVideo: Boolean = false,
    val videoProgress: VideoAnalysisProgress? = null,
    val videoResult: VideoAnalysisResult? = null,
    val videoError: String? = null
) {
    val isCameraActive: Boolean
        get() = sensorMode != SensorMode.AUDIO_ONLY && !showVideoSheet

    val hasActiveProgress: Boolean
        get() = metrics.jumpCount > 0 || metrics.elapsedMs >= 1000L
}

class JumpRopeViewModel(application: Application) : AndroidViewModel(application) {

    private val jumpDetector = JumpDetector()
    private val fusionEngine = FusionEngine()
    private val stftAudioEngine = StftAudioEngine()
    private val metricsTracker = MetricsTracker()
    private val videoAnalyzer = VideoJumpAnalyzer(application.applicationContext)
    private val historyRepository = WorkoutHistoryRepository(
        File(application.filesDir, "workout_history.json")
    )

    private val _uiState = MutableStateFlow(JumpRopeUiState())
    val uiState: StateFlow<JumpRopeUiState> = _uiState.asStateFlow()

    private val _audioCues = MutableSharedFlow<AudioCue>(extraBufferCapacity = 16)
    val audioCues: SharedFlow<AudioCue> = _audioCues.asSharedFlow()

    private var tickerJob: Job? = null
    private var videoJob: Job? = null
    private var lastHiitPhase: HiitPhase? = null
    private var lastCountdownSecond: Int = -1
    private var activeSessionId: String? = null
    private var activeSessionStartEpochMs: Long = 0L
    private var lastAutoSavedJumpCount: Int = 0

    init {
        val loaded = historyRepository.loadSessions()
        _uiState.update {
            it.copy(
                historySessions = loaded,
                cumulativeStats = WorkoutHistoryRepository.computeStats(loaded)
            )
        }
    }

    fun onAudioPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(hasAudioPermission = granted) }
        syncAudioCapture()
    }

    fun notifySpeakerPlayed(durationMs: Long = 220L) {
        stftAudioEngine.notifySpeakerOutput(durationMs)
    }

    fun cycleSensorMode() {
        val next = when (_uiState.value.sensorMode) {
            SensorMode.FUSION -> SensorMode.AUDIO_ONLY
            SensorMode.AUDIO_ONLY -> SensorMode.CAMERA_ONLY
            SensorMode.CAMERA_ONLY -> SensorMode.FUSION
        }
        _uiState.update {
            it.copy(
                sensorMode = next,
                latestPose = if (next == SensorMode.AUDIO_ONLY) null else it.latestPose,
                isEcoMode = if (next == SensorMode.AUDIO_ONLY) false else it.isEcoMode
            )
        }
        syncAudioCapture()
    }

    fun onPoseFrame(frame: PoseFrame) {
        val currentState = _uiState.value
        if (currentState.sensorMode == SensorMode.AUDIO_ONLY) return

        val isCountingAllowed = currentState.isSessionRunning && currentState.workoutProgress.isCountingAllowed
        val result = jumpDetector.processFrame(frame, isCountingActive = isCountingAllowed)

        var jumpsAccepted = 0
        var latestStyle = currentState.jumpStyle
        var latestFingerprint = currentState.fingerprint
        var recommendLowFps = currentState.recommendEcoLowFps

        val candidate = result.candidate
        if (candidate != null && isCountingAllowed) {
            if (currentState.sensorMode == SensorMode.FUSION) {
                val useAudio = currentState.hasAudioPermission
                val decision = fusionEngine.evaluateCandidate(
                    candidate = candidate,
                    audioHistory = if (useAudio) stftAudioEngine.snapshotHistory() else emptyList(),
                    useAudioFusion = useAudio
                )
                jumpsAccepted = decision.acceptedJumps
                latestStyle = decision.jumpStyle
                latestFingerprint = decision.fingerprint
                recommendLowFps = decision.recommendEcoLowFps
            } else if (result.jumpDetected) {
                jumpsAccepted = 1
            }
        }

        if (jumpsAccepted > 0 && isCountingAllowed) {
            repeat(jumpsAccepted) {
                metricsTracker.recordJump(frame.timestampMs)
            }
            emitJumpCue(metricsTracker.jumpCount)
        }

        val needsSkeletonRender = currentState.showSkeletonOverlay && !currentState.isEcoMode
        val prevMetrics = currentState.metrics
        val stateChanged = jumpsAccepted > 0 ||
            result.isAirborne != prevMetrics.isAirborne ||
            result.status != prevMetrics.detectorStatus ||
            result.calibrationProgress != prevMetrics.calibrationProgress

        if (!needsSkeletonRender && !stateChanged) {
            return
        }

        val nowMs = frame.timestampMs
        val elapsedMs = metricsTracker.getElapsedMs(nowMs)
        val progress = WorkoutModeEvaluator.evaluate(
            config = currentState.workoutConfig,
            jumpCount = metricsTracker.jumpCount,
            elapsedMs = elapsedMs
        )

        val justCompleted = currentState.isSessionRunning && progress.isCompleted

        _uiState.update { state ->
            state.copy(
                latestPose = if (needsSkeletonRender) frame else null,
                workoutProgress = progress,
                jumpStyle = latestStyle,
                fingerprint = latestFingerprint,
                tripsCount = fusionEngine.totalTrips,
                recommendEcoLowFps = recommendLowFps,
                metrics = state.metrics.copy(
                    jumpCount = metricsTracker.jumpCount,
                    elapsedMs = elapsedMs,
                    currentRpm = metricsTracker.getCurrentRpm(nowMs),
                    peakRpm = metricsTracker.peakRpm,
                    estimatedCalories = metricsTracker.estimatedCalories,
                    currentStreak = metricsTracker.getActiveStreak(nowMs),
                    bestStreak = metricsTracker.bestStreak,
                    isAirborne = result.isAirborne,
                    detectorStatus = result.status,
                    calibrationProgress = result.calibrationProgress
                )
            )
        }

        if (justCompleted) {
            finishAndSaveSession(completedByTarget = true)
        }
    }

    private fun onAudioOnlyJump(timestampMs: Long) {
        val currentState = _uiState.value
        if (currentState.sensorMode != SensorMode.AUDIO_ONLY) return
        val isCountingAllowed = currentState.isSessionRunning && currentState.workoutProgress.isCountingAllowed
        if (!isCountingAllowed) return

        metricsTracker.recordJump(timestampMs)
        emitJumpCue(metricsTracker.jumpCount)

        val elapsedMs = metricsTracker.getElapsedMs(timestampMs)
        val progress = WorkoutModeEvaluator.evaluate(
            config = currentState.workoutConfig,
            jumpCount = metricsTracker.jumpCount,
            elapsedMs = elapsedMs
        )

        _uiState.update { state ->
            state.copy(
                workoutProgress = progress,
                metrics = state.metrics.copy(
                    jumpCount = metricsTracker.jumpCount,
                    elapsedMs = elapsedMs,
                    currentRpm = metricsTracker.getCurrentRpm(timestampMs),
                    peakRpm = metricsTracker.peakRpm,
                    estimatedCalories = metricsTracker.estimatedCalories,
                    currentStreak = metricsTracker.getActiveStreak(timestampMs),
                    bestStreak = metricsTracker.bestStreak
                )
            )
        }

        if (progress.isCompleted) {
            finishAndSaveSession(completedByTarget = true)
        }
    }

    private fun emitJumpCue(count: Int) {
        if (count % 10 == 0) {
            autoSaveIfNeeded()
        }
        if (count % 25 == 0) {
            notifySpeakerPlayed(900L)
            _audioCues.tryEmit(AudioCue.Milestone(count))
        } else {
            notifySpeakerPlayed(160L)
            _audioCues.tryEmit(AudioCue.JumpTick)
        }
    }

    fun toggleSession() {
        if (_uiState.value.isSessionRunning) pauseSession() else startSession()
    }

    fun startSession() {
        val nowMs = SystemClock.elapsedRealtime()
        val isFreshStart = metricsTracker.getElapsedMs(nowMs) == 0L
        metricsTracker.start(nowMs)
        lastCountdownSecond = -1

        val config = _uiState.value.workoutConfig
        if (isFreshStart) {
            lastHiitPhase = HiitPhase.WORK
            notifySpeakerPlayed(700L)
            if (config.mode == WorkoutModeType.HIIT) {
                _audioCues.tryEmit(AudioCue.HiitWorkStart(1))
            } else {
                _audioCues.tryEmit(AudioCue.SessionStart)
            }
        }

        _uiState.update {
            it.copy(
                isSessionRunning = true,
                isControlsVisible = false,
                completedSessionSummary = null
            )
        }
        syncAudioCapture()
        startTicker()
    }

    fun autoSaveIfNeeded(nowMs: Long = SystemClock.elapsedRealtime()) {
        val totalJumps = metricsTracker.jumpCount
        val durationMs = metricsTracker.getElapsedMs(nowMs)
        if (totalJumps == 0 || totalJumps == lastAutoSavedJumpCount) return

        val sessionId = activeSessionId ?: UUID.randomUUID().toString().also {
            activeSessionId = it
            activeSessionStartEpochMs = System.currentTimeMillis()
        }
        val avgRpm = if (durationMs >= 1000L) {
            (totalJumps * 60_000.0 / durationMs).roundToInt()
        } else {
            0
        }
        val config = _uiState.value.workoutConfig
        val session = WorkoutSession(
            id = sessionId,
            timestampMillis = activeSessionStartEpochMs,
            modeLabel = "${config.shortLabel()} • ${_uiState.value.sensorMode.label}",
            modeType = config.mode,
            jumpCount = totalJumps,
            durationMs = durationMs,
            averageRpm = avgRpm,
            peakRpm = metricsTracker.peakRpm,
            estimatedCalories = metricsTracker.estimatedCalories,
            bestStreak = metricsTracker.bestStreak
        )
        val updated = historyRepository.upsertSession(session)
        lastAutoSavedJumpCount = totalJumps
        _uiState.update {
            it.copy(
                historySessions = updated,
                cumulativeStats = WorkoutHistoryRepository.computeStats(updated)
            )
        }
    }

    fun pauseSession() {
        val nowMs = SystemClock.elapsedRealtime()
        metricsTracker.pause(nowMs)
        tickerJob?.cancel()
        tickerJob = null
        stftAudioEngine.stopLiveCapture()
        autoSaveIfNeeded(nowMs)
        _uiState.update { state ->
            state.copy(
                isSessionRunning = false,
                isControlsVisible = true,
                recommendEcoLowFps = false,
                metrics = state.metrics.copy(
                    elapsedMs = metricsTracker.getElapsedMs(nowMs),
                    currentRpm = 0,
                    isAirborne = false
                )
            )
        }
    }

    fun finishAndSaveSession(completedByTarget: Boolean = false) {
        val nowMs = SystemClock.elapsedRealtime()
        metricsTracker.pause(nowMs)
        tickerJob?.cancel()
        tickerJob = null
        stftAudioEngine.stopLiveCapture()

        val durationMs = metricsTracker.getElapsedMs(nowMs)
        val totalJumps = metricsTracker.jumpCount
        if (totalJumps == 0 && durationMs < 1000L) {
            resetSession()
            return
        }

        val avgRpm = if (durationMs >= 1000L) {
            (totalJumps * 60_000.0 / durationMs).roundToInt()
        } else {
            0
        }

        val config = _uiState.value.workoutConfig
        val sessionId = activeSessionId ?: UUID.randomUUID().toString()
        val sessionTs = if (activeSessionStartEpochMs > 0L) activeSessionStartEpochMs else System.currentTimeMillis()
        val session = WorkoutSession(
            id = sessionId,
            timestampMillis = sessionTs,
            modeLabel = "${config.shortLabel()} • ${_uiState.value.sensorMode.label}",
            modeType = config.mode,
            jumpCount = totalJumps,
            durationMs = durationMs,
            averageRpm = avgRpm,
            peakRpm = metricsTracker.peakRpm,
            estimatedCalories = metricsTracker.estimatedCalories,
            bestStreak = metricsTracker.bestStreak
        )

        val updatedHistory = historyRepository.upsertSession(session)
        if (completedByTarget) {
            notifySpeakerPlayed(1500L)
            _audioCues.tryEmit(AudioCue.WorkoutCompleted(totalJumps))
        }

        jumpDetector.reset()
        fusionEngine.reset()
        stftAudioEngine.reset()
        metricsTracker.reset()
        activeSessionId = null
        activeSessionStartEpochMs = 0L
        lastAutoSavedJumpCount = 0
        lastHiitPhase = null
        lastCountdownSecond = -1

        _uiState.update { state ->
            state.copy(
                isSessionRunning = false,
                isControlsVisible = true,
                isEcoMode = false,
                recommendEcoLowFps = false,
                tripsCount = 0,
                fingerprint = AcousticFingerprint(),
                metrics = WorkoutMetrics(),
                workoutProgress = WorkoutProgress(),
                completedSessionSummary = session,
                historySessions = updatedHistory,
                cumulativeStats = WorkoutHistoryRepository.computeStats(updatedHistory)
            )
        }
    }

    fun resetSession() {
        // Auto-save before reset if user had >= 5 jumps so accidental reset never loses data
        if (metricsTracker.jumpCount >= 5) {
            autoSaveIfNeeded()
        }
        tickerJob?.cancel()
        tickerJob = null
        stftAudioEngine.stopLiveCapture()
        jumpDetector.reset()
        fusionEngine.reset()
        stftAudioEngine.reset()
        metricsTracker.reset()
        activeSessionId = null
        activeSessionStartEpochMs = 0L
        lastAutoSavedJumpCount = 0
        lastHiitPhase = null
        lastCountdownSecond = -1
        _uiState.update { state ->
            state.copy(
                isSessionRunning = false,
                isControlsVisible = true,
                recommendEcoLowFps = false,
                tripsCount = 0,
                fingerprint = AcousticFingerprint(),
                metrics = WorkoutMetrics(),
                workoutProgress = WorkoutProgress()
            )
        }
    }

    // --- Video Analysis ---

    fun openVideoSheet() {
        if (_uiState.value.isSessionRunning) pauseSession()
        _uiState.update {
            it.copy(
                showVideoSheet = true,
                videoError = null
            )
        }
    }

    fun closeVideoSheet() {
        videoJob?.cancel()
        videoJob = null
        _uiState.update {
            it.copy(
                showVideoSheet = false,
                isAnalyzingVideo = false,
                videoProgress = null
            )
        }
    }

    fun analyzeMp4Video(uri: Uri, fileName: String) {
        videoJob?.cancel()
        _uiState.update {
            it.copy(
                showVideoSheet = true,
                isAnalyzingVideo = true,
                videoResult = null,
                videoError = null,
                videoProgress = VideoAnalysisProgress("Memulai analisis...", 0.02f, 0)
            )
        }

        videoJob = viewModelScope.launch {
            try {
                val result = videoAnalyzer.analyzeVideo(uri, fileName) { prog ->
                    _uiState.update { it.copy(videoProgress = prog) }
                }
                // Auto-save analyzed video session to history immediately so user never forgets!
                var updatedHistory = _uiState.value.historySessions
                if (result.jumpCount > 0) {
                    val autoSession = WorkoutSession(
                        id = UUID.randomUUID().toString(),
                        timestampMillis = System.currentTimeMillis(),
                        modeLabel = "Video • ${result.style.label}",
                        modeType = WorkoutModeType.FREE,
                        jumpCount = result.jumpCount,
                        durationMs = result.durationMs,
                        averageRpm = result.averageRpm,
                        peakRpm = result.peakRpm,
                        estimatedCalories = result.estimatedCalories,
                        bestStreak = result.bestStreak
                    )
                    updatedHistory = historyRepository.upsertSession(autoSession)
                }
                _uiState.update {
                    it.copy(
                        isAnalyzingVideo = false,
                        videoResult = result,
                        videoProgress = null,
                        historySessions = updatedHistory,
                        cumulativeStats = WorkoutHistoryRepository.computeStats(updatedHistory)
                    )
                }
            } catch (_: CancellationException) {
                // Cancelled cleanly when sheet dismissed
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isAnalyzingVideo = false,
                        videoError = "Gagal membaca video: ${e.localizedMessage ?: "format tidak didukung"}"
                    )
                }
            }
        }
    }

    fun saveVideoResultToHistory(result: VideoAnalysisResult) {
        // Already auto-saved on completion; close cleanly
        _uiState.update {
            it.copy(
                showVideoSheet = false,
                videoResult = null
            )
        }
    }

    fun updateWorkoutConfig(newConfig: WorkoutConfig) {
        resetSession()
        _uiState.update { it.copy(workoutConfig = newConfig, showModeSheet = false) }
    }

    fun toggleCamera() {
        jumpDetector.reset()
        _uiState.update { state ->
            val nextLens = if (state.lensFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            state.copy(lensFacing = nextLens, latestPose = null)
        }
    }

    fun toggleSkeletonOverlay() {
        _uiState.update { state ->
            val next = !state.showSkeletonOverlay
            state.copy(showSkeletonOverlay = next, latestPose = if (next) state.latestPose else null)
        }
    }

    fun toggleAudio() {
        _uiState.update { it.copy(isAudioEnabled = !it.isAudioEnabled) }
    }

    fun toggleEcoMode() {
        _uiState.update { state ->
            val nextEco = !state.isEcoMode
            state.copy(isEcoMode = nextEco, latestPose = if (nextEco) null else state.latestPose)
        }
    }

    fun toggleControlsVisibility() {
        _uiState.update { state ->
            if (!state.isSessionRunning) state else state.copy(isControlsVisible = !state.isControlsVisible)
        }
    }

    fun setShowHistorySheet(show: Boolean) {
        _uiState.update { it.copy(showHistorySheet = show) }
    }

    fun setShowModeSheet(show: Boolean) {
        _uiState.update { it.copy(showModeSheet = show) }
    }

    fun dismissSummaryDialog() {
        _uiState.update { it.copy(completedSessionSummary = null) }
    }

    fun deleteHistorySession(sessionId: String) {
        val updated = historyRepository.deleteSession(sessionId)
        _uiState.update {
            it.copy(
                historySessions = updated,
                cumulativeStats = WorkoutHistoryRepository.computeStats(updated)
            )
        }
    }

    private fun syncAudioCapture() {
        val state = _uiState.value
        val needsMic = state.isSessionRunning &&
            state.hasAudioPermission &&
            (state.sensorMode == SensorMode.FUSION || state.sensorMode == SensorMode.AUDIO_ONLY)

        if (needsMic) {
            val audioOnlyCallback: ((Long) -> Unit)? = if (state.sensorMode == SensorMode.AUDIO_ONLY) {
                { ts -> onAudioOnlyJump(ts) }
            } else {
                null
            }
            stftAudioEngine.startLiveCapture(onAudioOnlyJump = audioOnlyCallback)
        } else {
            stftAudioEngine.stopLiveCapture()
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(500L)
                val currentState = _uiState.value
                if (!currentState.isSessionRunning) continue

                val nowMs = SystemClock.elapsedRealtime()
                val elapsedMs = metricsTracker.getElapsedMs(nowMs)
                val progress = WorkoutModeEvaluator.evaluate(
                    config = currentState.workoutConfig,
                    jumpCount = metricsTracker.jumpCount,
                    elapsedMs = elapsedMs
                )

                if (currentState.workoutConfig.mode == WorkoutModeType.HIIT && !progress.isCompleted) {
                    if (lastHiitPhase != null && progress.hiitPhase != lastHiitPhase) {
                        notifySpeakerPlayed(800L)
                        if (progress.hiitPhase == HiitPhase.REST) {
                            _audioCues.tryEmit(AudioCue.HiitRestStart)
                        } else {
                            _audioCues.tryEmit(AudioCue.HiitWorkStart(progress.currentRound))
                        }
                    }
                    lastHiitPhase = progress.hiitPhase
                }

                progress.remainingMs?.let { remMs ->
                    val remSec = ((remMs + 999L) / 1000L).toInt()
                    if (remSec in 1..3 && remSec != lastCountdownSecond && !progress.isCompleted) {
                        lastCountdownSecond = remSec
                        notifySpeakerPlayed(160L)
                        _audioCues.tryEmit(AudioCue.CountdownTick)
                    } else if (remSec > 3) {
                        lastCountdownSecond = -1
                    }
                }

                if (progress.isCompleted) {
                    finishAndSaveSession(completedByTarget = true)
                    break
                }

                _uiState.update { state ->
                    if (!state.isSessionRunning) return@update state
                    state.copy(
                        workoutProgress = progress,
                        metrics = state.metrics.copy(
                            elapsedMs = elapsedMs,
                            currentRpm = metricsTracker.getCurrentRpm(nowMs),
                            currentStreak = metricsTracker.getActiveStreak(nowMs)
                        )
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        tickerJob?.cancel()
        videoJob?.cancel()
        stftAudioEngine.stopLiveCapture()
    }
}
