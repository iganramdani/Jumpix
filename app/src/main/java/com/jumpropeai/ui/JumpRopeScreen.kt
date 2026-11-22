package com.jumpropeai.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Movie
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
import com.jumpropeai.ui.components.VideoAnalyzerScreen
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

    val audioManager = remember { AudioFeedbackManager(context) }
    DisposableEffect(Unit) {
        onDispose { audioManager.release() }
    }
    LaunchedEffect(viewModel) {
        viewModel.audioCues.collect { cue ->
            audioManager.play(cue, enabled = currentAudioEnabled)
        }
    }

    // Dim screen brightness in AMOLED Eco Mode or Audio-Only Eco Mode while running
    val shouldDimScreen = uiState.isEcoMode || (uiState.sensorMode == SensorMode.AUDIO_ONLY && uiState.isSessionRunning)
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

    val mp4PickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val name = context.resolveDisplayName(uri)
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

    if (!hasCameraPermission) {
        CameraPermissionView(
            onRequestPermission = {
                permissionsLauncher.launch(
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                )
            }
        )
    } else if (uiState.showVideoSheet) {
        VideoAnalyzerScreen(
            isAnalyzing = uiState.isAnalyzingVideo,
            progress = uiState.videoProgress,
            result = uiState.videoResult,
            errorMessage = uiState.videoError,
            onPickVideo = { mp4PickerLauncher.launch(arrayOf("video/mp4", "video/*")) },
            onSaveResultToHistory = viewModel::saveVideoResultToHistory,
            onClose = viewModel::closeVideoSheet
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
            onCycleSensorMode = viewModel::cycleSensorMode,
            onToggleControls = viewModel::toggleControlsVisibility,
            onOpenHistory = { viewModel.setShowHistorySheet(true) },
            onOpenModeSheet = { viewModel.setShowModeSheet(true) },
            onOpenVideoSheet = viewModel::openVideoSheet
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
    onCycleSensorMode: () -> Unit,
    onToggleControls: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenModeSheet: () -> Unit,
    onOpenVideoSheet: () -> Unit
) {
    val metrics = uiState.metrics
    val config = uiState.workoutConfig
    val progress = uiState.workoutProgress
    val showFullControls = !uiState.isSessionRunning || uiState.isControlsVisible
    val isPureBlackMode = uiState.isEcoMode || uiState.sensorMode == SensorMode.AUDIO_ONLY

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
        // 1. CameraX host (unbound completely in Audio-Only, headless in Eco Mode)
        CameraPreview(
            lensFacing = uiState.lensFacing,
            isCameraActive = uiState.isCameraActive,
            isPreviewEnabled = !uiState.isEcoMode,
            isSessionRunning = uiState.isSessionRunning,
            isDynamicEcoLowFps = uiState.isEcoMode && uiState.recommendEcoLowFps,
            onPoseFrame = onPoseFrame,
            modifier = Modifier.fillMaxSize()
        )

        // 2. Optional Skeleton Overlay
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
                onFinishSession = onFinishSession,
                onExitEcoMode = onToggleEcoMode
            )
            return@Box
        }

        // 4. Subtle Scrims when camera preview is visible
        if (uiState.isCameraActive) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(listOf(Color(0x9E000000), Color.Transparent))
                    )
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))
                    )
            )
        }

        // 5. Minimal & Fun Foreground HUD
        val counterScale by animateFloatAsState(
            targetValue = if (metrics.isAirborne) 1.09f else 1.0f,
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
            // TOP BAR: Fixed-position toolbar (never shifts!) + separate centered AI Status pill
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: Fixed-width Eco toggle pill (or spacer in Audio-Only)
                    if (uiState.isCameraActive) {
                        Surface(
                            onClick = onToggleEcoMode,
                            shape = CircleShape,
                            color = GlassDark,
                            border = BorderStroke(1.dp, NeonLime.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Bedtime,
                                    contentDescription = "Mode Eco",
                                    tint = NeonLime,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "Eco",
                                    color = NeonLime,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    // Right: Fixed icon toolbar (never pushed by status text!)
                    AnimatedVisibility(
                        visible = showFullControls,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HudIconButton(
                                onClick = onOpenVideoSheet,
                                active = true,
                                activeColor = ElectricCyan,
                                icon = Icons.Rounded.Movie,
                                contentDescription = "Hitung Video MP4"
                            )
                            HudIconButton(
                                onClick = onToggleAudio,
                                active = uiState.isAudioEnabled,
                                activeColor = NeonLime,
                                icon = if (uiState.isAudioEnabled) {
                                    Icons.AutoMirrored.Rounded.VolumeUp
                                } else {
                                    Icons.AutoMirrored.Rounded.VolumeOff
                                },
                                contentDescription = "Audio Coach"
                            )
                            if (uiState.isCameraActive) {
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
                            }
                            HudIconButton(
                                onClick = onOpenHistory,
                                active = false,
                                activeColor = Color.White,
                                icon = Icons.Rounded.History,
                                contentDescription = "Riwayat"
                            )
                            if (uiState.isCameraActive) {
                                HudIconButton(
                                    onClick = onToggleCamera,
                                    active = false,
                                    activeColor = Color.White,
                                    icon = Icons.Rounded.Cameraswitch,
                                    contentDescription = "Ganti Kamera"
                                )
                            }
                        }
                    }
                }

                // Dedicated centered AI Status pill — changing text never affects toolbar layout!
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    AiStatusPill(
                        sensorMode = uiState.sensorMode,
                        status = metrics.detectorStatus,
                        isSessionRunning = uiState.isSessionRunning,
                        calibrationProgress = metrics.calibrationProgress
                    )
                }

                // Sensor Mode + Workout Mode minimal selector pills (auto-hide when running)
                AnimatedVisibility(
                    visible = !uiState.isSessionRunning,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                    ) {
                        // Sensor Mode Switcher (Fusion AI / Audio Eco / Kamera)
                        Surface(
                            onClick = onCycleSensorMode,
                            shape = CircleShape,
                            color = GlassDark,
                            border = BorderStroke(1.dp, NeonLime.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.GraphicEq,
                                    contentDescription = null,
                                    tint = NeonLime,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = uiState.sensorMode.label,
                                    color = NeonLime,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Workout Mode Pill
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
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
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
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.6f),
                                    modifier = Modifier.size(13.dp)
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

            // CENTER: Minimal Hero Jump Counter + Fun Combo Hype + Style/Fingerprint Pill
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Playful streak hype badge!
                val hypeText = when {
                    config.mode == WorkoutModeType.HIIT && progress.hiitPhase == HiitPhase.REST -> "ISTIRAHAT"
                    metrics.currentStreak >= 100 -> "BEAST MODE!"
                    metrics.currentStreak >= 50 -> "UNSTOPPABLE!"
                    metrics.currentStreak >= 20 -> "ON FIRE!"
                    else -> null
                }
                if (hypeText != null) {
                    Text(
                        text = hypeText,
                        color = if (hypeText == "ISTIRAHAT") ElectricCyan else FlameOrange,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp
                    )
                }

                Text(
                    text = metrics.jumpCount.toString(),
                    color = if (metrics.isAirborne) NeonLime else Color.White,
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 98.sp,
                    modifier = Modifier.scale(counterScale)
                )

                Spacer(modifier = Modifier.height(6.dp))

                CompactMetricsStrip(metrics = metrics)

                // Subtle live Self-Learning & Style badge when Fusion is active
                if (uiState.isSessionRunning && uiState.sensorMode == SensorMode.FUSION && metrics.jumpCount >= 3) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = ElectricCyan.copy(alpha = 0.8f),
                            modifier = Modifier.size(13.dp)
                        )
                        val fpText = if (uiState.fingerprint.isCalibrated) {
                            "${uiState.jumpStyle.label} • ${uiState.fingerprint.shortBadge()}"
                        } else {
                            uiState.jumpStyle.label
                        }
                        Text(
                            text = fpText,
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else if (uiState.sensorMode == SensorMode.AUDIO_ONLY) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Kamera mati total • Mendengarkan bunyi tali (STFT 2.5–6.5 kHz)",
                        color = NeonLime.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // BOTTOM CONTROLS
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (uiState.isSessionRunning && !showFullControls) {
                    Text(
                        text = "Ketuk layar untuk kontrol",
                        color = Color.White.copy(alpha = 0.4f),
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
            val ecoLabel = if (uiState.recommendEcoLowFps) {
                "ECO FUSION • DYNAMIC-FPS HEMAT BATERAI"
            } else {
                "ECO MODE • AI AKTIF (KAMERA TERSEMBUNYI)"
            }
            Text(
                text = when (metrics.detectorStatus) {
                    DetectorStatus.NO_PERSON -> "ECO • Posisikan badan di depan kamera"
                    DetectorStatus.LOW_CONFIDENCE -> "ECO • Pose kurang jelas"
                    else -> ecoLabel
                },
                color = dimText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp
            )
        }

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
                    Box(modifier = Modifier.padding(12.dp), contentAlignment = Alignment.Center) {
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
    Text(text = "•", color = Color.White.copy(alpha = 0.35f), fontSize = 13.sp)
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
        Icon(imageVector = icon, contentDescription = contentDescription, modifier = Modifier.size(19.dp))
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
        Text(text = bannerText, color = accentColor, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
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
    sensorMode: SensorMode,
    status: DetectorStatus,
    isSessionRunning: Boolean,
    calibrationProgress: Float,
    modifier: Modifier = Modifier
) {
    val (dotColor, label) = if (sensorMode == SensorMode.AUDIO_ONLY) {
        if (isSessionRunning) NeonLime to "Audio Eco Aktif" else MintGreen to "Audio Eco Siap"
    } else {
        when (status) {
            DetectorStatus.NO_PERSON -> SoftRed to "Posisikan badan"
            DetectorStatus.LOW_CONFIDENCE -> FlameOrange to "Mundur sedikit"
            DetectorStatus.CALIBRATING -> ElectricCyan to "Kalibrasi ${(calibrationProgress * 100).toInt()}%"
            DetectorStatus.READY -> MintGreen to "AI Siap"
            DetectorStatus.ACTIVE -> if (isSessionRunning) NeonLime to "Fusion Live" else MintGreen to "AI Siap"
        }
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
                text = "Izinkan akses kamera & mikrofon agar Fusion AI v6 dapat mendeteksi pose tubuh sekaligus suara tali secara real-time 100% langsung di perangkat Anda.",
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
                    text = "IZINKAN & MULAI",
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

private fun Context.resolveDisplayName(uri: Uri): String {
    return runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: "video_skipping.mp4"
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
