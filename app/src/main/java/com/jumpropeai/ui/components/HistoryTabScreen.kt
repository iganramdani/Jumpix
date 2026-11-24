package com.jumpropeai.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jumpropeai.data.CumulativeStats
import com.jumpropeai.data.WorkoutSession
import com.jumpropeai.ui.theme.DeepMidnight
import com.jumpropeai.ui.theme.IceMint
import com.jumpropeai.ui.theme.IceMintSoft
import com.jumpropeai.ui.theme.MidnightBorder
import com.jumpropeai.ui.theme.MidnightElevated
import com.jumpropeai.ui.theme.MidnightSurface
import com.jumpropeai.ui.theme.TextPrimary
import com.jumpropeai.ui.theme.TextSecondary
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class DayBarData(
    val label: String,
    val jumps: Int,
    val isToday: Boolean
)

/**
 * Full-screen History, Daily Goal, Personal Records & CSV Backup tab in strict Two-Tone (Deep Midnight + Ice Mint).
 */
@Composable
fun HistoryTabScreen(
    stats: CumulativeStats,
    dailyGoalJumps: Int,
    sessions: List<WorkoutSession>,
    onShareSession: (WorkoutSession) -> Unit,
    onDeleteSession: (String) -> Unit,
    onExportHistoryCsv: () -> Unit,
    modifier: Modifier = Modifier
) {
    val numberFmt = remember { NumberFormat.getIntegerInstance(Locale.US) }
    val weeklyBars = remember(sessions) { computeLast7DaysBars(sessions) }
    val maxDayJumps = remember(weeklyBars) { (weeklyBars.maxOfOrNull { it.jumps } ?: 0).coerceAtLeast(1) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DeepMidnight)
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header + Day Streak & CSV Backup Button
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "History",
                        color = TextPrimary,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Performance, Personal Records & Logs",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (stats.activeDayStreak > 0) {
                        Surface(
                            shape = CircleShape,
                            color = IceMintSoft,
                            border = BorderStroke(1.dp, IceMint.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.LocalFireDepartment,
                                    contentDescription = null,
                                    tint = IceMint,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = "${stats.activeDayStreak}d",
                                    color = IceMint,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }

                    if (sessions.isNotEmpty()) {
                        Surface(
                            onClick = onExportHistoryCsv,
                            shape = CircleShape,
                            color = MidnightSurface,
                            border = BorderStroke(1.dp, MidnightBorder)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = "Export CSV Backup",
                                    tint = IceMint,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "CSV",
                                    color = TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // Daily Goal Progress Card
        item {
            val goal = dailyGoalJumps.coerceAtLeast(50)
            val todayFrac = (stats.todayJumps.toFloat() / goal.toFloat()).coerceIn(0f, 1f)
            val pct = (todayFrac * 100f).toInt()
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MidnightSurface,
                border = BorderStroke(1.dp, if (pct >= 100) IceMint.copy(alpha = 0.6f) else MidnightBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Today's Goal",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${numberFmt.format(stats.todayJumps)} / ${numberFmt.format(goal)} ($pct%)",
                            color = IceMint,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { todayFrac },
                        color = IceMint,
                        trackColor = MidnightElevated,
                        strokeCap = StrokeCap.Round,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                    )
                }
            }
        }

        // Hero Summary Card + 7-Day Ice Mint Bar Chart
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MidnightSurface,
                border = BorderStroke(1.dp, MidnightBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column {
                            Text(
                                text = numberFmt.format(stats.totalJumps),
                                color = TextPrimary,
                                fontSize = 40.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-1).sp,
                                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
                            )
                            Text(
                                text = "Total Jumps",
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                        }
                        Surface(
                            shape = CircleShape,
                            color = IceMint
                        ) {
                            Text(
                                text = "${stats.totalSessions} Sessions",
                                color = DeepMidnight,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MiniSummaryChip(
                            label = "Best Streak",
                            value = numberFmt.format(stats.allTimeBestStreak),
                            modifier = Modifier.weight(1f)
                        )
                        MiniSummaryChip(
                            label = "Total Time",
                            value = "${(stats.totalDurationMs / 60_000L).coerceAtLeast(0L)} min",
                            modifier = Modifier.weight(1f)
                        )
                        MiniSummaryChip(
                            label = "Calories",
                            value = "${stats.totalCalories.toInt()} kcal",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(Modifier.height(22.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(126.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        weeklyBars.forEach { bar ->
                            val frac = if (bar.jumps <= 0) 0.08f else (bar.jumps.toFloat() / maxDayJumps).coerceIn(0.14f, 1f)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(16.dp)
                                        .height(96.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MidnightElevated),
                                    contentAlignment = Alignment.BottomCenter
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .fillMaxHeight(frac)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(
                                                if (bar.jumps > 0) IceMint else IceMintSoft
                                            )
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = bar.label,
                                    color = if (bar.isToday) IceMint else TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = if (bar.isToday) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }

        // Personal Records Trophy Strip
        if (sessions.isNotEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MidnightSurface,
                    border = BorderStroke(1.dp, MidnightBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.EmojiEvents,
                                contentDescription = null,
                                tint = IceMint,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Personal Records",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MiniSummaryChip(
                                label = "Peak RPM",
                                value = "${stats.fastestPeakRpm}",
                                modifier = Modifier.weight(1f)
                            )
                            MiniSummaryChip(
                                label = "Best Session",
                                value = numberFmt.format(stats.highestSingleSessionJumps),
                                modifier = Modifier.weight(1f)
                            )
                            MiniSummaryChip(
                                label = if (stats.best30sSprintJumps > 0) "30s Sprint" else "Best Form",
                                value = if (stats.best30sSprintJumps > 0) "${stats.best30sSprintJumps}" else if (stats.bestFormScore > 0) "${stats.bestFormScore}%" else "—",
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // Section Header
        item {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Recent Sessions",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (sessions.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MidnightSurface,
                    border = BorderStroke(1.dp, MidnightBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp, horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = null,
                            tint = IceMint,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "No workouts saved yet",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Start a live workout or analyze a recorded video to see your history here.",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        } else {
            items(sessions, key = { it.id }) { session ->
                HistorySessionCard(
                    session = session,
                    numberFmt = numberFmt,
                    onShare = { onShareSession(session) },
                    onDelete = { onDeleteSession(session.id) }
                )
            }
        }
    }
}

@Composable
private fun MiniSummaryChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MidnightElevated,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                text = value,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = label,
                color = TextSecondary,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistorySessionCard(
    session: WorkoutSession,
    numberFmt: NumberFormat,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = remember(session.timestampMillis) {
        SimpleDateFormat("EEE, MMM dd • HH:mm", Locale.US).format(Date(session.timestampMillis))
    }
    val durSec = (session.durationMs / 1000L).coerceAtLeast(0L)
    val durStr = String.format(Locale.US, "%02d:%02d", durSec / 60L, durSec % 60L)

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MidnightSurface,
        border = BorderStroke(1.dp, MidnightBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = IceMint
                        ) {
                            Text(
                                text = session.modeLabel,
                                color = DeepMidnight,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                            )
                        }
                        if (session.formScore > 0) {
                            Surface(
                                shape = CircleShape,
                                color = IceMintSoft
                            ) {
                                Text(
                                    text = "Form ${session.formScore}%",
                                    color = IceMint,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${numberFmt.format(session.jumpCount)} Jumps",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "$dateStr  •  ${session.averageRpm} RPM  •  $durStr",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(onClick = onShare) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = "Share",
                        tint = IceMint,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Delete",
                        tint = TextSecondary.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (session.moveBreakdown.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    session.moveBreakdown.entries
                        .sortedByDescending { it.value }
                        .take(4)
                        .forEach { (move, cnt) ->
                            Surface(
                                shape = CircleShape,
                                color = MidnightElevated
                            ) {
                                Text(
                                    text = "$move × $cnt",
                                    color = TextSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                }
            }
        }
    }
}

private fun computeLast7DaysBars(sessions: List<WorkoutSession>): List<DayBarData> {
    val dayLabels = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)

    val dayStarts = LongArray(7)
    val labels = Array(7) { "" }
    for (offset in 6 downTo 0) {
        val c = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -offset) }
        val idx = 6 - offset
        dayStarts[idx] = c.timeInMillis
        labels[idx] = dayLabels[c.get(Calendar.DAY_OF_WEEK) - 1]
    }
    val totals = IntArray(7)
    val oneDayMs = 24L * 60L * 60L * 1000L
    for (s in sessions) {
        for (i in 0 until 7) {
            if (s.timestampMillis >= dayStarts[i] && s.timestampMillis < dayStarts[i] + oneDayMs) {
                totals[i] += s.jumpCount
                break
            }
        }
    }
    return List(7) { i ->
        DayBarData(
            label = labels[i],
            jumps = totals[i],
            isToday = (i == 6)
        )
    }
}
