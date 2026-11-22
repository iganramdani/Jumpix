package com.jumpropeai.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Whatshot
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
import com.jumpropeai.workout.WorkoutModeType
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
    onOpenHistory: () -> Unit,
    onOpenModeSheet: () -> Unit
) {
    val metrics = uiState.metrics
    val config = uiState.workoutConfig
    val progress = uiState.workoutProgress

    val counterScale by animateFloatAsState(
        targetValue = if (metrics.isAirborne) 1.09f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "jumpCounterScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. Live Full-Screen Camera Preview
        CameraPreview(
            lensFacing = uiState.lensFacing,
            onPoseFrame = onPoseFrame,
            modifier = Modifier.fillMaxSize()
        )

        // 2. Real-time AI Skeleton Overlay
        if (uiState.showSkeletonOverlay) {
            PoseOverlay(
                poseFrame = uiState.latestPose,
                isAirborne = metrics.isAirborne,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 3. Top & Bottom Gradient Scrims
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xC0000000), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xE0000000))
                    )
                )
        )

        // 4. Foreground HUD
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Controls + Workout Mode Selector
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AiStatusPill(
                        status = metrics.detectorStatus,
                        isSessionRunning = uiState.isSessionRunning,
                        calibrationProgress = metrics.calibrationProgress,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HudIconButton(
                            onClick = onToggleAudio,
                            active = uiState.isAudioEnabled,
                            activeColor = NeonLime,
                            icon = if (uiState.isAudioEnabled) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                            contentDescription = "Audio Feedback"
                        )
                        HudIconButton(
                            onClick = onToggleSkeleton,
                            active = uiState.showSkeletonOverlay,
                            activeColor = ElectricCyan,
                            icon = if (uiState.showSkeletonOverlay) Icons.Rounded.AccessibilityNew else Icons.Rounded.VisibilityOff,
                            contentDescription = "Skeleton Overlay"
                        )
                        HudIconButton(
                            onClick = onOpenHistory,
                            active = false,
                            activeColor = Color.White,
                            icon = Icons.Rounded.History,
                            contentDescription = "Riwayat Latihan"
                        )
                        HudIconButton(
                            onClick = onToggleCamera,
                            active = false,
                            activeColor = Color.White,
                            icon = Icons.Rounded.Cameraswitch,
                            contentDescription = "Ganti Kamera"
                        )
                    }
                }

                // Mode Pill + Progress / HIIT Banner
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Surface(
                        onClick = { if (!uiState.isSessionRunning) onOpenModeSheet() },
                        shape = CircleShape,
                        color = GlassDark,
                        border = BorderStroke(
                            1.dp,
                            if (config.mode != WorkoutModeType.FREE) ElectricCyan.copy(alpha = 0.6f) else GlassBorder
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
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = config.shortLabel(),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (!uiState.isSessionRunning) {
                                Icon(
                                    imageVector = Icons.Rounded.Tune,
                                    contentDescription = "Ubah Mode",
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                // Target / HIIT Live Banner & Progress Bar
                if (config.mode != WorkoutModeType.FREE) {
                    WorkoutProgressBanner(
                        config = config,
                        progress = progress,
                        jumpCount = metrics.jumpCount
                    )
                }
            }

            // Hero Jump Counter + Streak Badge
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (config.mode == WorkoutModeType.HIIT && progress.hiitPhase == HiitPhase.REST) {
                        "ISTIRAHAT"
                    } else {
                        "LOMPATAN"
                    },
                    color = if (config.mode == WorkoutModeType.HIIT && progress.hiitPhase == HiitPhase.REST) {
                        ElectricCyan
                    } else {
                        Color.White.copy(alpha = 0.72f)
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp
                )
                Text(
                    text = metrics.jumpCount.toString(),
                    color = if (metrics.isAirborne) NeonLime else Color.White,
                    fontSize = 84.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 86.sp,
                    modifier = Modifier.scale(counterScale)
                )

                Spacer(modifier = Modifier.height(6.dp))

                Surface(
                    shape = CircleShape,
                    color = GlassDark,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (metrics.currentStreak > 0) FlameOrange.copy(alpha = 0.65f) else GlassBorder
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Whatshot,
                            contentDescription = null,
                            tint = if (metrics.currentStreak > 0) FlameOrange else Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Streak ${metrics.currentStreak}",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "•",
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Best ${metrics.bestStreak}",
                            color = NeonLime,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Bottom Panel: Live Metrics Grid + Action Buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricsGlassBar(metrics = metrics)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Reset Button
                    Surface(
                        onClick = onResetSession,
                        shape = RoundedCornerShape(20.dp),
                        color = GlassCard,
                        border = BorderStroke(1.dp, GlassBorder),
                        modifier = Modifier.size(58.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Reset",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    // Primary Start / Pause Button
                    Button(
                        onClick = onToggleSession,
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.isSessionRunning) SoftRed else NeonLime,
                            contentColor = if (uiState.isSessionRunning) Color.White else Color(0xFF0A0D10)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(58.dp)
                    ) {
                        Icon(
                            imageVector = if (uiState.isSessionRunning) {
                                Icons.Rounded.Pause
                            } else {
                                Icons.Rounded.PlayArrow
                            },
                            contentDescription = null,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (uiState.isSessionRunning) "JEDA" else "MULAI",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )
                    }

                    // Finish & Save Button (visible once workout has progress)
                    if (uiState.hasActiveProgress) {
                        Surface(
                            onClick = onFinishSession,
                            shape = RoundedCornerShape(20.dp),
                            color = MintGreen.copy(alpha = 0.22f),
                            border = BorderStroke(1.dp, MintGreen.copy(alpha = 0.7f)),
                            modifier = Modifier.size(58.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Selesai & Simpan",
                                    tint = MintGreen,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
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
        modifier = Modifier.size(40.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (active) activeColor.copy(alpha = 0.22f) else GlassDark,
            contentColor = if (active) activeColor else Color.White
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun WorkoutProgressBanner(
    config: com.jumpropeai.workout.WorkoutConfig,
    progress: com.jumpropeai.workout.WorkoutProgress,
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
                .height(6.dp)
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
        DetectorStatus.ACTIVE -> if (isSessionRunning) {
            NeonLime to "AI Live"
        } else {
            MintGreen to "AI Siap"
        }
    }

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = GlassDark,
        border = BorderStroke(1.dp, GlassBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
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
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun MetricsGlassBar(metrics: WorkoutMetrics) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = GlassCard,
        border = BorderStroke(1.dp, GlassBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            MetricTile(
                icon = Icons.Rounded.Timer,
                iconTint = Color.White,
                value = formatDuration(metrics.elapsedMs),
                label = "Durasi",
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                icon = Icons.Rounded.Speed,
                iconTint = ElectricCyan,
                value = "${metrics.currentRpm}",
                label = "RPM",
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                icon = Icons.Rounded.LocalFireDepartment,
                iconTint = FlameOrange,
                value = String.format(Locale.US, "%.1f", metrics.estimatedCalories),
                label = "kkal",
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                icon = Icons.Rounded.Whatshot,
                iconTint = NeonLime,
                value = "${metrics.peakRpm}",
                label = "Peak RPM",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MetricTile(
    icon: ImageVector,
    iconTint: Color,
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconTint,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
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

private fun formatDuration(elapsedMs: Long): String {
    val totalSeconds = elapsedMs / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
