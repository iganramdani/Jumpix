package com.jumpropeai.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jumpropeai.BuildConfig
import com.jumpropeai.metrics.RopeType
import com.jumpropeai.ui.JumpRopeUiState
import com.jumpropeai.ui.theme.DeepMidnight
import com.jumpropeai.ui.theme.IceMint
import com.jumpropeai.ui.theme.IceMintSoft
import com.jumpropeai.ui.theme.MidnightBorder
import com.jumpropeai.ui.theme.MidnightElevated
import com.jumpropeai.ui.theme.MidnightSurface
import com.jumpropeai.ui.theme.TextPrimary
import com.jumpropeai.ui.theme.TextSecondary
import kotlin.math.roundToInt

private enum class SettingsCategory(val label: String) {
    ATHLETE("Athlete & Goal"),
    AI_PRO("AI & Pro Suite"),
    DISPLAY_SYS("Display & Logs")
}

/**
 * Jumpvex v7.0 — High-Usability 3-Category Settings Sheet with strict `>= 48.dp` sweaty-hand
 * touch targets, `>= 12.sp` high-contrast typography, and crisp haptic ticks.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CategorizedWorkoutSettingsSheet(
    uiState: JumpRopeUiState,
    onCycleSensorMode: () -> Unit,
    onSelectCountdown: (Int) -> Unit,
    onToggleGestureControl: () -> Unit,
    onToggleDoubleClap: () -> Unit,
    onUpdateAthleteProfile: (Int, RopeType, Int) -> Unit,
    onCyclePacemakerMode: () -> Unit,
    onSetPacemakerRpm: (Int) -> Unit,
    onCycleGhostProfile: () -> Unit,
    onOpenHeartRateScanner: () -> Unit,
    onOpenSkillAcademy: () -> Unit,
    onStartSplitBattle: () -> Unit,
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
    val haptics = LocalHapticFeedback.current
    var selectedCategory by remember { mutableStateOf(SettingsCategory.ATHLETE) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DeepMidnight,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Tune, contentDescription = null, tint = IceMint, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Cockpit Settings",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "Thermal: ${uiState.thermalTier.label}",
                    color = IceMint,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // 3 Clean Category Segmented Tabs (>= 48dp touch height)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingsCategory.entries.forEach { cat ->
                    val selected = selectedCategory == cat
                    Surface(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedCategory = cat
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) IceMint else MidnightSurface,
                        border = BorderStroke(1.dp, if (selected) IceMint else MidnightBorder),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp)) {
                            Text(
                                text = cat.label,
                                color = if (selected) DeepMidnight else TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            when (selectedCategory) {
                SettingsCategory.ATHLETE -> {
                    // Athlete Profile Card with 48dp Sweaty-Hand Steppers
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MidnightSurface,
                        border = BorderStroke(1.dp, MidnightBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.FitnessCenter, null, tint = IceMint, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text("Body Weight", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                        Text("Calibrates MET kcal expenditure", color = TextSecondary, fontSize = 12.sp)
                                    }
                                }
                                Text(
                                    text = "${uiState.userWeightKg} kg",
                                    color = IceMint,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Surface(
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onUpdateAthleteProfile(
                                            (uiState.userWeightKg - 1).coerceAtLeast(35),
                                            uiState.ropeType,
                                            uiState.dailyGoalJumps
                                        )
                                    },
                                    shape = CircleShape,
                                    color = MidnightElevated,
                                    border = BorderStroke(1.dp, MidnightBorder),
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("−", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onUpdateAthleteProfile(
                                            (uiState.userWeightKg + 1).coerceAtMost(150),
                                            uiState.ropeType,
                                            uiState.dailyGoalJumps
                                        )
                                    },
                                    shape = CircleShape,
                                    color = MidnightElevated,
                                    border = BorderStroke(1.dp, MidnightBorder),
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("+", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Rope Type", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    RopeType.entries.forEach { rope ->
                                        val selected = uiState.ropeType == rope
                                        Surface(
                                            onClick = {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onUpdateAthleteProfile(uiState.userWeightKg, rope, uiState.dailyGoalJumps)
                                            },
                                            shape = CircleShape,
                                            color = if (selected) IceMint else MidnightElevated,
                                            border = BorderStroke(1.dp, if (selected) IceMint else MidnightBorder),
                                            modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp)) {
                                                Text(
                                                    text = rope.label,
                                                    color = if (selected) DeepMidnight else TextPrimary,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Daily Jump Goal", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(250 to "250", 500 to "500", 1000 to "1,000", 2000 to "2,000").forEach { (goal, label) ->
                                        val selected = uiState.dailyGoalJumps == goal
                                        Surface(
                                            onClick = {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onUpdateAthleteProfile(uiState.userWeightKg, uiState.ropeType, goal)
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            color = if (selected) IceMintSoft else MidnightElevated,
                                            border = BorderStroke(1.dp, if (selected) IceMint else MidnightBorder),
                                            modifier = Modifier
                                                .weight(1f)
                                                .defaultMinSize(minHeight = 48.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = label,
                                                    color = if (selected) IceMint else TextPrimary,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Pre-Start Countdown Selector (>= 48dp buttons)
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MidnightSurface,
                        border = BorderStroke(1.dp, MidnightBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Timer, null, tint = IceMint, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Start Countdown", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Delay after pressing Play to step back into frame", color = TextSecondary, fontSize = 12.sp)
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(0 to "Off", 3 to "3s", 5 to "5s", 8 to "8s").forEach { (sec, label) ->
                                    val selected = uiState.startCountdownDurationSec == sec
                                    Surface(
                                        onClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onSelectCountdown(sec)
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (selected) IceMint else MidnightElevated,
                                        border = BorderStroke(1.dp, if (selected) IceMint else MidnightBorder),
                                        modifier = Modifier
                                            .weight(1f)
                                            .defaultMinSize(minHeight = 48.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = label,
                                                color = if (selected) DeepMidnight else TextPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                SettingsCategory.AI_PRO -> {
                    // Sensor Mode Selector
                    Surface(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onCycleSensorMode()
                        },
                        shape = RoundedCornerShape(18.dp),
                        color = MidnightSurface,
                        border = BorderStroke(1.dp, IceMint.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 64.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.GraphicEq, null, tint = IceMint, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("AI Sensor Mode", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text("Tap to cycle • ${uiState.fingerprint.shortBadge()}", color = TextSecondary, fontSize = 12.sp)
                            }
                            Surface(shape = CircleShape, color = IceMintSoft) {
                                Text(
                                    text = uiState.sensorMode.label,
                                    color = IceMint,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }

                    // Phone-Only Pro Suite (>= 12sp labels & >= 48dp buttons)
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MidnightSurface,
                        border = BorderStroke(1.dp, MidnightBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("Phone-Only Pro Suite (100% On-Device)", color = IceMint, fontSize = 14.sp, fontWeight = FontWeight.Bold)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onCyclePacemakerMode()
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    color = MidnightElevated,
                                    border = BorderStroke(1.dp, MidnightBorder),
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 56.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("Audio Pacemaker", color = TextSecondary, fontSize = 12.sp)
                                        Text(
                                            text = "${uiState.pacemakerMode.label} (${uiState.pacemakerRpm})",
                                            color = IceMint,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Surface(
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onCycleGhostProfile()
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    color = MidnightElevated,
                                    border = BorderStroke(1.dp, MidnightBorder),
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 56.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("Ghost Rival Pacer", color = TextSecondary, fontSize = 12.sp)
                                        Text(
                                            text = uiState.ghostProfile.label,
                                            color = IceMint,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Pacemaker Tempo (100–420 SPM)", color = TextSecondary, fontSize = 12.sp)
                                    Text("${uiState.pacemakerRpm} SPM", color = IceMint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onOpenHeartRateScanner()
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, IceMint.copy(alpha = 0.6f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 48.dp)
                                ) {
                                    Text("Camera rPPG HR", color = IceMint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = onOpenSkillAcademy,
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, MidnightBorder),
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 48.dp)
                                ) {
                                    Text("AI Skill Tree", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            OutlinedButton(
                                onClick = onStartSplitBattle,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, IceMint.copy(alpha = 0.45f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp)
                            ) {
                                Text(
                                    text = if (uiState.battleState.isActive) "Close 2-Player Split Battle" else "Start 2-Player Split-Screen Battle",
                                    color = IceMint,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    ErgonomicSwitchCard(
                        icon = Icons.Rounded.PanTool,
                        title = "Touchless Hand-Gesture Control",
                        subtitle = "Raise 1 hand above head for 1.2s to Start or Pause",
                        checked = uiState.isGestureControlEnabled,
                        onCheckedChange = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggleGestureControl()
                        }
                    )

                    ErgonomicSwitchCard(
                        icon = Icons.Rounded.GraphicEq,
                        title = "Double-Clap Start / Pause (5m+)",
                        subtitle = "Clap twice crisply from across the gym to Start or Pause",
                        checked = uiState.isDoubleClapEnabled,
                        onCheckedChange = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggleDoubleClap()
                        }
                    )
                }

                SettingsCategory.DISPLAY_SYS -> {
                    ErgonomicSwitchCard(
                        icon = Icons.Rounded.AccessibilityNew,
                        title = "Pose Skeleton Overlay",
                        subtitle = "Display AI 17-landmark skeleton lines on camera",
                        checked = uiState.showSkeletonOverlay,
                        onCheckedChange = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggleSkeleton()
                        }
                    )

                    ErgonomicSwitchCard(
                        icon = Icons.AutoMirrored.Rounded.VolumeUp,
                        title = "Sound & Voice Cues",
                        subtitle = "Countdown beeps, milestones, and HIIT transitions",
                        checked = uiState.isAudioEnabled,
                        onCheckedChange = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggleAudio()
                        }
                    )

                    ErgonomicSwitchCard(
                        icon = Icons.Rounded.Bedtime,
                        title = "AMOLED Eco Mode",
                        subtitle = "Dim screen to pure black during active skipping",
                        checked = uiState.isEcoMode,
                        onCheckedChange = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggleEcoMode()
                        }
                    )

                    ErgonomicSwitchCard(
                        icon = Icons.Rounded.VisibilityOff,
                        title = "Auto-Hide Toolbar While Active",
                        subtitle = "Hide top & bottom bars during workout (tap screen to toggle)",
                        checked = uiState.autoHideControlsWhileActive,
                        onCheckedChange = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggleAutoHideControls()
                        }
                    )

                    ErgonomicSwitchCard(
                        icon = Icons.Rounded.GraphicEq,
                        title = "AI Performance & Full CSV v17 Log",
                        subtitle = "Show live FPS/CPU/RAM badge & record session telemetry",
                        checked = uiState.isDiagnosticsEnabled,
                        onCheckedChange = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggleDiagnostics()
                        }
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 60.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Download, null, tint = IceMint, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Export Live Diagnostic Log (CSV)", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = logExportNote
                                            ?: uiState.perfStats.lastSavedLogPath?.let { "Auto-saved: $it" }
                                            ?: "${uiState.perfStats.loggedRows} frames • Auto-saves to Documents/Jumpvex/DebugLogs",
                                        color = IceMint,
                                        fontSize = 12.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Surface(shape = CircleShape, color = IceMintSoft) {
                                    Text(
                                        text = "Share",
                                        color = IceMint,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }

                    // GitHub Release Update Checker
                    val hasUpdate = uiState.availableUpdate != null
                    Surface(
                        onClick = onCheckForUpdates,
                        shape = RoundedCornerShape(18.dp),
                        color = MidnightSurface,
                        border = BorderStroke(1.dp, if (hasUpdate) IceMint.copy(alpha = 0.65f) else MidnightBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 60.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
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
                                    text = if (hasUpdate) "Update Ready • v${uiState.availableUpdate?.versionName}" else "App Updates (v${BuildConfig.VERSION_NAME})",
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
                                CircularProgressIndicator(color = IceMint, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                            } else {
                                Surface(shape = CircleShape, color = if (hasUpdate) IceMint else IceMintSoft) {
                                    Text(
                                        text = if (hasUpdate) "Update" else "Check",
                                        color = if (hasUpdate) DeepMidnight else IceMint,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ErgonomicSwitchCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        shape = RoundedCornerShape(18.dp),
        color = MidnightSurface,
        border = BorderStroke(1.dp, MidnightBorder),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 62.dp)
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
                Text(text = title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(text = subtitle, color = TextSecondary, fontSize = 12.sp)
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
