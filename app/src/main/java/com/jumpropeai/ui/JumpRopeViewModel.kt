package com.jumpropeai.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jumpropeai.BuildConfig
import com.jumpropeai.ai.AcousticFingerprint
import com.jumpropeai.ai.FormCoachSnapshot
import com.jumpropeai.ai.FusionEngine
import com.jumpropeai.ai.JumpDetector
import com.jumpropeai.ai.JumpStyle
import com.jumpropeai.ai.KineticMove
import com.jumpropeai.ai.KineticTrickCoach
import com.jumpropeai.ai.PoseFrame
import com.jumpropeai.audio.AudioCue
import com.jumpropeai.audio.BeatSyncEngine
import com.jumpropeai.audio.BeatSyncSnapshot
import com.jumpropeai.audio.StftAudioEngine
import com.jumpropeai.data.CumulativeStats
import com.jumpropeai.data.WorkoutHistoryRepository
import com.jumpropeai.data.WorkoutSession
import com.jumpropeai.debug.PerfStats
import com.jumpropeai.debug.SessionDiagnosticLogger
import com.jumpropeai.metrics.MetricsTracker
import com.jumpropeai.metrics.WorkoutMetrics
import com.jumpropeai.update.AppReleaseInfo
import com.jumpropeai.update.AppUpdateChecker
import com.jumpropeai.update.UpdateCheckResult
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
    CAMERA_ONLY("Camera")
}

data class JumpRopeUiState(
    val isSessionRunning: Boolean = false,
    val lensFacing: Int = CameraSelector.LENS_FACING_FRONT,
    val sensorMode: SensorMode = SensorMode.FUSION,
    val hasAudioPermission: Boolean = false,
    val showSkeletonOverlay: Boolean = false,
    val isAudioEnabled: Boolean = true,
    val isEcoMode: Boolean = false,
    val autoHideControlsWhileActive: Boolean = false,
    val isControlsVisible: Boolean = true,
    val startCountdownDurationSec: Int = 3,
    val preStartCountdownRemaining: Int = 0,
    val isDiagnosticsEnabled: Boolean = false,
    val perfStats: PerfStats = PerfStats(),
    val recommendEcoLowFps: Boolean = false,
    val metrics: WorkoutMetrics = WorkoutMetrics(),
    val jumpStyle: JumpStyle = JumpStyle.REGULAR,
    val currentMove: KineticMove = KineticMove.REGULAR,
    val moveCounts: Map<KineticMove, Int> = emptyMap(),
    val formCoach: FormCoachSnapshot = FormCoachSnapshot(),
    val beatSync: BeatSyncSnapshot = BeatSyncSnapshot(),
    val fingerprint: AcousticFingerprint = AcousticFingerprint(),
    val tripsCount: Int = 0,
    val workoutConfig: WorkoutConfig = WorkoutConfig(),
    val workoutProgress: WorkoutProgress = WorkoutProgress(),
    val completedSessionSummary: WorkoutSession? = null,
    val historySessions: List<WorkoutSession> = emptyList(),
    val cumulativeStats: CumulativeStats = CumulativeStats(),
    val showHistorySheet: Boolean = false,
    val showModeSheet: Boolean = false,
    // Video Studio analysis state
    val showVideoSheet: Boolean = false,
    val isAnalyzingVideo: Boolean = false,
    val videoProgress: VideoAnalysisProgress? = null,
    val videoResult: VideoAnalysisResult? = null,
    val videoError: String? = null,
    // In-App Update state
    val availableUpdate: AppReleaseInfo? = null,
    val showUpdateDialog: Boolean = false,
    val isCheckingUpdate: Boolean = false,
    val updateStatusMessage: String? = null
) {
    val isCameraActive: Boolean
        get() = sensorMode != SensorMode.AUDIO_ONLY && !showVideoSheet

    val hasActiveProgress: Boolean
        get() = metrics.jumpCount > 0 || metrics.elapsedMs >= 1000L

    val isCountingDown: Boolean
        get() = preStartCountdownRemaining > 0
}

class JumpRopeViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("jumpvex_prefs", Context.MODE_PRIVATE)
    private val jumpDetector = JumpDetector()
    private val fusionEngine = FusionEngine()
    private val kineticTrickCoach = KineticTrickCoach()
    private val beatSyncEngine = BeatSyncEngine()
    private val stftAudioEngine = StftAudioEngine()
    private val metricsTracker = MetricsTracker()
    private val diagnosticLogger = SessionDiagnosticLogger()
    private val videoAnalyzer = VideoJumpAnalyzer(application.applicationContext)
    private val historyRepository = WorkoutHistoryRepository(
        File(application.filesDir, "workout_history.json")
    )

    private val _uiState = MutableStateFlow(
        JumpRopeUiState(
            startCountdownDurationSec = prefs.getInt(KEY_START_COUNTDOWN_SEC, 3),
            autoHideControlsWhileActive = prefs.getBoolean(KEY_AUTO_HIDE_CONTROLS, false),
            isDiagnosticsEnabled = prefs.getBoolean(KEY_DIAGNOSTICS_ENABLED, false)
        )
    )
    val uiState: StateFlow<JumpRopeUiState> = _uiState.asStateFlow()

    // Isolated high-frequency pose stream so ONLY PoseOverlay redraws at 18-30 FPS (0% full-HUD recomposition!)
    private val _latestPose = MutableStateFlow<PoseFrame?>(null)
    val latestPose: StateFlow<PoseFrame?> = _latestPose.asStateFlow()

    private val _audioCues = MutableSharedFlow<AudioCue>(extraBufferCapacity = 16)
    val audioCues: SharedFlow<AudioCue> = _audioCues.asSharedFlow()

    private var tickerJob: Job? = null
    private var preStartCountdownJob: Job? = null
    private var videoJob: Job? = null
    private var updateCheckJob: Job? = null
    private var lastHiitPhase: HiitPhase? = null
    private var lastCountdownSecond: Int = -1
    private var activeSessionId: String? = null
    private var activeSessionStartEpochMs: Long = 0L
    private var lastAutoSavedJumpCount: Int = 0
    private var lastAudioBeatPollMs: Long = -10_000L

    // Live FPS & CPU profiler counters (Zero-GC)
    private var fpsWindowStartMs: Long = 0L
    private var fpsFrameCount: Int = 0
    private var lastMeasuredFps: Int = 0
    private var lastPerfEmitMs: Long = 0L

    init {
        val loaded = historyRepository.loadSessions()
        _uiState.update {
            it.copy(
                historySessions = loaded,
                cumulativeStats = WorkoutHistoryRepository.computeStats(loaded)
            )
        }
        checkForUpdates(manual = false)
    }

    companion object {
        private const val KEY_START_COUNTDOWN_SEC = "start_countdown_sec"
        private const val KEY_AUTO_HIDE_CONTROLS = "auto_hide_controls"
        private const val KEY_DIAGNOSTICS_ENABLED = "diagnostics_enabled"
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
        if (next == SensorMode.AUDIO_ONLY) _latestPose.value = null
        _uiState.update {
            it.copy(
                sensorMode = next,
                isEcoMode = if (next == SensorMode.AUDIO_ONLY) false else it.isEcoMode
            )
        }
        syncAudioCapture()
    }

    fun onPoseFrame(frame: PoseFrame) {
        val currentState = _uiState.value
        if (currentState.sensorMode == SensorMode.AUDIO_ONLY) return

        val needsSkeletonRender = currentState.showSkeletonOverlay && !currentState.isEcoMode
        if (needsSkeletonRender) {
            _latestPose.value = frame
        }

        val tStartNs = if (currentState.isDiagnosticsEnabled) System.nanoTime() else 0L
        val nowMs = frame.timestampMs

        val isCountingAllowed = currentState.isSessionRunning && currentState.workoutProgress.isCountingAllowed
        val isShuffleMode = currentState.workoutConfig.mode == WorkoutModeType.FREESTYLE_SHUFFLE
        val result = jumpDetector.processFrame(frame, isCountingActive = isCountingAllowed)

        var jumpsAccepted = 0
        var latestStyle = currentState.jumpStyle
        var latestFingerprint = currentState.fingerprint
        var recommendLowFps = currentState.recommendEcoLowFps

        val useAudio = currentState.hasAudioPermission && currentState.sensorMode == SensorMode.FUSION
        val candidate = result.candidate
        val shouldPollBeatAudio = isCountingAllowed && useAudio && (nowMs - lastAudioBeatPollMs >= 500L)

        // CPU/GC Optimization: ONLY snapshot the 600-element STFT ring buffer on jump candidates (~2Hz) or beat poll (2Hz),
        // eliminating 90% of per-frame array copies & audio-thread lock contention!
        val audioSnapshot = if (isCountingAllowed && useAudio && (candidate != null || shouldPollBeatAudio)) {
            if (shouldPollBeatAudio) lastAudioBeatPollMs = nowMs
            stftAudioEngine.snapshotHistory()
        } else {
            emptyList()
        }

        if (candidate != null && isCountingAllowed) {
            if (currentState.sensorMode == SensorMode.FUSION) {
                val decision = fusionEngine.evaluateCandidate(
                    candidate = candidate,
                    audioHistory = audioSnapshot,
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

        // Parallel AI v13.3 Kinetic Trick, Shuffle Footwork & Form Coach (Zero-GC, never alters jump counter!)
        val kineticRes = if (isCountingAllowed) {
            kineticTrickCoach.processFrame(
                frame = frame,
                isAirborne = result.isAirborne,
                jumpAccepted = jumpsAccepted > 0,
                jumpAmplitude = candidate?.amplitude ?: 0f,
                audioHistory = audioSnapshot,
                isShuffleMode = isShuffleMode
            )
        } else {
            null
        }

        // In Freestyle / Shuffle mode, also count rhythmic Shuffle Dance steps (Running Man, T-Step, Charleston)
        val totalMovesAccepted = when {
            jumpsAccepted > 0 -> jumpsAccepted
            isShuffleMode && kineticRes?.shuffleStepDetected == true -> 1
            else -> 0
        }

        val beatSnapshot = if (isCountingAllowed && useAudio) {
            val snap = if (audioSnapshot.isNotEmpty()) {
                beatSyncEngine.updateFromAudio(audioSnapshot, nowMs)
            } else {
                beatSyncEngine.snapshot()
            }
            if (totalMovesAccepted > 0) {
                beatSyncEngine.recordMoveOnBeat(nowMs)
                beatSyncEngine.snapshot()
            } else {
                snap
            }
        } else {
            currentState.beatSync
        }

        if (totalMovesAccepted > 0 && isCountingAllowed) {
            repeat(totalMovesAccepted) {
                metricsTracker.recordJump(nowMs)
            }
            emitJumpCue(metricsTracker.jumpCount)
        }

        // Optional Zero-Overhead Live FPS/CPU/RAM Monitor + CSV Diagnostic Telemetry
        var updatedPerf: PerfStats? = null
        if (currentState.isDiagnosticsEnabled) {
            val aiUs = ((System.nanoTime() - tStartNs) / 1000L).toInt().coerceAtLeast(1)
            if (fpsWindowStartMs == 0L) fpsWindowStartMs = nowMs
            fpsFrameCount++
            val windowElapsed = nowMs - fpsWindowStartMs
            if (windowElapsed >= 1000L) {
                lastMeasuredFps = ((fpsFrameCount * 1000.0) / windowElapsed).roundToInt()
                fpsWindowStartMs = nowMs
                fpsFrameCount = 0
            }
            val rt = Runtime.getRuntime()
            val heapMb = ((rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)).toInt()
            if (isCountingAllowed) {
                diagnosticLogger.recordFrame(
                    tsMs = nowMs,
                    fps = lastMeasuredFps,
                    aiUs = aiUs,
                    heapMb = heapMb,
                    jumps = metricsTracker.jumpCount,
                    isAirborne = result.isAirborne,
                    moveName = (kineticRes?.currentMove ?: currentState.currentMove).name,
                    formScore = (kineticRes?.formSnapshot ?: currentState.formCoach).overallScore,
                    bpm = beatSnapshot.detectedBpm,
                    syncPct = beatSnapshot.beatSyncScore,
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
            if (nowMs - lastPerfEmitMs >= 500L) {
                lastPerfEmitMs = nowMs
                updatedPerf = PerfStats(
                    liveFps = lastMeasuredFps,
                    aiProcessUs = aiUs,
                    usedHeapMb = heapMb,
                    loggedRows = diagnosticLogger.loggedRowCount()
                )
            }
        }

        val prevMetrics = currentState.metrics
        val moveChanged = kineticRes != null && (
            kineticRes.currentMove != currentState.currentMove ||
                kineticRes.formSnapshot.overallScore != currentState.formCoach.overallScore
            )
        val beatChanged = beatSnapshot.detectedBpm != currentState.beatSync.detectedBpm ||
            beatSnapshot.beatSyncScore != currentState.beatSync.beatSyncScore

        val stateChanged = totalMovesAccepted > 0 ||
            moveChanged ||
            beatChanged ||
            updatedPerf != null ||
            result.isAirborne != prevMetrics.isAirborne ||
            result.status != prevMetrics.detectorStatus ||
            result.calibrationProgress != prevMetrics.calibrationProgress

        if (!stateChanged) {
            return
        }

        val elapsedMs = metricsTracker.getElapsedMs(nowMs)
        val progress = WorkoutModeEvaluator.evaluate(
            config = currentState.workoutConfig,
            jumpCount = metricsTracker.jumpCount,
            elapsedMs = elapsedMs
        )

        val justCompleted = currentState.isSessionRunning && progress.isCompleted

        _uiState.update { state ->
            state.copy(
                workoutProgress = progress,
                jumpStyle = latestStyle,
                currentMove = kineticRes?.currentMove ?: state.currentMove,
                moveCounts = kineticRes?.moveCounts ?: state.moveCounts,
                formCoach = kineticRes?.formSnapshot ?: state.formCoach,
                beatSync = beatSnapshot,
                perfStats = updatedPerf ?: state.perfStats,
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
        val step = _uiState.value.workoutConfig.milestoneInterval.coerceAtLeast(5)
        if (count > 0 && count % step == 0) {
            notifySpeakerPlayed(900L)
            _audioCues.tryEmit(AudioCue.Milestone(count))
        } else {
            notifySpeakerPlayed(160L)
            _audioCues.tryEmit(AudioCue.JumpTick)
        }
    }

    fun setStartCountdownDuration(seconds: Int) {
        val sanitized = when (seconds) {
            0, 3, 5, 8 -> seconds
            else -> 3
        }
        prefs.edit().putInt(KEY_START_COUNTDOWN_SEC, sanitized).apply()
        _uiState.update { it.copy(startCountdownDurationSec = sanitized) }
    }

    fun cancelPreStartCountdown() {
        preStartCountdownJob?.cancel()
        preStartCountdownJob = null
        _uiState.update { it.copy(preStartCountdownRemaining = 0, isControlsVisible = true) }
    }

    fun toggleSession() {
        val state = _uiState.value
        when {
            state.isCountingDown -> cancelPreStartCountdown()
            state.isSessionRunning -> pauseSession()
            else -> startSession()
        }
    }

    fun startSession() {
        val countdownSec = _uiState.value.startCountdownDurationSec
        if (countdownSec <= 0) {
            startSessionImmediately()
            return
        }

        preStartCountdownJob?.cancel()
        preStartCountdownJob = viewModelScope.launch {
            for (sec in countdownSec downTo 1) {
                _uiState.update {
                    it.copy(
                        preStartCountdownRemaining = sec,
                        completedSessionSummary = null
                    )
                }
                notifySpeakerPlayed(160L)
                _audioCues.tryEmit(AudioCue.CountdownTick)
                delay(1_000L)
            }
            _uiState.update { it.copy(preStartCountdownRemaining = 0) }
            preStartCountdownJob = null
            startSessionImmediately()
        }
    }

    private fun startSessionImmediately() {
        val nowMs = SystemClock.elapsedRealtime()
        val isFreshStart = metricsTracker.getElapsedMs(nowMs) == 0L
        metricsTracker.start(nowMs)
        lastCountdownSecond = -1

        val config = _uiState.value.workoutConfig
        if (isFreshStart) {
            if (_uiState.value.isDiagnosticsEnabled) {
                diagnosticLogger.startSession(config.shortLabel(), _uiState.value.sensorMode.label)
            }
            lastHiitPhase = HiitPhase.WORK
            notifySpeakerPlayed(700L)
            if (config.mode == WorkoutModeType.HIIT) {
                _audioCues.tryEmit(AudioCue.HiitWorkStart(1))
            } else {
                _audioCues.tryEmit(AudioCue.SessionStart)
            }
        }

        _uiState.update { state ->
            state.copy(
                isSessionRunning = true,
                preStartCountdownRemaining = 0,
                isControlsVisible = !state.autoHideControlsWhileActive,
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
        cancelPreStartCountdown()
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
        kineticTrickCoach.reset()
        beatSyncEngine.reset()
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
                currentMove = KineticMove.REGULAR,
                moveCounts = emptyMap(),
                formCoach = FormCoachSnapshot(),
                beatSync = BeatSyncSnapshot(),
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
        cancelPreStartCountdown()
        tickerJob?.cancel()
        tickerJob = null
        stftAudioEngine.stopLiveCapture()
        jumpDetector.reset()
        fusionEngine.reset()
        kineticTrickCoach.reset()
        beatSyncEngine.reset()
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
                currentMove = KineticMove.REGULAR,
                moveCounts = emptyMap(),
                formCoach = FormCoachSnapshot(),
                beatSync = BeatSyncSnapshot(),
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
                videoProgress = VideoAnalysisProgress("Starting analysis...", 0.02f, 0)
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
                        videoError = "Failed to read video: ${e.localizedMessage ?: "unsupported format"}"
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
        _latestPose.value = null
        _uiState.update { state ->
            val nextLens = if (state.lensFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            state.copy(lensFacing = nextLens)
        }
    }

    fun toggleSkeletonOverlay() {
        _uiState.update { state ->
            val next = !state.showSkeletonOverlay
            if (!next) _latestPose.value = null
            state.copy(showSkeletonOverlay = next)
        }
    }

    fun toggleAudio() {
        _uiState.update { it.copy(isAudioEnabled = !it.isAudioEnabled) }
    }

    fun toggleEcoMode() {
        _uiState.update { state ->
            val nextEco = !state.isEcoMode
            if (nextEco) _latestPose.value = null
            state.copy(isEcoMode = nextEco)
        }
    }

    fun toggleDiagnostics() {
        _uiState.update { state ->
            val next = !state.isDiagnosticsEnabled
            prefs.edit().putBoolean(KEY_DIAGNOSTICS_ENABLED, next).apply()
            if (next) {
                diagnosticLogger.startSession(state.workoutConfig.shortLabel(), state.sensorMode.label)
            }
            state.copy(
                isDiagnosticsEnabled = next,
                perfStats = if (next) state.perfStats else PerfStats()
            )
        }
    }

    fun exportAndShareDiagnosticLog(context: Context): Boolean {
        val file = diagnosticLogger.exportToFile(context) ?: return false
        diagnosticLogger.shareLogFile(context, file)
        return true
    }

    fun toggleAutoHideControls() {
        _uiState.update { state ->
            val nextAutoHide = !state.autoHideControlsWhileActive
            prefs.edit().putBoolean(KEY_AUTO_HIDE_CONTROLS, nextAutoHide).apply()
            state.copy(
                autoHideControlsWhileActive = nextAutoHide,
                isControlsVisible = if (state.isSessionRunning) !nextAutoHide else true
            )
        }
    }

    fun toggleControlsVisibility() {
        _uiState.update { state ->
            if (!state.isSessionRunning) state else state.copy(isControlsVisible = !state.isControlsVisible)
        }
    }

    fun checkForUpdates(manual: Boolean = true) {
        if (_uiState.value.isCheckingUpdate) return
        updateCheckJob?.cancel()
        updateCheckJob = viewModelScope.launch {
            if (manual) {
                _uiState.update {
                    it.copy(isCheckingUpdate = true, updateStatusMessage = "Checking GitHub Releases...")
                }
            }
            when (val result = AppUpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME)) {
                is UpdateCheckResult.UpdateAvailable -> {
                    _uiState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            availableUpdate = result.release,
                            showUpdateDialog = true,
                            updateStatusMessage = "New update v${result.release.versionName} available!"
                        )
                    }
                }
                is UpdateCheckResult.UpToDate -> {
                    _uiState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            availableUpdate = null,
                            updateStatusMessage = if (manual) "You're on the latest version (v${result.currentVersion})" else null
                        )
                    }
                }
                is UpdateCheckResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            updateStatusMessage = if (manual) "Couldn't check updates (offline or network error)" else null
                        )
                    }
                }
            }
        }
    }

    fun dismissUpdateDialog() {
        _uiState.update { it.copy(showUpdateDialog = false) }
    }

    fun openUpdateDialog() {
        if (_uiState.value.availableUpdate != null) {
            _uiState.update { it.copy(showUpdateDialog = true) }
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
        preStartCountdownJob?.cancel()
        videoJob?.cancel()
        updateCheckJob?.cancel()
        stftAudioEngine.stopLiveCapture()
    }
}
