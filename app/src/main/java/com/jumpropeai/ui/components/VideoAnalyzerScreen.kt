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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Movie
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
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
import com.jumpropeai.video.VideoAnalysisProgress
import com.jumpropeai.video.VideoAnalysisResult
import java.util.Locale

/**
 * Modern Two-Tone Video Studio tab (Deep Midnight + Ice Mint).
 * Live camera is completely turned OFF while this tab is active.
 */
@Composable
fun VideoAnalyzerScreen(
    isAnalyzing: Boolean,
    progress: VideoAnalysisProgress?,
    result: VideoAnalysisResult?,
    errorMessage: String?,
    onPickVideo: () -> Unit,
    onShareResult: (VideoAnalysisResult) -> Unit,
    onSaveResultToHistory: (VideoAnalysisResult) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeBitmap = when {
        isAnalyzing -> progress?.previewBitmap
        result != null -> result.finalPreviewBitmap
        else -> null
    }
    val imageBitmap = remember(activeBitmap) {
        activeBitmap?.takeIf { !it.isRecycled }?.asImageBitmap()
    }

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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        // 1. Clean Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Video Studio",
                color = TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            )

            OutlinedButton(
                onClick = onPickVideo,
                enabled = !isAnalyzing,
                shape = CircleShape,
                border = BorderStroke(1.dp, if (isAnalyzing) MidnightBorder else IceMint),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = IceMint,
                    disabledContentColor = TextSecondary
                )
            ) {
                Text(
                    text = if (isAnalyzing) "Analyzing..." else "Pick Video",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        // 2. Adaptive Full-Frame Video Preview Card (never crops portrait or landscape videos!)
        val isPortraitVideo = imageBitmap != null && imageBitmap.height > imageBitmap.width
        val previewAspect = if (isPortraitVideo) 3f / 4f else 16f / 10f
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MidnightSurface,
            border = BorderStroke(1.dp, MidnightBorder),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(previewAspect)
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

                        // Live AI Skeleton Overlay aligned 1:1 with ContentScale.Fit
                        if (isAnalyzing && progress?.latestPose != null) {
                            PoseOverlay(
                                poseFrame = progress.latestPose,
                                isAirborne = progress.isAirborne,
                                fitCenter = true,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // Crisp Ice Mint Jump Counter Badge in Top-Right
                        Surface(
                            shape = CircleShape,
                            color = IceMint,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp)
                                .scale(badgeScale)
                        ) {
                            Text(
                                text = "$liveJumpCount Jumps",
                                color = DeepMidnight,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }

                        // Thin Ice Mint Progress Bar at Bottom of Video Frame
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
                                .height(5.dp)
                        )
                    }
                } else {
                    // Empty Idle State
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = IceMintSoft,
                            modifier = Modifier.size(62.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Movie,
                                    contentDescription = null,
                                    tint = IceMint,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "Analyze Jump Rope Video",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Pick a workout video from your gallery to automatically count jumps with AI and export a watermarked video.",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }

        // Error Banner if any
        if (!errorMessage.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
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

        Spacer(Modifier.height(16.dp))

        // 3. Status / 2x2 Metric Grid
        if (isAnalyzing) {
            val pct = ((progress?.fraction ?: 0f) * 100).toInt().coerceIn(0, 100)
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MidnightSurface,
                border = BorderStroke(1.dp, MidnightBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
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
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StudioMetricCard(
                            value = "${progress?.currentJumps ?: 0}",
                            label = "Jumps",
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
            // 2x2 Crisp Metric Grid
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StudioMetricCard(
                        value = "${result.jumpCount}",
                        label = "Total Jumps",
                        icon = Icons.AutoMirrored.Rounded.DirectionsRun,
                        modifier = Modifier.weight(1f)
                    )
                    StudioMetricCard(
                        value = "${result.averageRpm}",
                        label = "Average RPM",
                        icon = Icons.Rounded.Speed,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
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
            }

            // AI Form Coach + Detected Tricks Breakdown Card
            if (result.formScore > 0 || result.moveCounts.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
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
                            Text(
                                text = "AI Form & Trick Breakdown",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (result.formScore > 0) {
                                Surface(
                                    shape = CircleShape,
                                    color = IceMintSoft
                                ) {
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
                        if (result.formTip.isNotBlank()) {
                            Text(
                                text = result.formTip,
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
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
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Primary CTA: Export & Share
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
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Export & Share",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(10.dp))

            // Secondary CTA: Save / Done
            OutlinedButton(
                onClick = { onSaveResultToHistory(result) },
                shape = CircleShape,
                border = BorderStroke(1.dp, MidnightBorder),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MidnightSurface,
                    contentColor = TextPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircleOutline,
                    contentDescription = null,
                    tint = IceMint,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Saved to History",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        } else {
            // Idle CTA Button
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
                Icon(
                    imageVector = Icons.Rounded.UploadFile,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Pick Video from Gallery",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(16.dp))
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
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = value,
                    color = TextPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = IceMint,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 1
            )
        }
    }
}

