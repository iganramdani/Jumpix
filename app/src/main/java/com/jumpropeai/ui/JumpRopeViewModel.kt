package com.jumpropeai.ui

import android.app.Application
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jumpropeai.ai.JumpDetector
import com.jumpropeai.ai.PoseFrame
import com.jumpropeai.audio.AudioCue
import com.jumpropeai.data.CumulativeStats
import com.jumpropeai.data.WorkoutHistoryRepository
import com.jumpropeai.data.WorkoutSession
import com.jumpropeai.metrics.MetricsTracker
import com.jumpropeai.metrics.WorkoutMetrics
import com.jumpropeai.workout.HiitPhase
import com.jumpropeai.workout.WorkoutConfig
import com.jumpropeai.workout.WorkoutModeEvaluator
import com.jumpropeai.workout.WorkoutModeType
import com.jumpropeai.workout.WorkoutProgress
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

data class JumpRopeUiState(
    val isSessionRunning: Boolean = false,
    val lensFacing: Int = CameraSelector.LENS_FACING_FRONT,
    val showSkeletonOverlay: Boolean = false,
    val isAudioEnabled: Boolean = true,
    val isEcoMode: Boolean = false,
    val isControlsVisible: Boolean = true,
    val latestPose: PoseFrame? = null,
    val metrics: WorkoutMetrics = WorkoutMetrics(),
    val workoutConfig: WorkoutConfig = WorkoutConfig(),
    val workoutProgress: WorkoutProgress = WorkoutProgress(),
    val completedSessionSummary: WorkoutSession? = null,
    val historySessions: List<WorkoutSession> = emptyList(),
    val cumulativeStats: CumulativeStats = CumulativeStats(),
    val showHistorySheet: Boolean = false,
    val showModeSheet: Boolean = false
) {
    val isFrontCamera: Boolean
        get() = lensFacing == CameraSelector.LENS_FACING_FRONT

    val hasActiveProgress: Boolean
        get() = metrics.jumpCount > 0 || metrics.elapsedMs >= 1000L
}

class JumpRopeViewModel(application: Application) : AndroidViewModel(application) {

    private val jumpDetector = JumpDetector()
    private val metricsTracker = MetricsTracker()
    private val historyRepository = WorkoutHistoryRepository(
        File(application.filesDir, "workout_history.json")
    )

    private val _uiState = MutableStateFlow(JumpRopeUiState())
    val uiState: StateFlow<JumpRopeUiState> = _uiState.asStateFlow()

    private val _audioCues = MutableSharedFlow<AudioCue>(extraBufferCapacity = 16)
    val audioCues: SharedFlow<AudioCue> = _audioCues.asSharedFlow()

    private var tickerJob: Job? = null
    private var lastHiitPhase: HiitPhase? = null
    private var lastHiitRound: Int = 1
    private var lastCountdownSecond: Int = -1

    init {
        val loaded = historyRepository.loadSessions()
        _uiState.update {
            it.copy(
                historySessions = loaded,
                cumulativeStats = WorkoutHistoryRepository.computeStats(loaded)
            )
        }
    }

    fun onPoseFrame(frame: PoseFrame) {
        val currentState = _uiState.value
        val isCountingAllowed = currentState.isSessionRunning && currentState.workoutProgress.isCountingAllowed
        val result = jumpDetector.processFrame(frame, isCountingActive = isCountingAllowed)

        var justCompleted = false
        if (result.jumpDetected && isCountingAllowed) {
            metricsTracker.recordJump(frame.timestampMs)
            val count = metricsTracker.jumpCount
            if (count % 25 == 0) {
                _audioCues.tryEmit(AudioCue.Milestone(count))
            } else {
                _audioCues.tryEmit(AudioCue.JumpTick)
            }
        }

        val needsSkeletonRender = currentState.showSkeletonOverlay && !currentState.isEcoMode
        val prevMetrics = currentState.metrics
        val stateChanged = result.jumpDetected ||
            result.isAirborne != prevMetrics.isAirborne ||
            result.status != prevMetrics.detectorStatus ||
            result.calibrationProgress != prevMetrics.calibrationProgress

        // Battery optimization: skip Compose StateFlow updates on frames with no visual/metric change
        // when skeleton overlay is hidden or Eco Mode is active.
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

        if (currentState.isSessionRunning && progress.isCompleted) {
            justCompleted = true
        }

        _uiState.update { state ->
            state.copy(
                latestPose = if (needsSkeletonRender) frame else null,
                workoutProgress = progress,
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

    fun toggleSession() {
        if (_uiState.value.isSessionRunning) {
            pauseSession()
        } else {
            startSession()
        }
    }

    fun startSession() {
        val nowMs = SystemClock.elapsedRealtime()
        val isFreshStart = metricsTracker.getElapsedMs(nowMs) == 0L
        metricsTracker.start(nowMs)
        lastCountdownSecond = -1

        val config = _uiState.value.workoutConfig
        if (isFreshStart) {
            lastHiitPhase = HiitPhase.WORK
            lastHiitRound = 1
            if (config.mode == WorkoutModeType.HIIT) {
                _audioCues.tryEmit(AudioCue.HiitWorkStart(1))
            } else {
                _audioCues.tryEmit(AudioCue.SessionStart)
            }
        }

        // Auto-hide clutter for clean minimal focus HUD as soon as workout starts
        _uiState.update {
            it.copy(
                isSessionRunning = true,
                isControlsVisible = false,
                completedSessionSummary = null
            )
        }
        startTicker()
    }

    fun pauseSession() {
        val nowMs = SystemClock.elapsedRealtime()
        metricsTracker.pause(nowMs)
        tickerJob?.cancel()
        tickerJob = null
        _uiState.update { state ->
            state.copy(
                isSessionRunning = false,
                isControlsVisible = true,
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
        val session = WorkoutSession(
            id = UUID.randomUUID().toString(),
            timestampMillis = System.currentTimeMillis(),
            modeLabel = config.shortLabel(),
            modeType = config.mode,
            jumpCount = totalJumps,
            durationMs = durationMs,
            averageRpm = avgRpm,
            peakRpm = metricsTracker.peakRpm,
            estimatedCalories = metricsTracker.estimatedCalories,
            bestStreak = metricsTracker.bestStreak
        )

        val updatedHistory = historyRepository.saveSession(session)
        if (completedByTarget) {
            _audioCues.tryEmit(AudioCue.WorkoutCompleted(totalJumps))
        }

        jumpDetector.reset()
        metricsTracker.reset()
        lastHiitPhase = null
        lastCountdownSecond = -1

        _uiState.update { state ->
            state.copy(
                isSessionRunning = false,
                isControlsVisible = true,
                isEcoMode = false,
                metrics = WorkoutMetrics(),
                workoutProgress = WorkoutProgress(),
                completedSessionSummary = session,
                historySessions = updatedHistory,
                cumulativeStats = WorkoutHistoryRepository.computeStats(updatedHistory)
            )
        }
    }

    fun resetSession() {
        tickerJob?.cancel()
        tickerJob = null
        jumpDetector.reset()
        metricsTracker.reset()
        lastHiitPhase = null
        lastCountdownSecond = -1
        _uiState.update { state ->
            state.copy(
                isSessionRunning = false,
                isControlsVisible = true,
                metrics = WorkoutMetrics(),
                workoutProgress = WorkoutProgress()
            )
        }
    }

    fun updateWorkoutConfig(newConfig: WorkoutConfig) {
        resetSession()
        _uiState.update {
            it.copy(
                workoutConfig = newConfig,
                showModeSheet = false
            )
        }
    }

    fun toggleCamera() {
        jumpDetector.reset()
        _uiState.update { state ->
            val nextLens = if (state.lensFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            state.copy(
                lensFacing = nextLens,
                latestPose = null
            )
        }
    }

    fun toggleSkeletonOverlay() {
        _uiState.update { state ->
            val next = !state.showSkeletonOverlay
            state.copy(
                showSkeletonOverlay = next,
                latestPose = if (next) state.latestPose else null
            )
        }
    }

    fun toggleAudio() {
        _uiState.update { it.copy(isAudioEnabled = !it.isAudioEnabled) }
    }

    fun toggleEcoMode() {
        _uiState.update { state ->
            val nextEco = !state.isEcoMode
            state.copy(
                isEcoMode = nextEco,
                latestPose = if (nextEco) null else state.latestPose
            )
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

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                // 500ms tick saves CPU wakeups while keeping timers & countdowns responsive
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
                        if (progress.hiitPhase == HiitPhase.REST) {
                            _audioCues.tryEmit(AudioCue.HiitRestStart)
                        } else {
                            _audioCues.tryEmit(AudioCue.HiitWorkStart(progress.currentRound))
                        }
                    }
                    lastHiitPhase = progress.hiitPhase
                    lastHiitRound = progress.currentRound
                }

                progress.remainingMs?.let { remMs ->
                    val remSec = ((remMs + 999L) / 1000L).toInt()
                    if (remSec in 1..3 && remSec != lastCountdownSecond && !progress.isCompleted) {
                        lastCountdownSecond = remSec
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
    }
}
