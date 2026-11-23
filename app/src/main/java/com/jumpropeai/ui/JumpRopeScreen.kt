package com.jumpropeai.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jumpropeai.ai.DetectorStatus
import com.jumpropeai.audio.AudioFeedbackManager
import com.jumpropeai.data.WorkoutSession
import com.jumpropeai.ui.components.CameraPreview
import com.jumpropeai.ui.components.HistoryTabScreen
import com.jumpropeai.ui.components.PoseOverlay
import com.jumpropeai.ui.components.SessionSummaryDialog
import com.jumpropeai.ui.components.ShareCardDialog
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
import com.jumpropeai.workout.HiitPhase
import com.jumpropeai.workout.WorkoutModeType
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private enum class MainTab(val label: String, val icon: ImageVector) {
    LATIHAN("Latihan", Icons.Rounded.FitnessCenter),
    VIDEO("Video", Icons.Rounded.Movie),
    RIWAYAT("Riwayat", Icons.Rounded.History)
}

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
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val name = context.resolveDisplayName(uri)
            selectedTab = MainTab.VIDEO
            viewModel.analyzeMp4Video(uri, name)
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

    var shareCardTarget by remember { mutableStateOf<Triple<WorkoutSession, Bitmap?, String?>?>(null) }

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

    val showBottomNav = !(selectedTab == MainTab.LATIHAN && uiState.isEcoMode)

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
                        isCameraAllowedOnTab = true,
                        onPoseFrame = viewModel::onPoseFrame,
                        onToggleSession = viewModel::toggleSession,
                        onFinishSession = { viewModel.finishAndSaveSession(completedByTarget = false) },
                        onResetSession = viewModel::resetSession,
                        onToggleCamera = viewModel::toggleCamera,
                        onToggleEcoMode = viewModel::toggleEcoMode,
                        onToggleControls = viewModel::toggleControlsVisibility,
                        onOpenModeSheet = { viewModel.setShowModeSheet(true) },
                        onOpenSettings = { showSettingsSheet = true }
                    )
                }

                MainTab.VIDEO -> {
                    VideoAnalyzerScreen(
                        isAnalyzing = uiState.isAnalyzingVideo,
                        progress = uiState.videoProgress,
                        result = uiState.videoResult,
                        errorMessage = uiState.videoError,
                        onPickVideo = { videoPickerLauncher.launch(arrayOf("video/*")) },
                        onShareResult = { res ->
                            val session = WorkoutSession(
                                id = "video_share",
                                timestampMillis = System.currentTimeMillis(),
                                modeLabel = "Video",
                                modeType = WorkoutModeType.FREE,
                                jumpCount = res.jumpCount,
                                durationMs = res.durationMs,
                                averageRpm = res.averageRpm,
                                peakRpm = res.peakRpm,
                                estimatedCalories = res.estimatedCalories,
                                bestStreak = res.bestStreak
                            )
                            shareCardTarget = Triple(session, res.finalPreviewBitmap, res.style.label)
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
                        sessions = uiState.historySessions,
                        onShareSession = { session -> shareCardTarget = Triple(session, null, null) },
                        onDeleteSession = viewModel::deleteHistorySession
                    )
                }
            }
        }

        if (showBottomNav) {
            TwoToneBottomNavBar(
                selectedTab = selectedTab,
                onSelectTab = { nextTab ->
                    if (nextTab == selectedTab) return@TwoToneBottomNavBar
                    if (selectedTab == MainTab.LATIHAN && uiState.isSessionRunning) {
                        viewModel.toggleSession()
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

    // Mode Selector Sheet (with all 4 Custom Inputs)
    if (uiState.showModeSheet) {
        WorkoutModeSheet(
            currentConfig = uiState.workoutConfig,
            onApply = viewModel::updateWorkoutConfig,
            onDismiss = { viewModel.setShowModeSheet(false) }
        )
    }

    // Clean Settings Bottom Sheet (all secondary HUD toggles)
    if (showSettingsSheet) {
        WorkoutSettingsSheet(
            uiState = uiState,
            onCycleSensorMode = viewModel::cycleSensorMode,
            onToggleSkeleton = viewModel::toggleSkeletonOverlay,
            onToggleAudio = viewModel::toggleAudio,
            onToggleEcoMode = {
                viewModel.toggleEcoMode()
                showSettingsSheet = false
            },
            onToggleControls = viewModel::toggleControlsVisibility,
            onDismiss = { showSettingsSheet = false }
        )
    }

    // Completed Session Summary Dialog
    uiState.completedSessionSummary?.let { summary ->
        SessionSummaryDialog(
            session = summary,
            onShareSession = { session -> shareCardTarget = Triple(session, null, uiState.jumpStyle.label) },
            onDismiss = viewModel::dismissSummaryDialog
        )
    }

    // Share Card & Video Watermark Studio Dialog
    shareCardTarget?.let { (session, backdrop, styleLabel) ->
        ShareCardDialog(
            session = session,
            backdropBitmap = backdrop,
            styleLabel = styleLabel,
            onDismiss = { shareCardTarget = null }
        )
    }
}

/**
 * Design 3 — Dynamic Ring HUD (Deep Midnight #0D1321 + Ice Mint #2EE59D).
 */
@Composable
private fun DynamicRingLiveHud(
    uiState: JumpRopeUiState,
    isCameraAllowedOnTab: Boolean,
    onPoseFrame: (com.jumpropeai.ai.PoseFrame) -> Unit,
    onToggleSession: () -> Unit,
    onFinishSession: () -> Unit,
    onResetSession: () -> Unit,
    onToggleCamera: () -> Unit,
    onToggleEcoMode: () -> Unit,
    onToggleControls: () -> Unit,
    onOpenModeSheet: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val metrics = uiState.metrics
    val config = uiState.workoutConfig
    val progress = uiState.workoutProgress
    val showControls = !uiState.isSessionRunning || uiState.isControlsVisible
    val isPureBlackMode = uiState.isEcoMode || uiState.sensorMode == SensorMode.AUDIO_ONLY

    // Compute intuitive ring progress for ALL 4 modes
    val rawRingProgress = when (config.mode) {
        WorkoutModeType.FREE -> {
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
                if (uiState.isSessionRunning && !uiState.isEcoMode) {
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

        // 2. Optional Two-Tone Skeleton Overlay
        if (uiState.showSkeletonOverlay && !isPureBlackMode) {
            PoseOverlay(
                poseFrame = uiState.latestPose,
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
                                contentDescription = "Pilih Mode",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularHudIconButton(
                            icon = Icons.Rounded.Settings,
                            contentDescription = "Pengaturan",
                            onClick = onOpenSettings
                        )
                        if (uiState.sensorMode != SensorMode.AUDIO_ONLY) {
                            CircularHudIconButton(
                                icon = Icons.Rounded.Cameraswitch,
                                contentDescription = "Ganti Kamera",
                                onClick = onToggleCamera
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.weight(0.55f))

            // UPPER-CENTER: Glowing Dynamic Circular Progress Ring + Giant Jump Counter
            val ringSubLabel = when {
                config.mode == WorkoutModeType.HIIT && uiState.isSessionRunning -> {
                    val phaseTxt = if (progress.hiitPhase == HiitPhase.WORK) "KERJA" else "ISTIRAHAT"
                    "$phaseTxt • R${progress.currentRound}/${progress.totalRounds}"
                }
                metrics.detectorStatus == DetectorStatus.NO_PERSON && uiState.sensorMode != SensorMode.AUDIO_ONLY ->
                    "POSISIKAN TUBUH"
                metrics.detectorStatus == DetectorStatus.CALIBRATING -> "KALIBRASI..."
                else -> "LOMPATAN"
            }

            DynamicProgressRingCounter(
                jumpCount = metrics.jumpCount,
                progress = animatedRingProgress,
                subLabel = ringSubLabel,
                isAirborne = metrics.isAirborne,
                scaleFactor = countScale
            )

            Spacer(Modifier.weight(1f))

            // LOWER SYMMETRICAL STAT STRIP (RPM • Waktu • Kalori)
            val timerMs = when (config.mode) {
                WorkoutModeType.TARGET_TIME, WorkoutModeType.HIIT -> progress.remainingMs ?: metrics.elapsedMs
                else -> metrics.elapsedMs
            }

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MidnightGlass,
                border = BorderStroke(1.dp, MidnightBorder),
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

            // CIRCULAR PRIMARY PLAY/PAUSE BUTTON + SECONDARY STOP & RESET BUTTONS
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
                    // Left slot: Reset (visible when paused with active progress)
                    Box(
                        modifier = Modifier.width(64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!uiState.isSessionRunning && uiState.hasActiveProgress) {
                            CircularHudIconButton(
                                icon = Icons.Rounded.Refresh,
                                contentDescription = "Reset",
                                onClick = onResetSession,
                                sizeDp = 48
                            )
                        }
                    }

                    Spacer(Modifier.width(18.dp))

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
                                contentDescription = if (uiState.isSessionRunning) "Jeda" else "Mulai",
                                tint = DeepMidnight,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(18.dp))

                    // Right slot: Stop / Finish (visible when active progress exists)
                    Box(
                        modifier = Modifier.width(64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (uiState.hasActiveProgress) {
                            CircularHudIconButton(
                                icon = Icons.Rounded.Stop,
                                contentDescription = "Selesai",
                                onClick = onFinishSession,
                                sizeDp = 48
                            )
                        }
                    }
                }
            }
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
                color = if (subLabel.startsWith("KERJA") || subLabel.startsWith("ISTIRAHAT")) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkoutSettingsSheet(
    uiState: JumpRopeUiState,
    onCycleSensorMode: () -> Unit,
    onToggleSkeleton: () -> Unit,
    onToggleAudio: () -> Unit,
    onToggleEcoMode: () -> Unit,
    onToggleControls: () -> Unit,
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
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Pengaturan Latihan",
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

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
                            text = "Mode Sensor AI",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Ketuk untuk mengganti • ${uiState.fingerprint.shortBadge()}",
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

            SettingsSwitchCard(
                icon = Icons.Rounded.AccessibilityNew,
                title = "Overlay Skeleton Pose",
                subtitle = "Tampilkan garis rangka tubuh AI di kamera",
                checked = uiState.showSkeletonOverlay,
                onCheckedChange = { onToggleSkeleton() }
            )

            SettingsSwitchCard(
                icon = Icons.AutoMirrored.Rounded.VolumeUp,
                title = "Suara & Cue Latihan",
                subtitle = "Bunyi milestone, hitungan HIIT, dan target tercapai",
                checked = uiState.isAudioEnabled,
                onCheckedChange = { onToggleAudio() }
            )

            SettingsSwitchCard(
                icon = Icons.Rounded.Bedtime,
                title = "Mode Hemat Layar AMOLED",
                subtitle = "Redupkan layar menjadi hitam pekat saat latihan",
                checked = uiState.isEcoMode,
                onCheckedChange = { onToggleEcoMode() }
            )

            SettingsSwitchCard(
                icon = Icons.Rounded.VisibilityOff,
                title = "Sembunyikan Kontrol saat Aktif",
                subtitle = "Ketuk layar kapan saja untuk menampilkan kembali",
                checked = !uiState.isControlsVisible,
                onCheckedChange = { onToggleControls() }
            )
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
                text = "MODE HEMAT AMOLED",
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
                text = "Ketuk di mana saja untuk kembali",
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
                    text = "Izin Kamera & Mikrofon",
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "JumpRope AI membutuhkan kamera dan mikrofon untuk mendeteksi lompatan secara real-time. Semua pemrosesan dilakukan 100% di perangkatmu.",
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
                        text = "Izinkan Akses",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
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

private fun Context.resolveDisplayName(uri: Uri): String {
    return runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: "Rekaman Video"
}
