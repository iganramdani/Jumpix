package com.jumpropeai.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jumpropeai.ai.DetectorStatus
import com.jumpropeai.audio.AudioFeedbackManager
import com.jumpropeai.metrics.WorkoutMetrics
import com.jumpropeai.ui.components.CameraPreview
import com.jumpropeai.ui.components.PoseOverlay
import com.jumpropeai.ui.components.SessionSummaryDialog
import com.jumpropeai.ui.components.WorkoutHistorySheet
import com.jumpropeai.ui.components.WorkoutModeSheet
import com.jumpropeai.ui.theme.DarkSurface
import com.jumpropeai.ui.theme.ElectricCyan
import com.jumpropeai.ui.theme.FlameOrange
import com.jumpropeai.ui.theme.GlassBorder
import com.jumpropeai.ui.theme.GlassCard
import com.jumpropeai.ui.theme.GlassDark
import com.jumpropeai.ui.theme.MintGreen
import com.jumpropeai.ui.theme.NeonLime
import com.jumpropeai.ui.theme.SoftRed
import com.jumpropeai.workout.HiitPhase
import com.jumpropeai.workout.WorkoutConfig
import com.jumpropeai.workout.WorkoutModeType
import com.jumpropeai.workout.WorkoutProgress
import java.util.Locale

@Composable
fun JumpRopeScreen(viewModel: JumpRopeViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentAudioEnabled by rememberUpdatedState(uiState.isAudioEnabled)

    // Lifecycle-aware Audio & Voice Feedback manager
    val audioManager = remember { AudioFeedbackManager(context) }
    DisposableEffect(Unit) {
        onDispose { audioManager.release() }
    }
    LaunchedEffect(viewModel) {
        viewModel.audioCues.collect { cue ->
            audioManager.play(cue, enabled = currentAudioEnabled)
        }
    }

    // Dim screen brightness automatically in Eco Mode to dramatically save display battery
    DisposableEffect(uiState.isEcoMode) {
        val window = context.findActivity()?.window
        if (window != null) {
            val params = window.attributes
            params.screenBrightness = if (uiState.isEcoMode) {
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
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasCameraPermission) {
        CameraPermissionView(
            onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) }
        )
    } else {
        LiveWorkoutHud(
            uiState = uiState,
            onPoseFrame = viewModel::onPoseFrame,
            onToggleSession = viewModel::toggleSession,
            onFinishSession = { viewModel.finishAndSaveSession(completedByTarget = false) },
            onResetSession = viewModel::resetSession,
            onToggleCamera = viewModel::toggleCamera,
            onToggleSkeleton = viewModel::toggleSkeletonOverlay,
            onToggleAudio = viewModel::toggleAudio,
            onToggleEcoMode = viewModel::toggleEcoMode,
            onToggleControls = viewModel::toggleControlsVisibility,
            onOpenHistory = { viewModel.setShowHistorySheet(true) },
            onOpenModeSheet = { viewModel.setShowModeSheet(true) }
        )

        if (uiState.showModeSheet) {
            WorkoutModeSheet(
                currentConfig = uiState.workoutConfig,
                onApply = viewModel::updateWorkoutConfig,
                onDismiss = { viewModel.setShowModeSheet(false) }
            )
        }

        if (uiState.showHistorySheet) {
            WorkoutHistorySheet(
                stats = uiState.cumulativeStats,
                sessions = uiState.historySessions,
                onDeleteSession = viewModel::deleteHistorySession,
                onDismiss = { viewModel.setShowHistorySheet(false) }
            )
        }

        uiState.completedSessionSummary?.let { summary ->
            SessionSummaryDialog(
                session = summary,
                onDismiss = viewModel::dismissSummaryDialog
            )
        }
    }
}

@Composable
private fun LiveWorkoutHud(
    uiState: JumpRopeUiState,
    onPoseFrame: (com.jumpropeai.ai.PoseFrame) -> Unit,
    onToggleSession: () -> Unit,
    onFinishSession: () -> Unit,
    onResetSession: () -> Unit,
    onToggleCamera: () -> Unit,
    onToggleSkeleton: () -> Unit,
    onToggleAudio: () -> Unit,
    onToggleEcoMode: () -> Unit,
    onToggleControls: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenModeSheet: () -> Unit
) {
    val metrics = uiState.metrics
    val config = uiState.workoutConfig
    val progress = uiState.workoutProgress
    val showFullControls = !uiState.isSessionRunning || uiState.isControlsVisible

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
        // 1. CameraX host: unbinds Preview in Eco Mode while keeping AI ImageAnalysis at full 30 FPS
        CameraPreview(
            lensFacing = uiState.lensFacing,
            isPreviewEnabled = !uiState.isEcoMode,
            isSessionRunning = uiState.isSessionRunning,
            onPoseFrame = onPoseFrame,
            modifier = Modifier.fillMaxSize()
        )

        // 2. Optional Skeleton Overlay (skipped completely in Eco Mode)
        if (uiState.showSkeletonOverlay && !uiState.isEcoMode) {
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
                onFinishSession = onFinishSession,
                onExitEcoMode = onToggleEcoMode
            )
            return@Box
        }

        // 4. Subtle Scrims (only when controls visible or for gentle readability)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0x99000000), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xCC000000))
                    )
                )
        )

        // 5. Minimal Focus Foreground HUD
        val counterScale by animateFloatAsState(
            targetValue = if (metrics.isAirborne) 1.07f else 1.0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "jumpCounterScale"
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // TOP AREA: Ultra-clean bar that auto-hides secondary buttons while running
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AiStatusPill(
                        status = metrics.detectorStatus,
                        isSessionRunning = uiState.isSessionRunning,
                        calibrationProgress = metrics.calibrationProgress
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // 1-tap Eco / Hide Camera pill (always accessible for instant battery saving)
                        Surface(
                            onClick = onToggleEcoMode,
                            shape = CircleShape,
                            color = GlassDark,
                            border = BorderStroke(1.dp, NeonLime.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Bedtime,
                                    contentDescription = "Mode Hemat Baterai",
                                    tint = NeonLime,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Eco",
                                    color = NeonLime,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = showFullControls,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                HudIconButton(
                                    onClick = onToggleAudio,
                                    active = uiState.isAudioEnabled,
                                    activeColor = NeonLime,
                                    icon = if (uiState.isAudioEnabled) {
                                        Icons.AutoMirrored.Rounded.VolumeUp
                                    } else {
                                        Icons.AutoMirrored.Rounded.VolumeOff
                                    },
                                    contentDescription = "Audio"
                                )
                                HudIconButton(
                                    onClick = onToggleSkeleton,
                                    active = uiState.showSkeletonOverlay,
                                    activeColor = ElectricCyan,
                                    icon = if (uiState.showSkeletonOverlay) {
                                        Icons.Rounded.AccessibilityNew
                                    } else {
                                        Icons.Rounded.VisibilityOff
                                    },
                                    contentDescription = "Skeleton"
                                )
                                HudIconButton(
                                    onClick = onOpenHistory,
                                    active = false,
                                    activeColor = Color.White,
                                    icon = Icons.Rounded.History,
                                    contentDescription = "Riwayat"
                                )
                                HudIconButton(
                                    onClick = onToggleCamera,
                                    active = false,
                                    activeColor = Color.White,
                                    icon = Icons.Rounded.Cameraswitch,
                                    contentDescription = "Kamera"
                                )
                            }
                        }
                    }
                }

                // Mode selector pill (hidden while running to keep HUD distraction-free)
                AnimatedVisibility(
                    visible = !uiState.isSessionRunning,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            onClick = onOpenModeSheet,
                            shape = CircleShape,
                            color = GlassDark,
                            border = BorderStroke(
                                1.dp,
                                if (config.mode != WorkoutModeType.FREE) ElectricCyan.copy(alpha = 0.55f) else GlassBorder
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.FitnessCenter,
                                    contentDescription = null,
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = config.shortLabel(),
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Icon(
                                    imageVector = Icons.Rounded.Tune,
                                    contentDescription = "Ubah Mode",
                                    tint = Color.White.copy(alpha = 0.65f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                if (config.mode != WorkoutModeType.FREE) {
                    WorkoutProgressBanner(
                        config = config,
                        progress = progress,
                        jumpCount = metrics.jumpCount
                    )
                }
            }

            // CENTER: Minimal Hero Jump Counter + Compact 1-Line Live Metrics
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (config.mode == WorkoutModeType.HIIT && progress.hiitPhase == HiitPhase.REST) {
                    Text(
                        text = "ISTIRAHAT",
                        color = ElectricCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp
                    )
                }

                Text(
                    text = metrics.jumpCount.toString(),
                    color = if (metrics.isAirborne) NeonLime else Color.White,
                    fontSize = 92.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 94.sp,
                    modifier = Modifier.scale(counterScale)
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Ultra-clean single-line metrics strip
                CompactMetricsStrip(metrics = metrics)
            }

            // BOTTOM: Minimal controls (fades to subtle Pause pill while running; tap screen to reveal)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (uiState.isSessionRunning && !showFullControls) {
                    Text(
                        text = "Ketuk layar untuk kontrol • Eco untuk hemat baterai",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 11.sp
                    )
                    Surface(
                        onClick = onToggleSession,
                        shape = CircleShape,
                        color = GlassDark,
                        border = BorderStroke(1.dp, GlassBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Pause,
                                contentDescription = "Jeda",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "JEDA",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            onClick = onResetSession,
                            shape = RoundedCornerShape(18.dp),
                            color = GlassCard,
                            border = BorderStroke(1.dp, GlassBorder),
                            modifier = Modifier.size(56.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = "Reset",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        Button(
                            onClick = onToggleSession,
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (uiState.isSessionRunning) SoftRed else NeonLime,
                                contentColor = if (uiState.isSessionRunning) Color.White else Color(0xFF0A0D10)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                        ) {
                            Icon(
                                imageVector = if (uiState.isSessionRunning) {
                                    Icons.Rounded.Pause
                                } else {
                                    Icons.Rounded.PlayArrow
                                },
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (uiState.isSessionRunning) "JEDA" else "MULAI",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp
                            )
                        }

                        if (uiState.hasActiveProgress) {
                            Surface(
                                onClick = onFinishSession,
                                shape = RoundedCornerShape(18.dp),
                                color = MintGreen.copy(alpha = 0.22f),
                                border = BorderStroke(1.dp, MintGreen.copy(alpha = 0.7f)),
                                modifier = Modifier.size(56.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "Selesai & Simpan",
                                        tint = MintGreen,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Pure #000000 AMOLED Blackout UI for maximum battery savings.
 * Camera preview is completely unbound while AI ImageAnalysis continues at full 25-30 FPS.
 */
@Composable
private fun EcoModeOverlay(
    uiState: JumpRopeUiState,
    onToggleSession: () -> Unit,
    onFinishSession: () -> Unit,
    onExitEcoMode: () -> Unit
) {
    val metrics = uiState.metrics
    val config = uiState.workoutConfig
    val progress = uiState.workoutProgress
    val dimText = Color(0xFF8B949E)
    val dimAccent = NeonLime.copy(alpha = 0.78f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Minimal top status indicator
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        when (metrics.detectorStatus) {
                            DetectorStatus.NO_PERSON -> SoftRed.copy(alpha = 0.75f)
                            DetectorStatus.LOW_CONFIDENCE -> FlameOrange.copy(alpha = 0.75f)
                            else -> dimAccent
                        }
                    )
            )
            Text(
                text = when (metrics.detectorStatus) {
                    DetectorStatus.NO_PERSON -> "ECO • Posisikan badan di depan kamera"
                    DetectorStatus.LOW_CONFIDENCE -> "ECO • Pose kurang jelas"
                    else -> "ECO MODE • AI AKTIF (KAMERA TERSEMBUNYI)"
                },
                color = dimText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            )
        }

        // Center low-power AMOLED digits
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (config.mode != WorkoutModeType.FREE) {
                val info = when (config.mode) {
                    WorkoutModeType.TARGET_JUMPS -> "${metrics.jumpCount} / ${config.targetJumps}"
                    WorkoutModeType.TARGET_TIME -> "Sisa ${formatDuration(progress.remainingMs ?: 0L)}"
                    WorkoutModeType.HIIT -> {
                        val ph = if (progress.hiitPhase == HiitPhase.WORK) "LOMPAT" else "ISTIRAHAT"
                        "R${progress.currentRound}/${progress.totalRounds} • $ph ${formatDuration(progress.remainingMs ?: 0L)}"
                    }
                    WorkoutModeType.FREE -> ""
                }
                Text(
                    text = info,
                    color = ElectricCyan.copy(alpha = 0.75f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = metrics.jumpCount.toString(),
                color = if (metrics.isAirborne) dimAccent else Color(0xFFD0D7DE),
                fontSize = 112.sp,
                fontWeight = FontWeight.Black,
                lineHeight = 112.sp
            )

            Text(
                text = "${formatDuration(metrics.elapsedMs)}  •  ${metrics.currentRpm} RPM  •  ${String.format(Locale.US, "%.1f", metrics.estimatedCalories)} kkal  •  Streak ${metrics.currentStreak}",
                color = dimText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Minimal bottom controls
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = onExitEcoMode,
                shape = CircleShape,
                color = Color(0xFF11151C),
                border = BorderStroke(1.dp, Color(0xFF262C36))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Visibility,
                        contentDescription = "Tampilkan Kamera",
                        tint = dimText,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Kamera",
                        color = dimText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Surface(
                onClick = onToggleSession,
                shape = CircleShape,
                color = if (uiState.isSessionRunning) Color(0xFF2B1414) else Color(0xFF1E2610),
                border = BorderStroke(
                    1.dp,
                    if (uiState.isSessionRunning) SoftRed.copy(alpha = 0.5f) else dimAccent.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (uiState.isSessionRunning) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = if (uiState.isSessionRunning) SoftRed else dimAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = if (uiState.isSessionRunning) "Jeda" else "Mulai",
                        color = if (uiState.isSessionRunning) SoftRed else dimAccent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (uiState.hasActiveProgress) {
                Surface(
                    onClick = onFinishSession,
                    shape = CircleShape,
                    color = Color(0xFF0E2418),
                    border = BorderStroke(1.dp, MintGreen.copy(alpha = 0.45f))
                ) {
                    Box(
                        modifier = Modifier.padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "Selesai",
                            tint = MintGreen.copy(alpha = 0.8f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactMetricsStrip(metrics: WorkoutMetrics) {
    Surface(
        shape = CircleShape,
        color = GlassDark,
        border = BorderStroke(1.dp, GlassBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = formatDuration(metrics.elapsedMs),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            DotSeparator()
            Text(
                text = "${metrics.currentRpm} RPM",
                color = ElectricCyan,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            DotSeparator()
            Text(
                text = "${String.format(Locale.US, "%.1f", metrics.estimatedCalories)} kkal",
                color = FlameOrange,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            DotSeparator()
            Text(
                text = "Streak ${metrics.currentStreak}",
                color = NeonLime,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun DotSeparator() {
    Text(
        text = "•",
        color = Color.White.copy(alpha = 0.35f),
        fontSize = 13.sp
    )
}

@Composable
private fun HudIconButton(
    onClick: () -> Unit,
    active: Boolean,
    activeColor: Color,
    icon: ImageVector,
    contentDescription: String
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(38.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (active) activeColor.copy(alpha = 0.22f) else GlassDark,
            contentColor = if (active) activeColor else Color.White
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(19.dp)
        )
    }
}

@Composable
private fun WorkoutProgressBanner(
    config: WorkoutConfig,
    progress: WorkoutProgress,
    jumpCount: Int
) {
    val accentColor = when {
        config.mode == WorkoutModeType.HIIT && progress.hiitPhase == HiitPhase.REST -> ElectricCyan
        config.mode == WorkoutModeType.HIIT -> FlameOrange
        else -> NeonLime
    }

    val bannerText = when (config.mode) {
        WorkoutModeType.TARGET_JUMPS -> "$jumpCount / ${config.targetJumps} lompatan"
        WorkoutModeType.TARGET_TIME -> "Sisa ${formatDuration(progress.remainingMs ?: 0L)}"
        WorkoutModeType.HIIT -> {
            val phaseLabel = if (progress.hiitPhase == HiitPhase.WORK) "LOMPAT" else "ISTIRAHAT"
            "Ronde ${progress.currentRound}/${progress.totalRounds} • $phaseLabel ${formatDuration(progress.remainingMs ?: 0L)}"
        }
        WorkoutModeType.FREE -> ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = bannerText,
            color = accentColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold
        )
        LinearProgressIndicator(
            progress = { progress.progressFraction },
            color = accentColor,
            trackColor = Color.White.copy(alpha = 0.2f),
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(CircleShape)
        )
    }
}

@Composable
private fun AiStatusPill(
    status: DetectorStatus,
    isSessionRunning: Boolean,
    calibrationProgress: Float,
    modifier: Modifier = Modifier
) {
    val (dotColor, label) = when (status) {
        DetectorStatus.NO_PERSON -> SoftRed to "Posisikan badan"
        DetectorStatus.LOW_CONFIDENCE -> FlameOrange to "Mundur sedikit"
        DetectorStatus.CALIBRATING -> ElectricCyan to "Kalibrasi ${(calibrationProgress * 100).toInt()}%"
        DetectorStatus.READY -> MintGreen to "AI Siap"
        DetectorStatus.ACTIVE -> if (isSessionRunning) NeonLime to "AI Live" else MintGreen to "AI Siap"
    }

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = GlassDark,
        border = BorderStroke(1.dp, GlassBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = label,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun CameraPermissionView(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkSurface)
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = NeonLime.copy(alpha = 0.15f),
                modifier = Modifier.size(84.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Videocam,
                        contentDescription = null,
                        tint = NeonLime,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            Text(
                text = "JumpRope AI",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )

            Text(
                text = "Izinkan akses kamera agar AI dapat mendeteksi pose tubuh dan menghitung lompatan skiping secara real-time. Semua pemrosesan AI berjalan 100% langsung di perangkat Anda.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onRequestPermission,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonLime,
                    contentColor = Color(0xFF0A0D10)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text(
                    text = "IZINKAN KAMERA",
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun formatDuration(elapsedMs: Long): String {
    val totalSeconds = elapsedMs / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
