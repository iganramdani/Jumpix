package com.jumpropeai.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jumpropeai.ui.theme.DangerSoft
import com.jumpropeai.ui.theme.DeepMidnight
import com.jumpropeai.ui.theme.IceMint
import com.jumpropeai.ui.theme.IceMintSoft
import com.jumpropeai.ui.theme.MidnightBorder
import com.jumpropeai.ui.theme.MidnightElevated
import com.jumpropeai.ui.theme.MidnightSurface
import com.jumpropeai.ui.theme.TextPrimary
import com.jumpropeai.ui.theme.TextSecondary
import com.jumpropeai.video.StudioPrecisionTier
import com.jumpropeai.video.VideoAnalysisProgress
import com.jumpropeai.video.VideoAnalysisResult
import com.jumpropeai.video.VideoStudioMode
import java.util.Locale

private enum class StudioResultSubTab(val label: String) {
    OVERVIEW("Overview"),
    SPEED("Speed"),
    VAR_ROPE("VAR Rope"),
    TRICKS_LOGS("Logs")
}

/**
 * Jumpvex v7.0 — High-Usability Video Studio with:
 * - **Pinned Sticky Slow-Mo Video Player** at the top (never scrolls off-screen while scrubbing charts!)
 * - **1-Tap Precision Toggle** (`🔬 Heavy VAR` vs `⚡ Fast 30fps`) + **`Cancel Analysis`** button
 * - **4 Clean Segmented Sub-Tabs** (`Overview` • `Speed & Splits` • `VAR Rope` • `Tricks & ZIP`)
 * - **1-Tap CSV / Batch `.ZIP` Telemetry Exporter** & clear `"View in History →"` action
 */
@Composable
fun VideoAnalyzerScreen(
    isAnalyzing: Boolean,
    isShuffleMode: Boolean,
    onToggleShuffleMode: () -> Unit,
    studioMode: VideoStudioMode = if (isShuffleMode) VideoStudioMode.SHUFFLE_FOOTWORK else VideoStudioMode.AUTO_HYBRID,
    onSelectStudioMode: (VideoStudioMode) -> Unit = { _ -> onToggleShuffleMode() },
    precisionTier: StudioPrecisionTier = StudioPrecisionTier.HEAVY_VAR,
    onTogglePrecisionTier: () -> Unit = {},
    onCancelAnalysis: () -> Unit = {},
    onShareDebugLogs: () -> Unit = {},
    progress: VideoAnalysisProgress?,
    result: VideoAnalysisResult?,
    errorMessage: String?,
    onPickVideo: () -> Unit,
    onShareResult: (VideoAnalysisResult) -> Unit,
    onSaveResultToHistory: (VideoAnalysisResult) -> Unit,
    onClose: () -> Unit,
    queueIndex: Int = 0,
    queueTotal: Int = 0,
    batchResults: List<VideoAnalysisResult> = emptyList(),
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    var scrubTimeMs by remember(result) { mutableLongStateOf(0L) }
    var activeSubTab by remember(result) { mutableStateOf(StudioResultSubTab.OVERVIEW) }

    val activeBitmap = when {
        isAnalyzing -> progress?.previewBitmap
        result != null -> result.finalPreviewBitmap
        else -> null
    }
    val imageBitmap = remember(activeBitmap) {
        activeBitmap?.takeIf { !it.isRecycled }?.asImageBitmap()
    }
    val isPortraitVideo = imageBitmap != null && imageBitmap.height > imageBitmap.width

    val liveJumpCount = when {
        isAnalyzing -> progress?.currentJumps ?: 0
        result != null -> result.jumpCount
        else -> 0
    }
    val badgeScale by animateFloatAsState(
        targetValue = if (isAnalyzing && liveJumpCount > 0) 1.04f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "videoBadgeBounce"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DeepMidnight)
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        // 1. Pinned Top Bar: Title + Precision Tier Switch + Pick / Cancel Action
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Video Studio",
                    color = TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                )
                if (queueTotal > 1) {
                    Text(
                        text = if (isAnalyzing) {
                            "Batch Queue • $queueIndex of $queueTotal"
                        } else {
                            "Batch Complete • ${batchResults.size}/$queueTotal Videos"
                        },
                        color = IceMint,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // 1-Tap Precision Pill: 🔬 Heavy VAR vs ⚡ Fast 30fps
                Surface(
                    onClick = {
                        if (!isAnalyzing) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onTogglePrecisionTier()
                        }
                    },
                    shape = CircleShape,
                    color = if (precisionTier == StudioPrecisionTier.HEAVY_VAR) IceMintSoft else MidnightSurface,
                    border = BorderStroke(
                        1.dp,
                        if (precisionTier == StudioPrecisionTier.HEAVY_VAR) IceMint else MidnightBorder
                    ),
                    modifier = Modifier.defaultMinSize(minHeight = 42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
                        Text(
                            text = precisionTier.badge,
                            color = if (precisionTier == StudioPrecisionTier.HEAVY_VAR) IceMint else TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (isAnalyzing) {
                    OutlinedButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onCancelAnalysis()
                        },
                        shape = CircleShape,
                        border = BorderStroke(1.dp, DangerSoft),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerSoft),
                        modifier = Modifier.defaultMinSize(minHeight = 42.dp)
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Cancel", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    OutlinedButton(
                        onClick = onPickVideo,
                        shape = CircleShape,
                        border = BorderStroke(1.dp, IceMint),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = IceMint),
                        modifier = Modifier.defaultMinSize(minHeight = 42.dp)
                    ) {
                        Text("Pick Video(s)", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 2. 4-Mode AI Engine Selector Strip (collapses to 1 compact chip when result is loaded!)
        if (result == null || isAnalyzing) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    VideoStudioMode.AUTO_HYBRID to "Auto",
                    VideoStudioMode.IJRC_COMPETITION to "IJRC Speed",
                    VideoStudioMode.FREESTYLE_TRICKS to "Freestyle",
                    VideoStudioMode.SHUFFLE_FOOTWORK to "Shuffle"
                ).forEach { (modeItem, shortTitle) ->
                    val selected = studioMode == modeItem
                    Surface(
                        onClick = {
                            if (!isAnalyzing) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelectStudioMode(modeItem)
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) IceMint else MidnightSurface,
                        border = BorderStroke(1.dp, if (selected) IceMint else MidnightBorder),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
                            Text(
                                text = shortTitle,
                                color = if (selected) DeepMidnight else TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        } else {
            val allModes = listOf(
                VideoStudioMode.AUTO_HYBRID,
                VideoStudioMode.IJRC_COMPETITION,
                VideoStudioMode.FREESTYLE_TRICKS,
                VideoStudioMode.SHUFFLE_FOOTWORK
            )
            val modeLabel = when (studioMode) {
                VideoStudioMode.AUTO_HYBRID -> "Auto Hybrid"
                VideoStudioMode.IJRC_COMPETITION -> "IJRC Speed"
                VideoStudioMode.FREESTYLE_TRICKS -> "Freestyle"
                VideoStudioMode.SHUFFLE_FOOTWORK -> "Shuffle"
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val nextIdx = (allModes.indexOf(studioMode) + 1) % allModes.size
                        onSelectStudioMode(allModes[nextIdx])
                    },
                    shape = CircleShape,
                    color = MidnightSurface,
                    border = BorderStroke(1.dp, MidnightBorder)
                ) {
                    Text(
                        text = "Mode: $modeLabel ▾",
                        color = TextPrimary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Text(
                    text = "${result.jumpCount} jumps • ${result.averageRpm} RPM",
                    color = IceMint,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
            }
            Spacer(Modifier.height(6.dp))
        }

        // 3. PINNED VIEWPORT: Always stays fixed above the scrollable telemetry sub-tabs!
        if (!isAnalyzing && result != null && result.sourceUri != null) {
            SyncedSlowMoVideoPlayer(
                sourceUri = result.sourceUri,
                durationMs = result.durationMs,
                poseFrames = result.poseFrames,
                scrubTimeMs = scrubTimeMs,
                onScrubTimeChanged = { scrubTimeMs = it },
                isPortrait = isPortraitVideo,
                result = result
            )
            Spacer(Modifier.height(8.dp))

            // 4 Segmented Telemetry Sub-Tabs (>= 44dp touch height)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StudioResultSubTab.entries.forEach { tab ->
                    val selected = activeSubTab == tab
                    Surface(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            activeSubTab = tab
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) IceMint else MidnightSurface,
                        border = BorderStroke(1.dp, if (selected) IceMint else MidnightBorder),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
                            Text(
                                text = tab.label,
                                color = if (selected) DeepMidnight else TextSecondary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // 4. Independent Scrollable Detail Region Below the Pinned Video Player!
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Live decoding preview or Empty State when no completed result player is pinned
            if (isAnalyzing || result?.sourceUri == null) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MidnightSurface,
                    border = BorderStroke(1.dp, MidnightBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isPortraitVideo) 270.dp else 220.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp)
                    ) {
                        if (imageBitmap != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.Black)
                            ) {
                                Image(
                                    bitmap = imageBitmap,
                                    contentDescription = "Video Analysis Preview",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )

                                if (isAnalyzing && progress?.latestPose != null) {
                                    PoseOverlay(
                                        poseFrame = progress.latestPose,
                                        isAirborne = progress.isAirborne,
                                        fitCenter = true,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }

                                if (isAnalyzing && progress != null && liveJumpCount > 0) {
                                    val pillLabel = buildString {
                                        append(progress.currentMove.label.uppercase(Locale.US))
                                        if (progress.detectedBpm > 0) append(" • ${progress.detectedBpm} BPM")
                                    }
                                    Surface(
                                        shape = CircleShape,
                                        color = DeepMidnight.copy(alpha = 0.82f),
                                        border = BorderStroke(1.dp, IceMint.copy(alpha = 0.45f)),
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(10.dp)
                                    ) {
                                        Text(
                                            text = pillLabel,
                                            color = IceMint,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                }

                                Surface(
                                    shape = CircleShape,
                                    color = IceMint,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(10.dp)
                                        .scale(badgeScale)
                                ) {
                                    Text(
                                        text = "$liveJumpCount ${if (isShuffleMode) "Moves" else "Jumps"}",
                                        color = DeepMidnight,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                    )
                                }

                                val barProgress = when {
                                    isAnalyzing -> (progress?.fraction ?: 0f).coerceIn(0f, 1f)
                                    result != null -> 1f
                                    else -> 0f
                                }
                                LinearProgressIndicator(
                                    progress = { barProgress },
                                    color = IceMint,
                                    trackColor = Color.White.copy(alpha = 0.22f),
                                    strokeCap = StrokeCap.Round,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                        .height(6.dp)
                                )
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Surface(shape = CircleShape, color = IceMintSoft, modifier = Modifier.size(58.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Rounded.Movie, null, tint = IceMint, modifier = Modifier.size(28.dp))
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = "Dual-Tier Neural VAR Video Studio",
                                    color = TextPrimary,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Pick 1 or multiple videos for Heavy 3D Pose, Sub-Foot Rope Vision, IJRC Judge Scoring, and Slow-Mo Telemetry.",
                                    color = TextSecondary,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = DangerSoft.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, DangerSoft.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = errorMessage,
                        color = DangerSoft,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            if (isAnalyzing) {
                val pct = ((progress?.fraction ?: 0f) * 100).toInt().coerceIn(0, 100)
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MidnightSurface,
                    border = BorderStroke(1.dp, MidnightBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = progress?.stage ?: "Processing video...",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "$pct%",
                                color = IceMint,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            StudioMetricCard(
                                value = "${progress?.currentJumps ?: 0}",
                                label = if (isShuffleMode) "Moves" else "Jumps",
                                icon = Icons.AutoMirrored.Rounded.DirectionsRun,
                                modifier = Modifier.weight(1f)
                            )
                            StudioMetricCard(
                                value = "${progress?.currentRpm ?: 0}",
                                label = "Live RPM",
                                icon = Icons.Rounded.Speed,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            } else if (result != null) {
                when (activeSubTab) {
                    StudioResultSubTab.OVERVIEW -> {
                        result.ijrcSummary?.takeIf { it.isCompetitionSprintDetected }?.let { ijrc ->
                            IjrcCompetitionScoreboardCard(ijrc)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            StudioMetricCard(
                                value = "${result.jumpCount}",
                                label = if (isShuffleMode) "Total Moves" else "Total Jumps",
                                icon = Icons.AutoMirrored.Rounded.DirectionsRun,
                                modifier = Modifier.weight(1f)
                            )
                            StudioMetricCard(
                                value = "${result.averageRpm}",
                                label = "Active RPM",
                                icon = Icons.Rounded.Speed,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            StudioMetricCard(
                                value = "${result.bestStreak}",
                                label = "Best Streak",
                                icon = Icons.Rounded.Bolt,
                                modifier = Modifier.weight(1f)
                            )
                            StudioMetricCard(
                                value = String.format(Locale.US, "%.0f kcal", result.estimatedCalories),
                                label = "Calories",
                                icon = Icons.Rounded.LocalFireDepartment,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (batchResults.size > 1) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = MidnightSurface,
                                border = BorderStroke(1.dp, MidnightBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Batch Summary (${batchResults.size} Videos)",
                                        color = TextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    batchResults.forEachIndexed { index, item ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${index + 1}. ${item.fileName}",
                                                color = TextSecondary,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = "${item.jumpCount} • ${item.averageRpm} RPM",
                                                color = IceMint,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = { onShareResult(result) },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = IceMint,
                                contentColor = DeepMidnight
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Export Animated HUD Video & Card", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = { onSaveResultToHistory(result) },
                            shape = CircleShape,
                            border = BorderStroke(1.dp, IceMint.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = MidnightSurface,
                                contentColor = TextPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Text("Auto-Saved • View in History", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = IceMint, modifier = Modifier.size(18.dp))
                        }
                    }

                    StudioResultSubTab.SPEED -> {
                        SpeedAndSplitsTabPanel(
                            result = result,
                            scrubTimeMs = scrubTimeMs,
                            onScrubTimeChanged = { scrubTimeMs = it }
                        )
                    }

                    StudioResultSubTab.VAR_ROPE -> {
                        VarRopeAndWaveTabPanel(
                            result = result,
                            scrubTimeMs = scrubTimeMs,
                            onScrubTimeChanged = { scrubTimeMs = it }
                        )
                    }

                    StudioResultSubTab.TRICKS_LOGS -> {
                        // 1-Tap Share CSV / Batch .ZIP Telemetry Card!
                        Surface(
                            onClick = onShareDebugLogs,
                            shape = RoundedCornerShape(18.dp),
                            color = MidnightSurface,
                            border = BorderStroke(1.dp, IceMint.copy(alpha = 0.55f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 64.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (batchResults.size > 1) Icons.Rounded.FolderZip else Icons.Rounded.Share,
                                    contentDescription = null,
                                    tint = IceMint,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (batchResults.size > 1) {
                                            "Export All ${batchResults.size} Logs as .ZIP"
                                        } else {
                                            "Share v17 Telemetry Log (CSV)"
                                        },
                                        color = TextPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = result.savedDebugLogPath ?: "Includes neural_prob, rope_vis, clean_whip & # Final Summary",
                                        color = IceMint,
                                        fontSize = 12.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Surface(shape = CircleShape, color = IceMint) {
                                    Text(
                                        text = if (batchResults.size > 1) "Share .ZIP" else "Share CSV",
                                        color = DeepMidnight,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                    )
                                }
                            }
                        }

                        if (result.formScore > 0 || result.moveCounts.isNotEmpty() || result.detectedBpm > 0) {
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
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("AI Form, Tricks & Rhythm", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            if (result.detectedBpm > 0) {
                                                Surface(shape = CircleShape, color = MidnightElevated) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(Icons.Rounded.MusicNote, null, tint = IceMint, modifier = Modifier.size(12.dp))
                                                        Spacer(Modifier.width(3.dp))
                                                        Text(
                                                            text = if (result.beatSyncScore > 0) "${result.detectedBpm} BPM • ${result.beatSyncScore}%" else "${result.detectedBpm} BPM",
                                                            color = TextPrimary,
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }
                                            if (result.formScore > 0) {
                                                Surface(shape = CircleShape, color = IceMintSoft) {
                                                    Text(
                                                        text = "Form ${result.formScore}%",
                                                        color = IceMint,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    if (result.formTip.isNotBlank()) {
                                        Text(result.formTip, color = TextSecondary, fontSize = 12.sp)
                                    }
                                    if (result.moveCounts.isNotEmpty()) {
                                        @OptIn(ExperimentalLayoutApi::class)
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            result.moveCounts.entries.sortedByDescending { it.value }.forEach { (move, count) ->
                                                Surface(
                                                    shape = CircleShape,
                                                    color = MidnightElevated,
                                                    border = BorderStroke(1.dp, MidnightBorder)
                                                ) {
                                                    Text(
                                                        text = "${move.label} × $count",
                                                        color = TextPrimary,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                                                    )
                                                }
                                            }
                                            if (result.comboCount > 0) {
                                                Surface(
                                                    shape = CircleShape,
                                                    color = IceMintSoft,
                                                    border = BorderStroke(1.dp, IceMint.copy(alpha = 0.5f))
                                                ) {
                                                    Text(
                                                        text = "${result.comboCount} Combos",
                                                        color = IceMint,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (result.telemetryEvents.isNotEmpty()) {
                            VideoSlowMoInspectorCard(
                                events = result.telemetryEvents,
                                onJumpToTimestampMs = { scrubTimeMs = it }
                            )
                        }
                    }
                }
            } else {
                Button(
                    onClick = onPickVideo,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = IceMint,
                        contentColor = DeepMidnight
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Pick Video(s) from Gallery", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun StudioMetricCard(
    value: String,
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MidnightSurface,
        border = BorderStroke(1.dp, MidnightBorder),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = value,
                    color = TextPrimary,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(icon, contentDescription = null, tint = IceMint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(text = label, color = TextSecondary, fontSize = 12.sp, maxLines = 1)
        }
    }
}
