package com.jumpropeai.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jumpropeai.BuildConfig
import com.jumpropeai.ai.DetectorStatus
import com.jumpropeai.ai.FramingStatus
import com.jumpropeai.ai.PoseFrame
import com.jumpropeai.audio.AudioFeedbackManager
import com.jumpropeai.data.WorkoutSession
import com.jumpropeai.metrics.RopeType
import com.jumpropeai.ui.components.CameraPreview
import com.jumpropeai.ui.components.HistoryTabScreen
import com.jumpropeai.ui.components.OpticalHeartRateDialog
import com.jumpropeai.ui.components.PoseOverlay
import com.jumpropeai.ui.components.SessionSummaryDialog
import com.jumpropeai.ui.components.ShareCardDialog
import com.jumpropeai.ui.components.SkillAcademyDialog
import com.jumpropeai.ui.components.SplitBattleArenaBanner
import com.jumpropeai.ui.components.VideoAnalyzerScreen
import com.jumpropeai.ui.components.WorkoutModeSheet
import com.jumpropeai.ui.theme.DeepMidnight
import com.jumpropeai.ui.theme.IceMint
import com.jumpropeai.ui.theme.IceMintGlow
import com.jumpropeai.ui.theme.IceMintSoft
import com.jumpropeai.ui.theme.MidnightBorder
import com.jumpropeai.ui.theme.MidnightElevated
import com.jumpropeai.ui.theme.MidnightGlass
import com.jumpropeai.ui.theme.MidnightSurface
import com.jumpropeai.ui.theme.TextPrimary
import com.jumpropeai.ui.theme.TextSecondary
import com.jumpropeai.update.AppReleaseInfo
import com.jumpropeai.video.VideoTelemetryEvent
import com.jumpropeai.workout.GhostProfile
import com.jumpropeai.workout.HiitPhase
import com.jumpropeai.workout.WorkoutModeType
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class MainTab(val label: String, val icon: ImageVector) {
    LATIHAN("Workout", Icons.Rounded.FitnessCenter),
    VIDEO("Video", Icons.Rounded.Movie),
    RIWAYAT("History", Icons.Rounded.History)
}

private data class ShareCardRequest(
    val session: WorkoutSession,
    val backdrop: Bitmap? = null,
    val styleLabel: String? = null,
    val sourceVideoUri: Uri? = null,
    val telemetryEvents: List<VideoTelemetryEvent> = emptyList()
)

@Composable
fun JumpRopeScreen(viewModel: JumpRopeViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentAudioEnabled by rememberUpdatedState(uiState.isAudioEnabled)

    var selectedTab by remember { mutableStateOf(MainTab.LATIHAN) }
    var showSettingsSheet by remember { mutableStateOf(false) }

    val audioManager = remember { AudioFeedbackManager(context) }
    DisposableEffect(Unit) {
        onDispose { audioManager.release() }
    }
    LaunchedEffect(viewModel) {
        viewModel.audioCues.collect { cue ->
            audioManager.play(cue, enabled = currentAudioEnabled)
        }
    }

    // Dim screen brightness in AMOLED Eco Mode or Audio-Only Eco Mode while running on Latihan tab
    val shouldDimScreen = selectedTab == MainTab.LATIHAN &&
        (uiState.isEcoMode || (uiState.sensorMode == SensorMode.AUDIO_ONLY && uiState.isSessionRunning))
    DisposableEffect(shouldDimScreen) {
        val window = context.findActivity()?.window
        if (window != null) {
            val params = window.attributes
            params.screenBrightness = if (shouldDimScreen) {
                0.08f
            } else {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            window.attributes = params
        }
        onDispose {
            val w = context.findActivity()?.window
            if (w != null) {
                val params = w.attributes
                params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = params
            }
        }
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasCameraPermission = results[Manifest.permission.CAMERA] ?: hasCameraPermission
        val micGranted = results[Manifest.permission.RECORD_AUDIO]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        viewModel.onAudioPermissionResult(micGranted)
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val entries = uris.map { uri -> uri to context.resolveDisplayName(uri) }
            selectedTab = MainTab.VIDEO
            viewModel.analyzeMultipleVideos(entries)
        }
    }

    LaunchedEffect(Unit) {
        val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        viewModel.onAudioPermissionResult(micGranted)
        if (!hasCameraPermission || !micGranted) {
            permissionsLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    var shareCardTarget by remember { mutableStateOf<ShareCardRequest?>(null) }

    if (!hasCameraPermission) {
        CameraPermissionView(
            onRequestPermission = {
                permissionsLauncher.launch(
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                )
            }
        )
        return
    }

    val showBottomNav = !(
        selectedTab == MainTab.LATIHAN &&
            (uiState.isEcoMode || uiState.isCountingDown || (uiState.isSessionRunning && !uiState.isControlsVisible))
        )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepMidnight)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (selectedTab) {
                MainTab.LATIHAN -> {
                    DynamicRingLiveHud(
                        uiState = uiState,
                        latestPoseFlow = viewModel.latestPose,
                        isCameraAllowedOnTab = true,
                        onPoseFrame = viewModel::onPoseFrame,
                        onToggleSession = viewModel::toggleSession,
                        onCancelCountdown = viewModel::cancelPreStartCountdown,
                        onCycleCountdown = {
                            val next = when (uiState.startCountdownDurationSec) {
                                3 -> 5
                                5 -> 8
                                8 -> 0
                                else -> 3
                            }
                            viewModel.setStartCountdownDuration(next)
                        },
                        onFinishSession = { viewModel.finishAndSaveSession(completedByTarget = false) },
                        onResetSession = viewModel::resetSession,
                        onToggleCamera = viewModel::toggleCamera,
                        onToggleEcoMode = viewModel::toggleEcoMode,
                        onToggleControls = viewModel::toggleControlsVisibility,
                        onOpenModeSheet = { viewModel.setShowModeSheet(true) },
                        onOpenSettings = { showSettingsSheet = true },
                        onCycleBattleMode = viewModel::cycleBattleArenaMode,
                        onResetBattle = viewModel::resetBattleArena,
                        onCloseBattle = { viewModel.toggleBattleArena() }
                    )
                }

                MainTab.VIDEO -> {
                    VideoAnalyzerScreen(
                        isAnalyzing = uiState.isAnalyzingVideo,
                        isShuffleMode = uiState.isVideoShuffleMode,
                        onToggleShuffleMode = viewModel::toggleVideoShuffleMode,
                        studioMode = uiState.videoStudioMode,
                        onSelectStudioMode = viewModel::setVideoStudioMode,
                        progress = uiState.videoProgress,
                        result = uiState.videoResult,
                        errorMessage = uiState.videoError,
                        queueIndex = uiState.videoQueueIndex,
                        queueTotal = uiState.videoQueueTotal,
                        batchResults = uiState.batchCompletedResults,
                        onPickVideo = { videoPickerLauncher.launch(arrayOf("video/*")) },
                        onShareResult = { res ->
                            val moveStrMap = LinkedHashMap<String, Int>()
                            for ((m, c) in res.moveCounts) {
                                if (c > 0) moveStrMap[m.label] = c
                            }
                            val ijrc = res.ijrcSummary
                            val session = WorkoutSession(
                                id = "video_share",
                                timestampMillis = System.currentTimeMillis(),
                                modeLabel = when {
                                    ijrc?.isCompetitionSprintDetected == true -> "Video • IJRC (${ijrc.rightFootJudgeScore} R-Foot)"
                                    uiState.isVideoShuffleMode -> "Video • Shuffle"
                                    else -> "Video"
                                },
                                modeType = when {
                                    ijrc?.isCompetitionSprintDetected == true -> WorkoutModeType.IJRU_SPEED_30S
                                    uiState.isVideoShuffleMode -> WorkoutModeType.FREESTYLE_SHUFFLE
                                    else -> WorkoutModeType.FREE
                                },
                                jumpCount = res.jumpCount,
                                durationMs = res.durationMs,
                                averageRpm = res.averageRpm,
                                peakRpm = res.peakRpm,
                                estimatedCalories = res.estimatedCalories,
                                bestStreak = res.bestStreak,
                                trips = res.trips,
                                formScore = res.formScore,
                                beatSyncScore = res.beatSyncScore,
                                detectedBpm = res.detectedBpm,
                                comboCount = res.comboCount,
                                ropeTypeLabel = uiState.ropeType.label,
                                moveBreakdown = moveStrMap
                            )
                            shareCardTarget = ShareCardRequest(
                                session = session,
                                backdrop = res.finalPreviewBitmap,
                                styleLabel = res.style.label,
                                sourceVideoUri = res.sourceUri,
                                telemetryEvents = res.telemetryEvents
                            )
                        },
                        onSaveResultToHistory = { res ->
                            viewModel.saveVideoResultToHistory(res)
                            selectedTab = MainTab.RIWAYAT
                        },
                        onClose = {
                            viewModel.closeVideoSheet()
                            selectedTab = MainTab.LATIHAN
                        }
                    )
                }

                MainTab.RIWAYAT -> {
                    HistoryTabScreen(
                        stats = uiState.cumulativeStats,
                        dailyGoalJumps = uiState.dailyGoalJumps,
                        sessions = uiState.historySessions,
                        onShareSession = { session -> shareCardTarget = ShareCardRequest(session = session) },
                        onDeleteSession = viewModel::deleteHistorySession,
                        onExportHistoryCsv = { viewModel.exportHistoryBackup(context) },
                        onOpenSkillAcademy = { viewModel.setSkillAcademyDialogVisible(true) }
                    )
                }
            }
        }

        if (showBottomNav) {
            TwoToneBottomNavBar(
                selectedTab = selectedTab,
                onSelectTab = { nextTab ->
                    if (nextTab == selectedTab) return@TwoToneBottomNavBar
                    if (selectedTab == MainTab.LATIHAN && (uiState.isSessionRunning || uiState.isCountingDown)) {
                        viewModel.pauseSession()
                    }
                    if (nextTab == MainTab.VIDEO) {
                        viewModel.openVideoSheet()
                    } else if (selectedTab == MainTab.VIDEO) {
                        viewModel.closeVideoSheet()
                    }
                    selectedTab = nextTab
                }
            )
        }
    }

    // Mode Selector Sheet (with IJRU Competition & Custom Inputs)
    if (uiState.showModeSheet) {
        WorkoutModeSheet(
            currentConfig = uiState.workoutConfig,
            onApply = viewModel::updateWorkoutConfig,
            onDismiss = { viewModel.setShowModeSheet(false) }
        )
    }

    // Clean Settings Bottom Sheet (Countdown + Touchless Gesture + Athlete Profile + Phone-Only Pro Suite)
    if (showSettingsSheet) {
        WorkoutSettingsSheet(
            uiState = uiState,
            onCycleSensorMode = viewModel::cycleSensorMode,
            onSelectCountdown = viewModel::setStartCountdownDuration,
            onToggleGestureControl = viewModel::toggleGestureControl,
            onToggleDoubleClap = viewModel::toggleDoubleClapControl,
            onUpdateAthleteProfile = viewModel::updateAthleteProfile,
            onCyclePacemakerMode = viewModel::cyclePacemakerMode,
            onSetPacemakerRpm = viewModel::setPacemakerRpm,
            onCycleGhostProfile = viewModel::cycleGhostProfile,
            onOpenHeartRateScanner = {
                showSettingsSheet = false
                viewModel.setHeartRateDialogVisible(true)
            },
            onOpenSkillAcademy = {
                showSettingsSheet = false
                viewModel.setSkillAcademyDialogVisible(true)
            },
            onStartSplitBattle = {
                showSettingsSheet = false
                viewModel.toggleBattleArena()
            },
            onToggleSkeleton = viewModel::toggleSkeletonOverlay,
            onToggleAudio = viewModel::toggleAudio,
            onToggleEcoMode = {
                viewModel.toggleEcoMode()
                showSettingsSheet = false
            },
            onToggleAutoHideControls = viewModel::toggleAutoHideControls,
            onToggleDiagnostics = viewModel::toggleDiagnostics,
            onExportDiagnosticLog = {
                viewModel.exportAndShareDiagnosticLog(context)
            },
            onCheckForUpdates = {
                if (uiState.availableUpdate != null) {
                    showSettingsSheet = false
                    viewModel.openUpdateDialog()
                } else {
                    viewModel.checkForUpdates(manual = true)
                }
            },
            onDismiss = { showSettingsSheet = false }
        )
    }

    if (uiState.showHeartRateDialog) {
        OpticalHeartRateDialog(
            snapshot = uiState.heartRateSnapshot,
            onOpticalSample = { r, g -> viewModel.feedOpticalHrSample(r, g) },
            onDismiss = { viewModel.setHeartRateDialogVisible(false) }
        )
    }

    if (uiState.showSkillAcademyDialog) {
        SkillAcademyDialog(
            skillNodes = uiState.skillNodes,
            adaptivePlan = uiState.adaptivePlan,
            onDismiss = { viewModel.setSkillAcademyDialogVisible(false) }
        )
    }

    // Completed Session Summary Dialog
    uiState.completedSessionSummary?.let { summary ->
        SessionSummaryDialog(
            session = summary,
            onShareSession = { session ->
                shareCardTarget = ShareCardRequest(session = session, styleLabel = uiState.jumpStyle.label)
            },
            onDismiss = viewModel::dismissSummaryDialog
        )
    }

    // Share Card & Live Animated Video Watermark Studio Dialog
    shareCardTarget?.let { req ->
        ShareCardDialog(
            session = req.session,
            backdropBitmap = req.backdrop,
            styleLabel = req.styleLabel,
            sourceVideoUri = req.sourceVideoUri,
            telemetryEvents = req.telemetryEvents,
            onDismiss = { shareCardTarget = null }
        )
    }

    // In-App GitHub Release Update Dialog
    val pendingUpdate = uiState.availableUpdate
    if (uiState.showUpdateDialog && pendingUpdate != null) {
        AppUpdateDialog(
            release = pendingUpdate,
            onDownloadApk = {
                viewModel.dismissUpdateDialog()
                context.openExternalUrl(pendingUpdate.apkDownloadUrl)
            },
            onOpenReleasePage = {
                viewModel.dismissUpdateDialog()
                context.openExternalUrl(pendingUpdate.releasePageUrl)
            },
            onDismiss = viewModel::dismissUpdateDialog
        )
    }
}

/**
 * Design 3 — Dynamic Ring HUD (Deep Midnight #0D1321 + Ice Mint #2EE59D).
 */
@Composable
private fun DynamicRingLiveHud(
    uiState: JumpRopeUiState,
    latestPoseFlow: StateFlow<PoseFrame?>,
    isCameraAllowedOnTab: Boolean,
    onPoseFrame: (PoseFrame) -> Unit,
    onToggleSession: () -> Unit,
    onCancelCountdown: () -> Unit,
    onCycleCountdown: () -> Unit,
    onFinishSession: () -> Unit,
    onResetSession: () -> Unit,
    onToggleCamera: () -> Unit,
    onToggleEcoMode: () -> Unit,
    onToggleControls: () -> Unit,
    onOpenModeSheet: () -> Unit,
    onOpenSettings: () -> Unit,
    onCycleBattleMode: () -> Unit = {},
    onResetBattle: () -> Unit = {},
    onCloseBattle: () -> Unit = {}
) {
    val metrics = uiState.metrics
    val config = uiState.workoutConfig
    val progress = uiState.workoutProgress
    val showControls = (!uiState.isSessionRunning || uiState.isControlsVisible) && !uiState.isCountingDown
    val isPureBlackMode = uiState.isEcoMode || uiState.sensorMode == SensorMode.AUDIO_ONLY

    // Compute intuitive ring progress for ALL 4 modes
    val rawRingProgress = when (config.mode) {
        WorkoutModeType.FREE, WorkoutModeType.FREESTYLE_SHUFFLE -> {
            val interval = config.milestoneInterval.coerceAtLeast(10)
            if (metrics.jumpCount == 0) 0f
            else {
                val mod = metrics.jumpCount % interval
                if (mod == 0) 1f else mod.toFloat() / interval.toFloat()
            }
        }
        else -> progress.progressFraction.coerceIn(0f, 1f)
    }
    val animatedRingProgress by animateFloatAsState(
        targetValue = rawRingProgress,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "ringProgress"
    )
    val countScale by animateFloatAsState(
        targetValue = if (metrics.isAirborne) 1.06f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "ringCountBounce"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (uiState.isSessionRunning && !uiState.isEcoMode && !uiState.isCountingDown) {
                    onToggleControls()
                }
            }
    ) {
        // 1. Full-bleed Camera Preview
        CameraPreview(
            lensFacing = uiState.lensFacing,
            isCameraActive = uiState.isCameraActive && isCameraAllowedOnTab,
            isPreviewEnabled = !uiState.isEcoMode,
            isSessionRunning = uiState.isSessionRunning,
            isDynamicEcoLowFps = uiState.isEcoMode && uiState.recommendEcoLowFps,
            onPoseFrame = onPoseFrame,
            modifier = Modifier.fillMaxSize()
        )

        // 2. Optional Two-Tone Skeleton Overlay (isolated recomposition scope for max FPS!)
        if (uiState.showSkeletonOverlay && !isPureBlackMode) {
            IsolatedPoseOverlay(
                latestPoseFlow = latestPoseFlow,
                isAirborne = metrics.isAirborne,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 3. Dedicated Pure AMOLED Eco Mode Overlay
        if (uiState.isEcoMode) {
            EcoModeOverlay(
                uiState = uiState,
                onToggleSession = onToggleSession,
                onExitEco = onToggleEcoMode
            )
            return@Box
        }

        // 4. Subtle Top & Bottom Deep Midnight Vignette for crisp legibility
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to DeepMidnight.copy(alpha = 0.72f),
                        0.28f to Color.Transparent,
                        0.68f to Color.Transparent,
                        1.0f to DeepMidnight.copy(alpha = 0.88f)
                    )
                )
        )

        // 5. Audio-Only Minimalist Backdrop
        if (uiState.sensorMode == SensorMode.AUDIO_ONLY) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DeepMidnight)
            )
        }

        // 6. Clean HUD Foreground
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // TOP BAR: Mode selector pill on left, ONLY 2 circular icon buttons (Settings + Camera Flip) on right
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = onOpenModeSheet,
                        shape = CircleShape,
                        color = MidnightGlass,
                        border = BorderStroke(1.dp, MidnightBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(IceMint)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = config.shortLabel(),
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = "Select Mode",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box {
                            CircularHudIconButton(
                                icon = Icons.Rounded.Settings,
                                contentDescription = "Settings",
                                onClick = onOpenSettings
                            )
                            if (uiState.availableUpdate != null) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = (-2).dp, y = 2.dp)
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(IceMint)
                                )
                            }
                        }
                        if (uiState.sensorMode != SensorMode.AUDIO_ONLY && uiState.sensorMode != SensorMode.POCKET_IMU) {
                            CircularHudIconButton(
                                icon = Icons.Rounded.Cameraswitch,
                                contentDescription = "Switch Camera",
                                onClick = onToggleCamera
                            )
                        }
                    }
                }
            }

            // 2-Player Split-Screen Battle Scoreboard (when active)
            if (uiState.battleState.isActive || uiState.battleState.winnerLabel != null) {
                Spacer(Modifier.height(8.dp))
                SplitBattleArenaBanner(
                    battleState = uiState.battleState,
                    onCycleMode = onCycleBattleMode,
                    onResetBattle = onResetBattle,
                    onCloseBattle = onCloseBattle
                )
            }

            // Optional Live AI Performance Monitor Pill (FPS • CPU µs • RAM • Logged Rows)
            if (uiState.isDiagnosticsEnabled && !uiState.isCountingDown) {
                Spacer(Modifier.height(8.dp))
                val p = uiState.perfStats
                val aiMsStr = String.format(Locale.US, "%.2fms", p.aiProcessUs / 1000.0)
                Surface(
                    shape = CircleShape,
                    color = DeepMidnight.copy(alpha = 0.72f),
                    border = BorderStroke(1.dp, IceMint.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = "${p.liveFps} FPS  •  AI $aiMsStr  •  ${p.usedHeapMb}MB  •  LOG ${p.loggedRows}",
                        color = IceMint,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }

            // Pocket IMU Camera-Free Cockpit Badge
            if (uiState.sensorMode == SensorMode.POCKET_IMU && !uiState.isCountingDown) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = CircleShape,
                    color = DeepMidnight.copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, IceMint.copy(alpha = 0.55f))
                ) {
                    Text(
                        text = if (uiState.isSessionRunning) {
                            "POCKET IMU ACTIVE • 100 Hz Gyro-Gravity Locked"
                        } else {
                            "Pocket IMU Ready • Press Play & slide phone into pocket"
                        },
                        color = IceMint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 5.dp)
                    )
                }
            }

            // Smart Camera Framing Assistant & Touchless Hand-Gesture Hold Indicator
            if (uiState.sensorMode != SensorMode.AUDIO_ONLY && uiState.sensorMode != SensorMode.POCKET_IMU && !uiState.isCountingDown) {
                if (uiState.gestureHoldProgress > 0.02f) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = DeepMidnight.copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, IceMint)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth(0.76f)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (uiState.isSessionRunning) "HOLD HAND UP TO PAUSE..." else "HOLD HAND UP TO START...",
                                color = IceMint,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { uiState.gestureHoldProgress.coerceIn(0f, 1f) },
                                color = IceMint,
                                trackColor = MidnightElevated,
                                strokeCap = StrokeCap.Round,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                            )
                        }
                    }
                } else if (!uiState.isSessionRunning) {
                    val framing = uiState.framingStatus
                    val hintText = when {
                        framing == FramingStatus.READY && uiState.isGestureControlEnabled ->
                            "Full body locked • Raise 1 hand to start"
                        framing == FramingStatus.READY ->
                            "Full body locked • Ready to jump"
                        else -> framing.guidance
                    }
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = CircleShape,
                        color = MidnightGlass,
                        border = BorderStroke(
                            1.dp,
                            if (framing.isOptimal) IceMint.copy(alpha = 0.5f) else MidnightBorder
                        )
                    ) {
                        Text(
                            text = hintText,
                            color = if (framing.isOptimal) IceMint else TextPrimary.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.weight(0.55f))

            // UPPER-CENTER: Glowing Dynamic Circular Progress Ring + Giant Jump/Move Counter
            val isShuffleMode = config.mode == WorkoutModeType.FREESTYLE_SHUFFLE
            val isCameraSensor = uiState.sensorMode != SensorMode.AUDIO_ONLY && uiState.sensorMode != SensorMode.POCKET_IMU
            val ringSubLabel = when {
                config.mode == WorkoutModeType.HIIT && uiState.isSessionRunning -> {
                    val phaseTxt = if (progress.hiitPhase == HiitPhase.WORK) "WORK" else "REST"
                    "$phaseTxt • R${progress.currentRound}/${progress.totalRounds}"
                }
                uiState.sensorMode == SensorMode.POCKET_IMU && !uiState.isSessionRunning -> "POCKET IMU"
                metrics.detectorStatus == DetectorStatus.NO_PERSON && isCameraSensor ->
                    "STEP INTO FRAME"
                metrics.detectorStatus == DetectorStatus.CALIBRATING && isCameraSensor -> "CALIBRATING..."
                isShuffleMode -> "MOVES"
                else -> "JUMPS"
            }

            DynamicProgressRingCounter(
                jumpCount = metrics.jumpCount,
                progress = animatedRingProgress,
                subLabel = ringSubLabel,
                isAirborne = metrics.isAirborne,
                scaleFactor = countScale
            )

            // Clean Two-Tone Live AI Trick, Form Coach & Beat-Sync Pill (visible once moving)
            if (uiState.isSessionRunning && (metrics.jumpCount > 0 || uiState.beatSync.detectedBpm > 0)) {
                Spacer(Modifier.height(12.dp))
                val formScore = uiState.formCoach.overallScore
                val bpm = uiState.beatSync.detectedBpm
                val syncScore = uiState.beatSync.beatSyncScore
                val badgeText = buildString {
                    append(uiState.currentMove.label.uppercase(Locale.US))
                    if (formScore > 0) append("  •  FORM $formScore%")
                    if (bpm > 0 && isShuffleMode) {
                        append("  •  $bpm BPM")
                        if (syncScore > 0) append(" ($syncScore%)")
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = MidnightGlass,
                    border = BorderStroke(1.dp, IceMint.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = badgeText,
                        color = IceMint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }

                // Live Ghost Rival Split & Plyometrics Force-Plate Strip
                val showGhost = uiState.ghostProfile != GhostProfile.OFF && uiState.ghostSnapshot.statusText.isNotBlank()
                val showPlyo = uiState.plyometrics.avgJumpHeightCm > 0.2f && uiState.plyometrics.avgGctMs > 0
                if (showGhost || showPlyo) {
                    Spacer(Modifier.height(6.dp))
                    val proHudText = buildString {
                        if (showGhost) append(uiState.ghostSnapshot.statusText)
                        if (showGhost && showPlyo) append("  •  ")
                        if (showPlyo) {
                            append(
                                String.format(
                                    Locale.US,
                                    "%.1fcm • %dms GCT • RSI %.2f",
                                    uiState.plyometrics.avgJumpHeightCm,
                                    uiState.plyometrics.avgGctMs,
                                    uiState.plyometrics.reactiveStrengthIndex
                                )
                            )
                        }
                    }
                    Text(
                        text = proHudText,
                        color = if (showGhost && uiState.ghostSnapshot.deltaJumps < 0) Color(0xFFFB7185) else IceMint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Live Trip Root-Cause Autopsy Callout
                uiState.tripAutopsy?.let { trip ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Trip Autopsy: ${trip.headline}",
                        color = Color(0xFFFB7185),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (formScore in 1..79) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = uiState.formCoach.coachingTip,
                        color = TextPrimary.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // LOWER SYMMETRICAL STAT STRIP (more translucent when Skeleton Overlay is ON so feet stay clear!)
            val timerMs = when (config.mode) {
                WorkoutModeType.TARGET_TIME,
                WorkoutModeType.HIIT,
                WorkoutModeType.IJRU_SPEED_30S,
                WorkoutModeType.IJRU_ENDURANCE_180S -> progress.remainingMs ?: metrics.elapsedMs
                else -> metrics.elapsedMs
            }

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = if (uiState.showSkeletonOverlay) DeepMidnight.copy(alpha = 0.52f) else MidnightGlass,
                border = BorderStroke(1.dp, MidnightBorder.copy(alpha = if (uiState.showSkeletonOverlay) 0.6f else 1f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${metrics.currentRpm}",
                            color = TextPrimary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
                        )
                        Text(
                            text = "RPM",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Text(
                        text = formatElapsedTimer(timerMs),
                        color = TextPrimary,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
                    )

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = String.format(Locale.US, "%.0f", metrics.estimatedCalories),
                            color = TextPrimary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
                        )
                        Text(
                            text = "kcal",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // CIRCULAR PRIMARY PLAY/PAUSE BUTTON + SECONDARY COUNTDOWN / RESET & STOP BUTTONS
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left slot: Reset (when paused with progress) OR quick 3s/5s/8s Countdown pill (when idle)
                    Box(
                        modifier = Modifier.width(72.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!uiState.isSessionRunning && uiState.hasActiveProgress) {
                            CircularHudIconButton(
                                icon = Icons.Rounded.Refresh,
                                contentDescription = "Reset",
                                onClick = onResetSession,
                                sizeDp = 48
                            )
                        } else if (!uiState.isSessionRunning) {
                            Surface(
                                onClick = onCycleCountdown,
                                shape = CircleShape,
                                color = MidnightGlass,
                                border = BorderStroke(
                                    1.dp,
                                    if (uiState.startCountdownDurationSec > 0) IceMint.copy(alpha = 0.5f) else MidnightBorder
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Timer,
                                        contentDescription = "Start Countdown",
                                        tint = if (uiState.startCountdownDurationSec > 0) IceMint else TextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = if (uiState.startCountdownDurationSec > 0) {
                                            "${uiState.startCountdownDurationSec}s"
                                        } else {
                                            "Off"
                                        },
                                        color = if (uiState.startCountdownDurationSec > 0) TextPrimary else TextSecondary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.width(16.dp))

                    // Center: Prominent Circular Ice Mint Play / Pause Button
                    Surface(
                        onClick = onToggleSession,
                        shape = CircleShape,
                        color = IceMint,
                        shadowElevation = 10.dp,
                        modifier = Modifier.size(72.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (uiState.isSessionRunning) {
                                    Icons.Rounded.Pause
                                } else {
                                    Icons.Rounded.PlayArrow
                                },
                                contentDescription = if (uiState.isSessionRunning) "Pause" else "Start",
                                tint = DeepMidnight,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(16.dp))

                    // Right slot: Stop / Finish (visible when active progress exists)
                    Box(
                        modifier = Modifier.width(72.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (uiState.hasActiveProgress) {
                            CircularHudIconButton(
                                icon = Icons.Rounded.Stop,
                                contentDescription = "Finish",
                                onClick = onFinishSession,
                                sizeDp = 48
                            )
                        }
                    }
                }
            }

            // Subtle hint when controls & toolbar are hidden during active session
            if (uiState.isSessionRunning && !uiState.isControlsVisible && !uiState.isCountingDown) {
                Text(
                    text = "Tap screen to show toolbar",
                    color = TextSecondary.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = 8.dp)
                )
            }
        }

        // 7. Full-Screen Pre-Start Countdown Overlay (3s / 5s / 8s after pressing Play)
        if (uiState.isCountingDown) {
            PreStartCountdownOverlay(
                remainingSeconds = uiState.preStartCountdownRemaining,
                totalSeconds = uiState.startCountdownDurationSec.coerceAtLeast(1),
                onCancel = onCancelCountdown
            )
        }
    }
}

@Composable
private fun DynamicProgressRingCounter(
    jumpCount: Int,
    progress: Float,
    subLabel: String,
    isAirborne: Boolean,
    scaleFactor: Float
) {
    Box(
        modifier = Modifier.size(236.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = 7.5.dp.toPx()
            val glowStrokePx = 18.dp.toPx()
            val inset = glowStrokePx / 2f
            val arcSize = Size(size.width - glowStrokePx, size.height - glowStrokePx)
            val topLeft = Offset(inset, inset)

            // Soft radial ambient glow behind the ring
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        IceMint.copy(alpha = if (isAirborne) 0.22f else 0.12f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = size.minDimension * 0.56f
                )
            )

            // Subtle dark track ring
            drawArc(
                color = Color.White.copy(alpha = 0.14f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )

            val sweep = (progress.coerceIn(0f, 1f) * 360f).coerceAtLeast(if (jumpCount > 0) 6f else 0f)
            if (sweep > 0f) {
                // Soft Ice Mint outer halo on active arc
                drawArc(
                    color = IceMintGlow,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = glowStrokePx * 0.72f, cap = StrokeCap.Round)
                )
                // Crisp Ice Mint progress arc
                drawArc(
                    color = IceMint,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )

                // Leading bright tip dot
                val angleRad = ((-90f + sweep) * PI / 180.0)
                val radius = arcSize.width / 2f
                val tipX = center.x + (radius * cos(angleRad)).toFloat()
                val tipY = center.y + (radius * sin(angleRad)).toFloat()
                drawCircle(
                    color = Color.White,
                    radius = strokePx * 0.42f,
                    center = Offset(tipX, tipY)
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.scale(scaleFactor)
        ) {
            Text(
                text = "$jumpCount",
                color = TextPrimary,
                fontSize = 72.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-2).sp,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
            )
            Text(
                text = subLabel,
                color = if (subLabel.startsWith("WORK") || subLabel.startsWith("REST")) {
                    IceMint
                } else {
                    TextPrimary.copy(alpha = 0.78f)
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.6.sp
            )
        }
    }
}

@Composable
private fun CircularHudIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    sizeDp: Int = 44
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MidnightGlass,
        border = BorderStroke(1.dp, MidnightBorder),
        modifier = Modifier.size(sizeDp.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = TextPrimary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun TwoToneBottomNavBar(
    selectedTab: MainTab,
    onSelectTab: (MainTab) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DeepMidnight)
            .navigationBarsPadding()
    ) {
        HorizontalDivider(color = MidnightBorder, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MainTab.entries.forEach { tab ->
                val isSelected = tab == selectedTab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelectTab(tab) },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .width(40.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp))
                                .background(IceMint)
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = if (isSelected) IceMint else TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = tab.label,
                            color = if (isSelected) IceMint else TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IsolatedPoseOverlay(
    latestPoseFlow: StateFlow<PoseFrame?>,
    isAirborne: Boolean,
    modifier: Modifier = Modifier
) {
    val latestPose by latestPoseFlow.collectAsStateWithLifecycle()
    PoseOverlay(
        poseFrame = latestPose,
        isAirborne = isAirborne,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun WorkoutSettingsSheet(
    uiState: JumpRopeUiState,
    onCycleSensorMode: () -> Unit,
    onSelectCountdown: (Int) -> Unit,
    onToggleGestureControl: () -> Unit,
    onToggleDoubleClap: () -> Unit = {},
    onUpdateAthleteProfile: (Int, RopeType, Int) -> Unit,
    onCyclePacemakerMode: () -> Unit,
    onSetPacemakerRpm: (Int) -> Unit,
    onCycleGhostProfile: () -> Unit,
    onOpenHeartRateScanner: () -> Unit,
    onOpenSkillAcademy: () -> Unit,
    onStartSplitBattle: () -> Unit = {},
    onToggleSkeleton: () -> Unit,
    onToggleAudio: () -> Unit,
    onToggleEcoMode: () -> Unit,
    onToggleAutoHideControls: () -> Unit,
    onToggleDiagnostics: () -> Unit,
    onExportDiagnosticLog: () -> Boolean,
    onCheckForUpdates: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DeepMidnight,
        contentColor = TextPrimary,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MidnightBorder)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Workout Settings",
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            // Athlete Profile & Rope Setup Card (Body Weight Slider + Rope Type + Daily Goal)
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MidnightSurface,
                border = BorderStroke(1.dp, MidnightBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Person,
                            contentDescription = null,
                            tint = IceMint,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Athlete Profile & Rope",
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Used for accurate calorie burn & daily target",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    // Body Weight (dedicated spacious row with smooth 1 kg Slider + ±1 kg fine buttons)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Body Weight",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Surface(
                                shape = CircleShape,
                                color = IceMintSoft,
                                border = BorderStroke(1.dp, IceMint.copy(alpha = 0.45f))
                            ) {
                                Text(
                                    text = "${uiState.userWeightKg} kg",
                                    color = IceMint,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                onClick = {
                                    onUpdateAthleteProfile(
                                        (uiState.userWeightKg - 1).coerceAtLeast(35),
                                        uiState.ropeType,
                                        uiState.dailyGoalJumps
                                    )
                                },
                                shape = CircleShape,
                                color = MidnightElevated,
                                border = BorderStroke(1.dp, MidnightBorder),
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "−",
                                        color = TextPrimary,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Slider(
                                value = uiState.userWeightKg.toFloat().coerceIn(35f, 150f),
                                onValueChange = { raw ->
                                    val snapped = raw.roundToInt().coerceIn(35, 150)
                                    if (snapped != uiState.userWeightKg) {
                                        onUpdateAthleteProfile(snapped, uiState.ropeType, uiState.dailyGoalJumps)
                                    }
                                },
                                valueRange = 35f..150f,
                                colors = SliderDefaults.colors(
                                    thumbColor = IceMint,
                                    activeTrackColor = IceMint,
                                    inactiveTrackColor = MidnightElevated
                                ),
                                modifier = Modifier.weight(1f)
                            )

                            Surface(
                                onClick = {
                                    onUpdateAthleteProfile(
                                        (uiState.userWeightKg + 1).coerceAtMost(150),
                                        uiState.ropeType,
                                        uiState.dailyGoalJumps
                                    )
                                },
                                shape = CircleShape,
                                color = MidnightElevated,
                                border = BorderStroke(1.dp, MidnightBorder),
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "+",
                                        color = TextPrimary,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // Rope Type selector
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Rope Type",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            RopeType.entries.forEach { rope ->
                                val selected = uiState.ropeType == rope
                                Surface(
                                    onClick = {
                                        onUpdateAthleteProfile(uiState.userWeightKg, rope, uiState.dailyGoalJumps)
                                    },
                                    shape = CircleShape,
                                    color = if (selected) IceMint else MidnightElevated,
                                    border = BorderStroke(1.dp, if (selected) IceMint else MidnightBorder)
                                ) {
                                    Text(
                                        text = rope.label,
                                        color = if (selected) DeepMidnight else TextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Daily Jump Goal selector
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Daily Jump Goal",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(250 to "250", 500 to "500", 1000 to "1,000", 2000 to "2,000").forEach { (goal, label) ->
                                val selected = uiState.dailyGoalJumps == goal
                                Surface(
                                    onClick = {
                                        onUpdateAthleteProfile(uiState.userWeightKg, uiState.ropeType, goal)
                                    },
                                    shape = CircleShape,
                                    color = if (selected) IceMintSoft else MidnightElevated,
                                    border = BorderStroke(1.dp, if (selected) IceMint else MidnightBorder),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = label,
                                        color = if (selected) IceMint else TextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            SettingsSwitchCard(
                icon = Icons.Rounded.PanTool,
                title = "Touchless Hand-Gesture Control",
                subtitle = "Raise 1 hand above head for 1.2s to Start or Pause hands-free",
                checked = uiState.isGestureControlEnabled,
                onCheckedChange = { onToggleGestureControl() }
            )

            SettingsSwitchCard(
                icon = Icons.Rounded.GraphicEq,
                title = "Double-Clap Start / Pause (5m+)",
                subtitle = "Clap twice crisply from across the gym to Start or Pause hands-free",
                checked = uiState.isDoubleClapEnabled,
                onCheckedChange = { onToggleDoubleClap() }
            )

            // Pre-Start Countdown Selector (Off / 3s / 5s / 8s)
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MidnightSurface,
                border = BorderStroke(1.dp, MidnightBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Timer,
                            contentDescription = null,
                            tint = IceMint,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Start Countdown",
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Delay after pressing Play to get in position",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(0 to "Off", 3 to "3s", 5 to "5s", 8 to "8s").forEach { (sec, label) ->
                            val selected = uiState.startCountdownDurationSec == sec
                            Surface(
                                onClick = { onSelectCountdown(sec) },
                                shape = CircleShape,
                                color = if (selected) IceMint else MidnightElevated,
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) IceMint else MidnightBorder
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = label,
                                    color = if (selected) DeepMidnight else TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Sensor Mode Row (click to cycle)
            Surface(
                onClick = onCycleSensorMode,
                shape = RoundedCornerShape(18.dp),
                color = MidnightSurface,
                border = BorderStroke(1.dp, MidnightBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = IceMint,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AI Sensor Mode",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Tap to switch • ${uiState.fingerprint.shortBadge()}",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = IceMintSoft
                    ) {
                        Text(
                            text = uiState.sensorMode.label,
                            color = IceMint,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // Phone-Only Pro Suite (v4.2 – v6.0): Audio Pacemaker, Ghost Rival, Camera rPPG & Skill Academy
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MidnightSurface,
                border = BorderStroke(1.dp, IceMint.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Phone-Only Pro Suite",
                            color = IceMint,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Thermal: ${uiState.thermalTier.label}",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            onClick = onCyclePacemakerMode,
                            shape = RoundedCornerShape(12.dp),
                            color = MidnightElevated,
                            border = BorderStroke(1.dp, MidnightBorder),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Audio Pacemaker", color = TextSecondary, fontSize = 10.5.sp)
                                Text(
                                    text = "${uiState.pacemakerMode.label} (${uiState.pacemakerRpm})",
                                    color = IceMint,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Surface(
                            onClick = onCycleGhostProfile,
                            shape = RoundedCornerShape(12.dp),
                            color = MidnightElevated,
                            border = BorderStroke(1.dp, MidnightBorder),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Ghost Rival Pacer", color = TextSecondary, fontSize = 10.5.sp)
                                Text(
                                    text = uiState.ghostProfile.label,
                                    color = IceMint,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Slider(
                        value = uiState.pacemakerRpm.toFloat(),
                        onValueChange = { onSetPacemakerRpm(it.roundToInt()) },
                        valueRange = 100f..420f,
                        colors = SliderDefaults.colors(
                            thumbColor = IceMint,
                            activeTrackColor = IceMint,
                            inactiveTrackColor = MidnightElevated
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenHeartRateScanner,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, IceMint.copy(alpha = 0.6f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Camera rPPG HR", color = IceMint, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onOpenSkillAcademy,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MidnightBorder),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("AI Skill Tree", color = TextPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedButton(
                        onClick = onStartSplitBattle,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, IceMint.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (uiState.battleState.isActive) "Close 2-Player Split Battle" else "Start 2-Player Split-Screen Battle",
                            color = IceMint,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            SettingsSwitchCard(
                icon = Icons.Rounded.AccessibilityNew,
                title = "Pose Skeleton Overlay",
                subtitle = "Display AI body skeleton lines on camera",
                checked = uiState.showSkeletonOverlay,
                onCheckedChange = { onToggleSkeleton() }
            )

            SettingsSwitchCard(
                icon = Icons.AutoMirrored.Rounded.VolumeUp,
                title = "Sound & Audio Cues",
                subtitle = "Countdown beeps, milestones, and HIIT transitions",
                checked = uiState.isAudioEnabled,
                onCheckedChange = { onToggleAudio() }
            )

            SettingsSwitchCard(
                icon = Icons.Rounded.Bedtime,
                title = "AMOLED Eco Mode",
                subtitle = "Dim screen to pure black to save battery",
                checked = uiState.isEcoMode,
                onCheckedChange = { onToggleEcoMode() }
            )

            SettingsSwitchCard(
                icon = Icons.Rounded.VisibilityOff,
                title = "Hide Toolbar While Active",
                subtitle = "Auto-hide top & bottom bars during workout (tap screen to toggle)",
                checked = uiState.autoHideControlsWhileActive,
                onCheckedChange = { onToggleAutoHideControls() }
            )

            SettingsSwitchCard(
                icon = Icons.Rounded.GraphicEq,
                title = "AI Performance & Full CSV Log",
                subtitle = "Show live FPS/CPU/RAM badge & record session telemetry",
                checked = uiState.isDiagnosticsEnabled,
                onCheckedChange = { onToggleDiagnostics() }
            )

            if (uiState.isDiagnosticsEnabled || uiState.perfStats.loggedRows > 0) {
                var logExportNote by remember { mutableStateOf<String?>(null) }
                Surface(
                    onClick = {
                        val ok = onExportDiagnosticLog()
                        logExportNote = if (ok) {
                            "Log exported (${uiState.perfStats.loggedRows} frames)"
                        } else {
                            "Start a workout first to record log data"
                        }
                    },
                    shape = RoundedCornerShape(18.dp),
                    color = MidnightSurface,
                    border = BorderStroke(1.dp, IceMint.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            tint = IceMint,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Export Diagnostic Log (CSV)",
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = logExportNote
                                    ?: uiState.perfStats.lastSavedLogPath?.let { "Auto-saved: $it" }
                                    ?: "${uiState.perfStats.loggedRows} frames recorded • Auto-saves to Documents/Jumpvex/DebugLogs",
                                color = IceMint,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Surface(
                            shape = CircleShape,
                            color = IceMintSoft
                        ) {
                            Text(
                                text = "Share",
                                color = IceMint,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            // In-App GitHub Release Update Checker Row
            val hasUpdate = uiState.availableUpdate != null
            Surface(
                onClick = onCheckForUpdates,
                shape = RoundedCornerShape(18.dp),
                color = MidnightSurface,
                border = BorderStroke(1.dp, if (hasUpdate) IceMint.copy(alpha = 0.65f) else MidnightBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SystemUpdate,
                        contentDescription = null,
                        tint = if (hasUpdate) IceMint else TextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (hasUpdate) {
                                "Update Ready • v${uiState.availableUpdate?.versionName}"
                            } else {
                                "App Updates (v${BuildConfig.VERSION_NAME})"
                            },
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = uiState.updateStatusMessage ?: "Check GitHub Releases for latest APK",
                            color = if (hasUpdate) IceMint else TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    if (uiState.isCheckingUpdate) {
                        CircularProgressIndicator(
                            color = IceMint,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Surface(
                            shape = CircleShape,
                            color = if (hasUpdate) IceMint else IceMintSoft
                        ) {
                            Text(
                                text = if (hasUpdate) "Update" else "Check",
                                color = if (hasUpdate) DeepMidnight else IceMint,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSwitchCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MidnightSurface,
        border = BorderStroke(1.dp, MidnightBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) IceMint else TextSecondary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = DeepMidnight,
                    checkedTrackColor = IceMint,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = MidnightElevated,
                    uncheckedBorderColor = MidnightBorder
                )
            )
        }
    }
}

@Composable
private fun EcoModeOverlay(
    uiState: JumpRopeUiState,
    onToggleSession: () -> Unit,
    onExitEco: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onExitEco() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "AMOLED ECO MODE",
                color = IceMint.copy(alpha = 0.65f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "${uiState.metrics.jumpCount}",
                color = IceMint.copy(alpha = 0.85f),
                fontSize = 84.sp,
                fontWeight = FontWeight.ExtraBold,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${uiState.metrics.currentRpm} RPM  •  ${formatElapsedTimer(uiState.metrics.elapsedMs)}",
                color = TextSecondary,
                fontSize = 15.sp,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
            )
            Spacer(Modifier.height(36.dp))
            Surface(
                onClick = onToggleSession,
                shape = CircleShape,
                color = MidnightSurface,
                border = BorderStroke(1.dp, IceMint.copy(alpha = 0.4f)),
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (uiState.isSessionRunning) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = IceMint,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Tap anywhere to exit",
                color = TextSecondary.copy(alpha = 0.6f),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun CameraPermissionView(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepMidnight)
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MidnightSurface,
            border = BorderStroke(1.dp, MidnightBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = IceMintSoft,
                    modifier = Modifier.size(68.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Videocam,
                            contentDescription = null,
                            tint = IceMint,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "Camera & Microphone Access",
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Jumpvex needs camera and microphone access to count your jumps in real time. All AI processing runs 100% on your device.",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onRequestPermission,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = IceMint,
                        contentColor = DeepMidnight
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text(
                        text = "Grant Access",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun PreStartCountdownOverlay(
    remainingSeconds: Int,
    totalSeconds: Int,
    onCancel: () -> Unit
) {
    val progress = (remainingSeconds.toFloat() / totalSeconds.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
    val animatedSweep by animateFloatAsState(
        targetValue = progress * 360f,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "countdownSweep"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepMidnight.copy(alpha = 0.82f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* absorb clicks while counting down */ },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "GET READY",
                color = IceMint,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp
            )
            Spacer(Modifier.height(22.dp))
            Box(
                modifier = Modifier.size(216.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokePx = 8.dp.toPx()
                    val glowPx = 20.dp.toPx()
                    val inset = glowPx / 2f
                    val arcSize = Size(size.width - glowPx, size.height - glowPx)
                    val topLeft = Offset(inset, inset)

                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(IceMint.copy(alpha = 0.22f), Color.Transparent),
                            center = center,
                            radius = size.minDimension * 0.56f
                        )
                    )
                    drawArc(
                        color = Color.White.copy(alpha = 0.14f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = IceMintGlow,
                        startAngle = -90f,
                        sweepAngle = animatedSweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = glowPx * 0.72f, cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = IceMint,
                        startAngle = -90f,
                        sweepAngle = animatedSweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )
                }

                Text(
                    text = "$remainingSeconds",
                    color = TextPrimary,
                    fontSize = 86.sp,
                    fontWeight = FontWeight.ExtraBold,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = "Step into camera view",
                color = TextSecondary,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(28.dp))
            OutlinedButton(
                onClick = onCancel,
                shape = CircleShape,
                border = BorderStroke(1.dp, MidnightBorder)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Cancel",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun AppUpdateDialog(
    release: AppReleaseInfo,
    onDownloadApk: () -> Unit,
    onOpenReleasePage: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MidnightSurface,
            border = BorderStroke(1.dp, IceMint.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = IceMintSoft,
                    modifier = Modifier.size(60.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.SystemUpdate,
                            contentDescription = null,
                            tint = IceMint,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Jumpvex v${release.versionName} Available",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Installed: v${BuildConfig.VERSION_NAME}",
                    color = IceMint,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(14.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = DeepMidnight,
                    border = BorderStroke(1.dp, MidnightBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = release.releaseNotes,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(14.dp)
                    )
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onDownloadApk,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = IceMint,
                        contentColor = DeepMidnight
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (release.apkSizeMb > 0f) {
                            String.format(Locale.US, "Download Release APK (%.1f MB)", release.apkSizeMb)
                        } else {
                            "Download Release APK"
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onOpenReleasePage) {
                        Text("Release Notes", color = IceMint, fontSize = 13.sp)
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Later", color = TextSecondary, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

private fun formatElapsedTimer(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.openExternalUrl(url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }
}

private fun Context.resolveDisplayName(uri: Uri): String {
    return runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: "Video Recording"
}
