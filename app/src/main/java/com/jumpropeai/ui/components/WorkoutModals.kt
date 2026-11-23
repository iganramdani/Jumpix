package com.jumpropeai.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.jumpropeai.data.CumulativeStats
import com.jumpropeai.data.WorkoutSession
import com.jumpropeai.ui.theme.DarkSurface
import com.jumpropeai.ui.theme.ElectricCyan
import com.jumpropeai.ui.theme.FlameOrange
import com.jumpropeai.ui.theme.GlassBorder
import com.jumpropeai.ui.theme.GlassCard
import com.jumpropeai.ui.theme.NeonLime
import com.jumpropeai.ui.theme.SoftRed
import com.jumpropeai.workout.WorkoutConfig
import com.jumpropeai.workout.WorkoutModeType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WorkoutModeSheet(
    currentConfig: WorkoutConfig,
    onApply: (WorkoutConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(currentConfig) { mutableStateOf(currentConfig) }

    // Custom text input states synced with presets & user typing
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
        containerColor = DarkSurface,
        contentColor = Color.White
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
                color = Color.White
            )

            // Mode Type selector
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
                            selectedContainerColor = NeonLime,
                            selectedLabelColor = Color(0xFF0A0D10),
                            containerColor = GlassCard,
                            labelColor = Color.White
                        )
                    )
                }
            }

            // Mode-specific presets + custom inputs
            when (draft.mode) {
                WorkoutModeType.FREE -> {
                    Text(
                        text = "Lompat bebas tanpa batas waktu atau target. Atur nama sesi & pengingat suara sesuai keinginan.",
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 13.sp
                    )
                    CustomInputTextField(
                        value = freeLabelText,
                        onValueChange = { text ->
                            val clean = text.take(28)
                            freeLabelText = clean
                            draft = draft.copy(customLabel = clean)
                        },
                        label = "Nama Sessions Custom (opsional)",
                        placeholder = "e.g., Morning Warm-Up / Double Under",
                        accentColor = NeonLime,
                        isNumeric = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Pengingat Suara (Tiap X Jumps)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = NeonLime
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
                                    selectedContainerColor = NeonLime,
                                    selectedLabelColor = Color(0xFF0A0D10),
                                    containerColor = GlassCard,
                                    labelColor = Color.White
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
                        label = "Custom Interval Pengingat (5–1000 lompatan)",
                        placeholder = "25",
                        accentColor = NeonLime,
                        isNumeric = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                WorkoutModeType.TARGET_JUMPS -> {
                    Text(
                        text = "Target Jumlah Jumps",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ElectricCyan
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
                                    selectedContainerColor = ElectricCyan,
                                    selectedLabelColor = Color(0xFF0A0D10),
                                    containerColor = GlassCard,
                                    labelColor = Color.White
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
                        label = "Custom Target Jumps (1–99.999)",
                        placeholder = "Contoh: 150",
                        accentColor = ElectricCyan,
                        isNumeric = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                WorkoutModeType.TARGET_TIME -> {
                    Text(
                        text = "Duration Target",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ElectricCyan
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(60 to "1 Minutes", 180 to "3 Minutes", 300 to "5 Minutes", 600 to "10 Minutes").forEach { (sec, label) ->
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
                                    selectedContainerColor = ElectricCyan,
                                    selectedLabelColor = Color(0xFF0A0D10),
                                    containerColor = GlassCard,
                                    labelColor = Color.White
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
                            accentColor = ElectricCyan,
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
                            accentColor = ElectricCyan,
                            isNumeric = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        text = "Total target: ${draft.shortLabel()}",
                        color = ElectricCyan.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                WorkoutModeType.HIIT -> {
                    Text(
                        text = "Preset & Custom Interval HIIT",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = FlameOrange
                    )
                    val presets = listOf(
                        Triple(20, 10, 4) to "Pemula: 20s / 10s × 4",
                        Triple(30, 15, 5) to "Standar: 30s / 15s × 5",
                        Triple(45, 15, 6) to "Intens: 45s / 15s × 6",
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
                                    selectedContainerColor = FlameOrange,
                                    selectedLabelColor = Color(0xFF0A0D10),
                                    containerColor = GlassCard,
                                    labelColor = Color.White
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
                            label = "Lompat (d)",
                            placeholder = "30",
                            accentColor = FlameOrange,
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
                            label = "Istirahat (d)",
                            placeholder = "15",
                            accentColor = FlameOrange,
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
                            accentColor = FlameOrange,
                            isNumeric = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    val totalHiitSec = draft.hiitRounds * draft.hiitWorkSeconds +
                        (draft.hiitRounds - 1).coerceAtLeast(0) * draft.hiitRestSeconds
                    Text(
                        text = "Total HIIT: ${totalHiitSec / 60}m ${totalHiitSec % 60}d (${draft.hiitWorkSeconds}s lompat / ${draft.hiitRestSeconds}s istirahat × ${draft.hiitRounds} ronde)",
                        color = FlameOrange.copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Button(
                onClick = {
                    // Normalize & clamp all custom inputs before applying
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
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonLime,
                    contentColor = Color(0xFF0A0D10)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = "TERAPKAN MODE",
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
        placeholder = { Text(placeholder, color = Color.White.copy(alpha = 0.35f), fontSize = 13.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isNumeric) KeyboardType.Number else KeyboardType.Text
        ),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accentColor,
            unfocusedBorderColor = GlassBorder,
            focusedLabelColor = accentColor,
            unfocusedLabelColor = Color.White.copy(alpha = 0.65f),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = accentColor,
            focusedContainerColor = GlassCard,
            unfocusedContainerColor = GlassCard
        ),
        modifier = modifier
    )
}

@Composable
fun SessionSummaryDialog(
    session: WorkoutSession,
    onShareSession: (WorkoutSession) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, NeonLime.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = NeonLime.copy(alpha = 0.16f),
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.EmojiEvents,
                            contentDescription = null,
                            tint = NeonLime,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                Text(
                    text = "Workout Complete!",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
                Text(
                    text = "${session.modeLabel} • Tersimpan di Riwayat",
                    fontSize = 13.sp,
                    color = ElectricCyan,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    SummaryStat("Jumps", "${session.jumpCount}", NeonLime)
                    SummaryStat("Duration", formatMillis(session.durationMs), Color.White)
                    SummaryStat("Calories", String.format(Locale.US, "%.1f", session.estimatedCalories), FlameOrange)
                }

                HorizontalDivider(color = GlassBorder)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    SummaryStat("Avg RPM", "${session.averageRpm}", ElectricCyan)
                    SummaryStat("Peak RPM", "${session.peakRpm}", ElectricCyan)
                    SummaryStat("Best Streak", "${session.bestStreak}", FlameOrange)
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        onClick = { onShareSession(session) },
                        shape = RoundedCornerShape(16.dp),
                        color = GlassCard,
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.6f)),
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
                                contentDescription = "Simpan / Bagikan Kartu",
                                tint = ElectricCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(
                                text = "KARTU",
                                color = ElectricCyan,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp
                            )
                        }
                    }

                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonLime,
                            contentColor = Color(0xFF0A0D10)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text(
                            text = "LANJUT",
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutHistorySheet(
    stats: CumulativeStats,
    sessions: List<WorkoutSession>,
    onShareSession: (WorkoutSession) -> Unit,
    onDeleteSession: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormatter = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.History,
                    contentDescription = null,
                    tint = NeonLime
                )
                Text(
                    text = "Riwayat & Statistik",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
            }

            // Cumulative Stats Card
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = GlassCard,
                border = BorderStroke(1.dp, GlassBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    SummaryStat("Total Lompat", "${stats.totalJumps}", NeonLime)
                    SummaryStat("Sessions", "${stats.totalSessions}", Color.White)
                    SummaryStat("Calories", String.format(Locale.US, "%.0f", stats.totalCalories), FlameOrange)
                    SummaryStat("Best Streak", "${stats.allTimeBestStreak}", ElectricCyan)
                }
            }

            if (sessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Belum ada riwayat latihan.\nDonekan sesi pertamamu untuk melihat statistik di sini!",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(sessions, key = { it.id }) { session ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = GlassCard,
                            border = BorderStroke(1.dp, GlassBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "${session.jumpCount} lompatan",
                                            color = NeonLime,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 16.sp
                                        )
                                        Text(
                                            text = "• ${session.modeLabel}",
                                            color = ElectricCyan,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Text(
                                        text = "${dateFormatter.format(Date(session.timestampMillis))}  •  ${formatMillis(session.durationMs)}  •  ${session.averageRpm} RPM  •  ${String.format(Locale.US, "%.1f", session.estimatedCalories)} kkal",
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 12.sp
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { onShareSession(session) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Share,
                                            contentDescription = "Simpan / Bagikan Kartu",
                                            tint = ElectricCyan
                                        )
                                    }
                                    IconButton(onClick = { onDeleteSession(session.id) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = "Hapus",
                                            tint = SoftRed.copy(alpha = 0.85f)
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
}

@Composable
private fun SummaryStat(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = valueColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.65f),
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
