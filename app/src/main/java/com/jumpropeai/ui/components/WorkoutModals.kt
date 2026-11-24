package com.jumpropeai.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.jumpropeai.data.WorkoutSession
import com.jumpropeai.ui.theme.DeepMidnight
import com.jumpropeai.ui.theme.IceMint
import com.jumpropeai.ui.theme.IceMintSoft
import com.jumpropeai.ui.theme.MidnightBorder
import com.jumpropeai.ui.theme.MidnightElevated
import com.jumpropeai.ui.theme.MidnightSurface
import com.jumpropeai.ui.theme.TextPrimary
import com.jumpropeai.ui.theme.TextSecondary
import com.jumpropeai.workout.WorkoutConfig
import com.jumpropeai.workout.WorkoutModeType
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WorkoutModeSheet(
    currentConfig: WorkoutConfig,
    onApply: (WorkoutConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(currentConfig) { mutableStateOf(currentConfig) }

    var freeLabelText by remember(currentConfig) { mutableStateOf(currentConfig.customLabel) }
    var milestoneText by remember(currentConfig) { mutableStateOf(currentConfig.milestoneInterval.toString()) }
    var targetJumpsText by remember(currentConfig) { mutableStateOf(currentConfig.targetJumps.toString()) }
    var targetMinText by remember(currentConfig) { mutableStateOf((currentConfig.targetTimeSeconds / 60).toString()) }
    var targetSecText by remember(currentConfig) { mutableStateOf((currentConfig.targetTimeSeconds % 60).toString()) }
    var hiitWorkText by remember(currentConfig) { mutableStateOf(currentConfig.hiitWorkSeconds.toString()) }
    var hiitRestText by remember(currentConfig) { mutableStateOf(currentConfig.hiitRestSeconds.toString()) }
    var hiitRoundsText by remember(currentConfig) { mutableStateOf(currentConfig.hiitRounds.toString()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DeepMidnight,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Select Workout Mode",
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TextPrimary
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WorkoutModeType.entries.forEach { modeType ->
                    val selected = draft.mode == modeType
                    FilterChip(
                        selected = selected,
                        onClick = { draft = draft.copy(mode = modeType) },
                        label = {
                            Text(
                                text = modeType.label,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = IceMint,
                            selectedLabelColor = DeepMidnight,
                            containerColor = MidnightSurface,
                            labelColor = TextPrimary
                        )
                    )
                }
            }

            when (draft.mode) {
                WorkoutModeType.FREE -> {
                    Text(
                        text = "Open-ended skipping without a fixed limit. Customize your session title and voice milestone cues.",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    CustomInputTextField(
                        value = freeLabelText,
                        onValueChange = { text ->
                            val clean = text.take(28)
                            freeLabelText = clean
                            draft = draft.copy(customLabel = clean)
                        },
                        label = "Custom Session Name (optional)",
                        placeholder = "e.g., Morning Warm-Up / Double Unders",
                        accentColor = IceMint,
                        isNumeric = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Voice Milestone (Every X Jumps)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = IceMint
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(10, 25, 50, 100).forEach { step ->
                            val selected = draft.milestoneInterval == step
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    milestoneText = step.toString()
                                    draft = draft.copy(milestoneInterval = step)
                                },
                                label = { Text("$step") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = IceMint,
                                    selectedLabelColor = DeepMidnight,
                                    containerColor = MidnightSurface,
                                    labelColor = TextPrimary
                                )
                            )
                        }
                    }
                    CustomInputTextField(
                        value = milestoneText,
                        onValueChange = { raw ->
                            val digits = raw.filter { it.isDigit() }.take(4)
                            milestoneText = digits
                            digits.toIntOrNull()?.takeIf { it >= 5 }?.let {
                                draft = draft.copy(milestoneInterval = it.coerceIn(5, 1000))
                            }
                        },
                        label = "Custom Milestone Interval (5–1,000 jumps)",
                        placeholder = "25",
                        accentColor = IceMint,
                        isNumeric = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                WorkoutModeType.TARGET_JUMPS -> {
                    Text(
                        text = "Target Jump Count",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = IceMint
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(50, 100, 200, 300, 500, 1000).forEach { target ->
                            val selected = draft.targetJumps == target
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    targetJumpsText = target.toString()
                                    draft = draft.copy(targetJumps = target)
                                },
                                label = { Text("$target") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = IceMint,
                                    selectedLabelColor = DeepMidnight,
                                    containerColor = MidnightSurface,
                                    labelColor = TextPrimary
                                )
                            )
                        }
                    }
                    CustomInputTextField(
                        value = targetJumpsText,
                        onValueChange = { raw ->
                            val digits = raw.filter { it.isDigit() }.take(5)
                            targetJumpsText = digits
                            digits.toIntOrNull()?.takeIf { it >= 1 }?.let {
                                draft = draft.copy(targetJumps = it.coerceIn(1, 99999))
                            }
                        },
                        label = "Custom Target Jumps (1–99,999)",
                        placeholder = "e.g., 250",
                        accentColor = IceMint,
                        isNumeric = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                WorkoutModeType.TARGET_TIME -> {
                    Text(
                        text = "Target Duration",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = IceMint
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(60 to "1 Minute", 180 to "3 Minutes", 300 to "5 Minutes", 600 to "10 Minutes").forEach { (sec, label) ->
                            val selected = draft.targetTimeSeconds == sec
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    targetMinText = (sec / 60).toString()
                                    targetSecText = (sec % 60).toString()
                                    draft = draft.copy(targetTimeSeconds = sec)
                                },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = IceMint,
                                    selectedLabelColor = DeepMidnight,
                                    containerColor = MidnightSurface,
                                    labelColor = TextPrimary
                                )
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CustomInputTextField(
                            value = targetMinText,
                            onValueChange = { raw ->
                                val digits = raw.filter { it.isDigit() }.take(3)
                                targetMinText = digits
                                val m = (digits.toIntOrNull() ?: 0).coerceIn(0, 180)
                                val s = (targetSecText.toIntOrNull() ?: 0).coerceIn(0, 59)
                                val total = (m * 60 + s).coerceAtLeast(10)
                                draft = draft.copy(targetTimeSeconds = total)
                            },
                            label = "Minutes (0–180)",
                            placeholder = "3",
                            accentColor = IceMint,
                            isNumeric = true,
                            modifier = Modifier.weight(1f)
                        )
                        CustomInputTextField(
                            value = targetSecText,
                            onValueChange = { raw ->
                                val digits = raw.filter { it.isDigit() }.take(2)
                                targetSecText = digits
                                val m = (targetMinText.toIntOrNull() ?: 0).coerceIn(0, 180)
                                val s = (digits.toIntOrNull() ?: 0).coerceIn(0, 59)
                                val total = (m * 60 + s).coerceAtLeast(10)
                                draft = draft.copy(targetTimeSeconds = total)
                            },
                            label = "Seconds (0–59)",
                            placeholder = "0",
                            accentColor = IceMint,
                            isNumeric = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        text = "Selected target: ${draft.shortLabel()}",
                        color = IceMint,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                WorkoutModeType.HIIT -> {
                    Text(
                        text = "HIIT Interval Presets & Custom Builder",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = IceMint
                    )
                    val presets = listOf(
                        Triple(20, 10, 4) to "Beginner: 20s / 10s × 4",
                        Triple(30, 15, 5) to "Standard: 30s / 15s × 5",
                        Triple(45, 15, 6) to "Intense: 45s / 15s × 6",
                        Triple(60, 20, 5) to "Pro: 60s / 20s × 5"
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        presets.forEach { (cfg, label) ->
                            val selected = draft.hiitWorkSeconds == cfg.first &&
                                draft.hiitRestSeconds == cfg.second &&
                                draft.hiitRounds == cfg.third
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    hiitWorkText = cfg.first.toString()
                                    hiitRestText = cfg.second.toString()
                                    hiitRoundsText = cfg.third.toString()
                                    draft = draft.copy(
                                        hiitWorkSeconds = cfg.first,
                                        hiitRestSeconds = cfg.second,
                                        hiitRounds = cfg.third
                                    )
                                },
                                label = { Text(label, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = IceMint,
                                    selectedLabelColor = DeepMidnight,
                                    containerColor = MidnightSurface,
                                    labelColor = TextPrimary
                                )
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CustomInputTextField(
                            value = hiitWorkText,
                            onValueChange = { raw ->
                                val digits = raw.filter { it.isDigit() }.take(3)
                                hiitWorkText = digits
                                digits.toIntOrNull()?.takeIf { it >= 5 }?.let {
                                    draft = draft.copy(hiitWorkSeconds = it.coerceIn(5, 600))
                                }
                            },
                            label = "Work (s)",
                            placeholder = "30",
                            accentColor = IceMint,
                            isNumeric = true,
                            modifier = Modifier.weight(1f)
                        )
                        CustomInputTextField(
                            value = hiitRestText,
                            onValueChange = { raw ->
                                val digits = raw.filter { it.isDigit() }.take(3)
                                hiitRestText = digits
                                digits.toIntOrNull()?.takeIf { it >= 5 }?.let {
                                    draft = draft.copy(hiitRestSeconds = it.coerceIn(5, 300))
                                }
                            },
                            label = "Rest (s)",
                            placeholder = "15",
                            accentColor = IceMint,
                            isNumeric = true,
                            modifier = Modifier.weight(1f)
                        )
                        CustomInputTextField(
                            value = hiitRoundsText,
                            onValueChange = { raw ->
                                val digits = raw.filter { it.isDigit() }.take(2)
                                hiitRoundsText = digits
                                digits.toIntOrNull()?.takeIf { it >= 1 }?.let {
                                    draft = draft.copy(hiitRounds = it.coerceIn(1, 50))
                                }
                            },
                            label = "Rounds",
                            placeholder = "5",
                            accentColor = IceMint,
                            isNumeric = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    val totalHiitSec = draft.hiitRounds * draft.hiitWorkSeconds +
                        (draft.hiitRounds - 1).coerceAtLeast(0) * draft.hiitRestSeconds
                    Text(
                        text = "Total HIIT: ${totalHiitSec / 60}m ${totalHiitSec % 60}s (${draft.hiitWorkSeconds}s work / ${draft.hiitRestSeconds}s rest × ${draft.hiitRounds} rounds)",
                        color = IceMint,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                WorkoutModeType.FREESTYLE_SHUFFLE -> {
                    Text(
                        text = "Freestyle Jump Tricks & Shuffle Dance",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = IceMint
                    )
                    Text(
                        text = "Detects Triple Under, Double Under, Criss-Cross, Boxer Step, Heel-Toe, High Knees, Running Man, T-Step, Charleston & live Music BPM Beat-Sync — 100% on-device.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }

                WorkoutModeType.IJRU_SPEED_30S -> {
                    Text(
                        text = "Official IJRU 30-Second Speed Sprint",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = IceMint
                    )
                    Text(
                        text = "Competition-grade 30-second all-out sprint benchmark. Uses 100 Hz STFT acoustic + ankle alternation tracking to resolve 270–350+ RPM sprints without camera aliasing.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }

                WorkoutModeType.IJRU_ENDURANCE_180S -> {
                    Text(
                        text = "Official IJRU 3-Minute Speed Endurance",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = IceMint
                    )
                    Text(
                        text = "Competition-grade 180-second endurance test tracking sustained cadence, trips, unbroken streak, and biomechanical form stability.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
            }

            Button(
                onClick = {
                    val finalConfig = draft.copy(
                        customLabel = freeLabelText.trim(),
                        milestoneInterval = (milestoneText.toIntOrNull() ?: draft.milestoneInterval).coerceIn(5, 1000),
                        targetJumps = (targetJumpsText.toIntOrNull() ?: draft.targetJumps).coerceIn(1, 99999),
                        targetTimeSeconds = (
                            (targetMinText.toIntOrNull() ?: 0).coerceIn(0, 180) * 60 +
                                (targetSecText.toIntOrNull() ?: 0).coerceIn(0, 59)
                            ).coerceAtLeast(10),
                        hiitWorkSeconds = (hiitWorkText.toIntOrNull() ?: draft.hiitWorkSeconds).coerceIn(5, 600),
                        hiitRestSeconds = (hiitRestText.toIntOrNull() ?: draft.hiitRestSeconds).coerceIn(5, 300),
                        hiitRounds = (hiitRoundsText.toIntOrNull() ?: draft.hiitRounds).coerceIn(1, 50)
                    )
                    onApply(finalConfig)
                },
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
                    text = "APPLY MODE",
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
private fun CustomInputTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    accentColor: Color,
    isNumeric: Boolean,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        placeholder = { Text(placeholder, color = TextSecondary.copy(alpha = 0.6f), fontSize = 13.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isNumeric) KeyboardType.Number else KeyboardType.Text
        ),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accentColor,
            unfocusedBorderColor = MidnightBorder,
            focusedLabelColor = accentColor,
            unfocusedLabelColor = TextSecondary,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = accentColor,
            focusedContainerColor = MidnightSurface,
            unfocusedContainerColor = MidnightSurface
        ),
        modifier = modifier
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionSummaryDialog(
    session: WorkoutSession,
    onShareSession: (WorkoutSession) -> Unit,
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
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = IceMintSoft,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.EmojiEvents,
                            contentDescription = null,
                            tint = IceMint,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                Text(
                    text = "Workout Complete!",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    color = TextPrimary
                )
                Text(
                    text = "${session.modeLabel} • Saved to History",
                    fontSize = 13.sp,
                    color = IceMint,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    SummaryStat("Jumps", "${session.jumpCount}", IceMint)
                    SummaryStat("Duration", formatMillis(session.durationMs), TextPrimary)
                    SummaryStat("Calories", String.format(Locale.US, "%.1f", session.estimatedCalories), IceMint)
                }

                HorizontalDivider(color = MidnightBorder)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    SummaryStat("Avg RPM", "${session.averageRpm}", TextPrimary)
                    SummaryStat("Peak RPM", "${session.peakRpm}", IceMint)
                    SummaryStat(
                        if (session.formScore > 0) "AI Form" else "Best Streak",
                        if (session.formScore > 0) "${session.formScore}%" else "${session.bestStreak}",
                        IceMint
                    )
                }

                if (session.moveBreakdown.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        session.moveBreakdown.entries
                            .sortedByDescending { it.value }
                            .take(5)
                            .forEach { (move, cnt) ->
                                Surface(
                                    shape = CircleShape,
                                    color = MidnightElevated,
                                    border = BorderStroke(1.dp, MidnightBorder)
                                ) {
                                    Text(
                                        text = "$move × $cnt",
                                        color = TextPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        onClick = { onShareSession(session) },
                        shape = CircleShape,
                        color = MidnightElevated,
                        border = BorderStroke(1.dp, IceMint.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = "Share Card",
                                tint = IceMint,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(
                                text = "SHARE CARD",
                                color = IceMint,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Button(
                        onClick = onDismiss,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = IceMint,
                            contentColor = DeepMidnight
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text(
                            text = "DONE",
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = valueColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
        )
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 11.sp
        )
    }
}

private fun formatMillis(ms: Long): String {
    val totalSec = ms / 1000L
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", min, sec)
}
