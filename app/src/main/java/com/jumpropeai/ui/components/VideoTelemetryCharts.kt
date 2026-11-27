package com.jumpropeai.ui.components

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.view.TextureView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.jumpropeai.ai.IjrcSprintSummary
import com.jumpropeai.ai.KineticMove
import com.jumpropeai.ai.PoseFrame
import com.jumpropeai.ai.RichTelemetrySeries
import com.jumpropeai.ui.theme.DeepMidnight
import com.jumpropeai.ui.theme.IceMint
import com.jumpropeai.ui.theme.MidnightBorder
import com.jumpropeai.ui.theme.MidnightElevated
import com.jumpropeai.ui.theme.MidnightSurface
import com.jumpropeai.ui.theme.TextPrimary
import com.jumpropeai.ui.theme.TextSecondary
import com.jumpropeai.video.VideoAnalysisResult
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private val SkyCyan = Color(0xFF38BDF8)
private val CoralAlert = Color(0xFFFB7185)
private val GoldAccent = Color(0xFFF59E0B)

/**
 * Jumpvex v7.0 — Pinned Non-Blocking (`prepareAsync()`) Frame-Accurate Slow-Mo Video Scrubber
 * (`0.2x` – `1.0x` + `±1 Frame`) with Live Sub-Foot Visual Rope-Pass & Neural VAR Confidence HUD!
 */
@Composable
fun SyncedSlowMoVideoPlayer(
    sourceUri: Uri,
    durationMs: Long,
    poseFrames: List<PoseFrame>,
    scrubTimeMs: Long,
    onScrubTimeChanged: (Long) -> Unit,
    isPortrait: Boolean,
    result: VideoAnalysisResult? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var mediaPlayer by remember(sourceUri) { mutableStateOf<MediaPlayer?>(null) }
    var isPrepared by remember(sourceUri) { mutableStateOf(false) }
    var isPlaying by remember(sourceUri) { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(0.25f) }
    var showSkeleton by remember { mutableStateOf(true) }

    DisposableEffect(sourceUri) {
        onDispose {
            isPrepared = false
            runCatching { mediaPlayer?.stop() }
            runCatching { mediaPlayer?.release() }
            mediaPlayer = null
        }
    }

    // Poll MediaPlayer position at 30 Hz while playing so charts & skeleton track smoothly
    LaunchedEffect(isPlaying, isPrepared, mediaPlayer) {
        while (isPlaying && isPrepared && mediaPlayer != null) {
            val pos = runCatching { mediaPlayer?.currentPosition?.toLong() ?: scrubTimeMs }.getOrDefault(scrubTimeMs)
            onScrubTimeChanged(pos.coerceIn(0L, max(1L, durationMs)))
            delay(33L)
        }
    }

    val activePose = remember(poseFrames, scrubTimeMs) {
        findNearestPoseFrame(poseFrames, scrubTimeMs)
    }

    // Determine live VAR neural confidence & sub-foot cable pass state around playhead
    val nearRopePass = remember(result, scrubTimeMs) {
        val passes = result?.visualPassTimestampsMs ?: LongArray(0)
        passes.any { abs(it - scrubTimeMs) <= 85L }
    }
    val nearEvent = remember(result, scrubTimeMs) {
        result?.telemetryEvents?.firstOrNull { abs(it.timestampMs - scrubTimeMs) <= 120L }
    }
    val nearChartPt = remember(result, scrubTimeMs) {
        val pts = result?.telemetrySeries?.points.orEmpty()
        if (pts.isEmpty()) null else {
            val idx = ((scrubTimeMs.toFloat() / max(1L, durationMs)) * pts.lastIndex).roundToInt().coerceIn(0, pts.lastIndex)
            pts[idx]
        }
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MidnightSurface,
        border = BorderStroke(1.dp, MidnightBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isPortrait) 224.dp else 188.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                    runCatching {
                                        val mp = MediaPlayer()
                                        mp.setDataSource(context, sourceUri)
                                        mp.setSurface(android.view.Surface(st))
                                        mp.isLooping = true
                                        mp.setOnPreparedListener { preparedMp ->
                                            isPrepared = true
                                            preparedMp.seekToSafe(scrubTimeMs)
                                        }
                                        mp.prepareAsync()
                                        mediaPlayer = mp
                                    }
                                }
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    isPrepared = false
                                    runCatching { mediaPlayer?.release() }
                                    mediaPlayer = null
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                            }
                        }
                    },
                    update = {
                        val mp = mediaPlayer
                        if (mp != null && isPrepared && !isPlaying) {
                            val diff = abs(mp.currentPosition.toLong() - scrubTimeMs)
                            if (diff >= 25L) {
                                mp.seekToSafe(scrubTimeMs)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (showSkeleton && activePose != null) {
                    PoseOverlay(
                        poseFrame = activePose,
                        isAirborne = nearEvent != null || nearRopePass,
                        fitCenter = true,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Top-left live timestamp + Jump # pill
                Surface(
                    shape = CircleShape,
                    color = DeepMidnight.copy(alpha = 0.84f),
                    border = BorderStroke(1.dp, IceMint.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    val jumpTag = nearEvent?.let { " • #${it.jumpNumber}" } ?: ""
                    Text(
                        text = String.format(Locale.US, "%.2fs / %.1fs%s", scrubTimeMs / 1000.0, durationMs / 1000.0, jumpTag),
                        color = IceMint,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }

                // Top-right skeleton toggle pill (>= 36dp visual, padded touch)
                Surface(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showSkeleton = !showSkeleton
                    },
                    shape = CircleShape,
                    color = if (showSkeleton) IceMint else DeepMidnight.copy(alpha = 0.84f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Text(
                        text = if (showSkeleton) "Skeleton ON" else "Skeleton OFF",
                        color = if (showSkeleton) DeepMidnight else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                // Bottom-left Live Visual Rope Clearance & VAR Neural Badge!
                val varPct = ((nearChartPt?.neuralVarProb ?: 0f) * 100f).roundToInt()
                val passBadgeText = when {
                    nearEvent?.move == KineticMove.TRIPLE_UNDER -> "⚡ 3x TU CABLE PASS • VAR $varPct%"
                    nearEvent?.move == KineticMove.DOUBLE_UNDER || nearEvent?.move == KineticMove.DOUBLE_UNDER_CROSS ->
                        "⚡ 2x DU CABLE PASS • VAR $varPct%"
                    nearRopePass -> "⚡ SUB-FOOT CABLE PASS • VAR $varPct%"
                    nearEvent != null -> "✓ ${nearEvent.move.label.uppercase(Locale.US)} • VAR $varPct%"
                    varPct >= 50 -> "VAR LOCK $varPct%"
                    else -> null
                }
                if (passBadgeText != null) {
                    Surface(
                        shape = CircleShape,
                        color = DeepMidnight.copy(alpha = 0.86f),
                        border = BorderStroke(1.dp, if (nearRopePass) SkyCyan else IceMint),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = passBadgeText,
                            color = if (nearRopePass) SkyCyan else IceMint,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Ergonomic Transport & Slow-Motion Speed Bar (44-48dp targets)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isPlaying = false
                            mediaPlayer?.takeIf { isPrepared }?.pause()
                            val next = (scrubTimeMs - 33L).coerceAtLeast(0L)
                            onScrubTimeChanged(next)
                            mediaPlayer?.takeIf { isPrepared }?.seekToSafe(next)
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(Icons.Rounded.SkipPrevious, contentDescription = "Prev Frame", tint = TextPrimary)
                    }

                    Surface(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val mp = mediaPlayer?.takeIf { isPrepared } ?: return@Surface
                            if (isPlaying) {
                                mp.pause()
                                isPlaying = false
                            } else {
                                applySpeedSafe(mp, playbackSpeed)
                                mp.start()
                                isPlaying = true
                            }
                        },
                        shape = CircleShape,
                        color = IceMint,
                        modifier = Modifier.size(46.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = DeepMidnight
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isPlaying = false
                            mediaPlayer?.takeIf { isPrepared }?.pause()
                            val next = (scrubTimeMs + 33L).coerceAtMost(durationMs)
                            onScrubTimeChanged(next)
                            mediaPlayer?.takeIf { isPrepared }?.seekToSafe(next)
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = "Next Frame", tint = TextPrimary)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(0.20f to "0.2x", 0.25f to "0.25x", 0.50f to "0.5x", 1.00f to "1.0x").forEach { (sp, label) ->
                        val active = abs(playbackSpeed - sp) < 0.01f
                        Surface(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                playbackSpeed = sp
                                mediaPlayer?.takeIf { isPrepared }?.let { mp ->
                                    if (isPlaying) applySpeedSafe(mp, sp)
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = if (active) IceMint else MidnightElevated,
                            modifier = Modifier.defaultMinSize(minHeight = 38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 9.dp)) {
                                Text(
                                    text = label,
                                    color = if (active) DeepMidnight else TextPrimary,
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
}

@Composable
fun IjrcCompetitionScoreboardCard(ijrc: IjrcSprintSummary, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MidnightSurface,
        border = BorderStroke(1.dp, IceMint.copy(alpha = 0.65f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🏆 IJRC / IJRU Official Judge Scoreboard",
                    color = IceMint,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                if (ijrc.buzzerWindow.detected) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "Buzzer %.1f–%.1fs",
                            ijrc.buzzerWindow.startBuzzerMs / 1000.0,
                            ijrc.buzzerWindow.endBuzzerMs / 1000.0
                        ),
                        color = SkyCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IjrcStatBox(
                    title = "Right-Foot (Judge)",
                    value = "${ijrc.rightFootJudgeScore}",
                    sub = "Official Tally",
                    accent = IceMint,
                    modifier = Modifier.weight(1f)
                )
                IjrcStatBox(
                    title = "Total Steps (L+R)",
                    value = "${ijrc.totalAlternatingSteps}",
                    sub = "${ijrc.averageStepSpm} SPM",
                    accent = SkyCyan,
                    modifier = Modifier.weight(1f)
                )
                IjrcStatBox(
                    title = "Rope Turns",
                    value = "${ijrc.verifiedRopeTurns}",
                    sub = "Peak ${ijrc.peakRopeRpm}",
                    accent = GoldAccent,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * Sub-Tab 2: Step Speed vs Rope Rotation Speed Dual Curve + 5-Second Competition Splits.
 */
@Composable
fun SpeedAndSplitsTabPanel(
    result: VideoAnalysisResult,
    scrubTimeMs: Long,
    onScrubTimeChanged: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val series: RichTelemetrySeries = result.telemetrySeries
    var showPerMinute by remember { mutableStateOf(true) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (series.points.size >= 4) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MidnightSurface,
                border = BorderStroke(1.dp, MidnightBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(15.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Step Speed vs Rope Rotation Speed",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Drag chart while watching the pinned Slow-Mo video above",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        Surface(
                            onClick = { showPerMinute = !showPerMinute },
                            shape = CircleShape,
                            color = MidnightElevated,
                            border = BorderStroke(1.dp, IceMint.copy(alpha = 0.45f)),
                            modifier = Modifier.defaultMinSize(minHeight = 38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
                                Text(
                                    text = if (showPerMinute) "SPM / RPM" else "SPS / RPS",
                                    color = IceMint,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    val activePt = remember(series.points, scrubTimeMs) {
                        val idx = ((scrubTimeMs.toFloat() / max(1L, result.durationMs)) * series.points.lastIndex)
                            .roundToInt()
                            .coerceIn(0, series.points.lastIndex)
                        series.points[idx]
                    }
                    val unitMult = if (showPerMinute) 60f else 1f
                    val stepLabel = if (showPerMinute) {
                        "${(activePt.stepSps * 60f).roundToInt()} SPM"
                    } else {
                        String.format(Locale.US, "%.2f SPS", activePt.stepSps)
                    }
                    val ropeLabel = if (showPerMinute) {
                        "${(activePt.ropeRps * 60f).roundToInt()} RPM"
                    } else {
                        String.format(Locale.US, "%.2f RPS", activePt.ropeRps)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("● Rope Speed: $ropeLabel", color = IceMint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("● Step Speed: $stepLabel", color = SkyCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.height(8.dp))

                    val maxRate = maxOf(series.peakRopeRps, series.peakStepSps, 3f) * unitMult * 1.12f
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(144.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(DeepMidnight)
                            .pointerInput(result.durationMs) {
                                detectTapGestures { offset ->
                                    val frac = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                    onScrubTimeChanged((frac * result.durationMs).toLong())
                                }
                            }
                            .pointerInput(result.durationMs) {
                                detectHorizontalDragGestures { change, _ ->
                                    change.consume()
                                    val frac = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                    onScrubTimeChanged((frac * result.durationMs).toLong())
                                }
                            }
                    ) {
                        val w = size.width
                        val h = size.height
                        val padV = 12f
                        val plotH = h - padV * 2f

                        for (g in 1..3) {
                            val gy = padV + plotH * (1f - g / 4f)
                            drawLine(MidnightBorder.copy(alpha = 0.55f), Offset(0f, gy), Offset(w, gy), 1f)
                        }

                        val pts = series.points
                        val stepPath = Path()
                        val ropePath = Path()

                        for (i in pts.indices) {
                            val x = (i.toFloat() / max(1, pts.lastIndex)) * w
                            val pt = pts[i]
                            if (pt.isTripZone) {
                                drawRect(
                                    color = CoralAlert.copy(alpha = 0.18f),
                                    topLeft = Offset(x - 2f, 0f),
                                    size = Size(max(4f, w / pts.size), h)
                                )
                            }
                            val yStep = padV + plotH * (1f - ((pt.stepSps * unitMult) / maxRate).coerceIn(0f, 1f))
                            val yRope = padV + plotH * (1f - ((pt.ropeRps * unitMult) / maxRate).coerceIn(0f, 1f))
                            if (i == 0) {
                                stepPath.moveTo(x, yStep)
                                ropePath.moveTo(x, yRope)
                            } else {
                                stepPath.lineTo(x, yStep)
                                ropePath.lineTo(x, yRope)
                            }
                        }

                        drawPath(path = stepPath, color = SkyCyan, style = Stroke(width = 3.2f, cap = StrokeCap.Round))
                        drawPath(path = ropePath, color = IceMint, style = Stroke(width = 3.6f, cap = StrokeCap.Round))

                        val cursorX = ((scrubTimeMs.toFloat() / max(1L, result.durationMs)) * w).coerceIn(0f, w)
                        drawLine(Color.White.copy(alpha = 0.9f), Offset(cursorX, 0f), Offset(cursorX, h), 2.2f)
                    }
                }
            }
        }

        if (series.splits5s.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MidnightSurface,
                border = BorderStroke(1.dp, MidnightBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(15.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("5-Second Pace Splits", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        val dropTxt = if (series.fatigueDropPct >= 0f) {
                            String.format(Locale.US, "Fatigue: -%.1f%%", series.fatigueDropPct)
                        } else {
                            String.format(Locale.US, "Negative Split: +%.1f%%", abs(series.fatigueDropPct))
                        }
                        Text(
                            text = dropTxt,
                            color = if (series.fatigueDropPct <= 12f) IceMint else GoldAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    val maxSplitSteps = (series.splits5s.maxOfOrNull { it.totalSteps } ?: 1).coerceAtLeast(1)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(112.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        series.splits5s.take(8).forEach { sp ->
                            val frac = (sp.totalSteps.toFloat() / maxSplitSteps.toFloat()).coerceIn(0.08f, 1f)
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom
                            ) {
                                Text("${sp.totalSteps}", color = IceMint, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                                Spacer(Modifier.height(3.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height((64f * frac).dp)
                                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                        .background(IceMint.copy(alpha = 0.85f))
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(sp.label, color = TextSecondary, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Sub-Tab 3 (NEW v7.0): Bidirectional VAR Neural Probability Curve (`0–100%`) + Visual Sub-Foot Cable
 * Pass Ticks + Zoomed `±2.5s` Alternating Leg-Drive & Clean HPSS Whip Oscilloscope!
 */
@Composable
fun VarRopeAndWaveTabPanel(
    result: VideoAnalysisResult,
    scrubTimeMs: Long,
    onScrubTimeChanged: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val series = result.telemetrySeries
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (series.points.size >= 4) {
            val activePt = remember(series.points, scrubTimeMs) {
                val idx = ((scrubTimeMs.toFloat() / max(1L, result.durationMs)) * series.points.lastIndex)
                    .roundToInt()
                    .coerceIn(0, series.points.lastIndex)
                series.points[idx]
            }

            // Card 1: Bidirectional VAR Neural Probability + Visual Rope Pass Track
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MidnightSurface,
                border = BorderStroke(1.dp, IceMint.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(15.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "🔬 VAR Neural Probability & Visual Rope Passes",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Mint curve = MS-TCN Jump Prob • Cyan ticks = Sub-Foot Cable ROI",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        Text(
                            text = "P=${(activePt.neuralVarProb * 100f).roundToInt()}%",
                            color = IceMint,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Visual Cable Passes: ${series.verifiedVisualRopePasses}",
                            color = SkyCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Mean Peak Confidence: ${series.meanNeuralConfidencePct}%",
                            color = IceMint,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(126.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(DeepMidnight)
                            .pointerInput(result.durationMs) {
                                detectTapGestures { offset ->
                                    val frac = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                    onScrubTimeChanged((frac * result.durationMs).toLong())
                                }
                            }
                            .pointerInput(result.durationMs) {
                                detectHorizontalDragGestures { change, _ ->
                                    change.consume()
                                    val frac = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                    onScrubTimeChanged((frac * result.durationMs).toLong())
                                }
                            }
                    ) {
                        val w = size.width
                        val h = size.height
                        val padV = 12f
                        val plotH = h - padV * 2f

                        // 50% VAR decision threshold line
                        val y50 = padV + plotH * 0.5f
                        drawLine(MidnightBorder, Offset(0f, y50), Offset(w, y50), 1f)

                        val pts = series.points
                        val probPath = Path()
                        for (i in pts.indices) {
                            val x = (i.toFloat() / max(1, pts.lastIndex)) * w
                            val pt = pts[i]
                            if (pt.visualRopePass) {
                                drawRect(
                                    color = SkyCyan.copy(alpha = 0.85f),
                                    topLeft = Offset(x - 1.5f, 4f),
                                    size = Size(3f, 14f)
                                )
                            }
                            val yP = padV + plotH * (1f - pt.neuralVarProb.coerceIn(0f, 1f))
                            if (i == 0) probPath.moveTo(x, yP) else probPath.lineTo(x, yP)
                        }
                        drawPath(path = probPath, color = IceMint, style = Stroke(width = 3.2f, cap = StrokeCap.Round))

                        val cursorX = ((scrubTimeMs.toFloat() / max(1L, result.durationMs)) * w).coerceIn(0f, w)
                        drawLine(GoldAccent, Offset(cursorX, 0f), Offset(cursorX, h), 2.2f)
                    }
                }
            }

            // Card 2: Zoomed ±2.5s Alternating Leg-Drive & Clean HPSS Whip Oscilloscope
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MidnightSurface,
                border = BorderStroke(1.dp, MidnightBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(15.dp)) {
                    Text(
                        text = "Leg-Drive Phase & HPSS Clean-Whip Oscilloscope (±2.5s Zoom)",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Gold bars = EDM-isolated cable whip • Cyan wave = L/R knee-ankle drive",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(8.dp))

                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(114.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(DeepMidnight)
                    ) {
                        val w = size.width
                        val h = size.height
                        val midY = h * 0.5f
                        val winHalfMs = 2_500L
                        val tLo = scrubTimeMs - winHalfMs
                        val tHi = scrubTimeMs + winHalfMs

                        drawLine(MidnightBorder, Offset(0f, midY), Offset(w, midY), 1f)

                        val wavePath = Path()
                        var started = false
                        for (pt in series.points) {
                            if (pt.timeMs !in tLo..tHi) continue
                            val x = ((pt.timeMs - tLo).toFloat() / (winHalfMs * 2f)) * w
                            val barH = max(pt.cleanWhipEnergy, pt.whipEnergy) * (h * 0.82f)
                            if (barH > 2f) {
                                drawRect(
                                    color = GoldAccent.copy(alpha = 0.42f),
                                    topLeft = Offset(x - 2f, h - barH),
                                    size = Size(4f, barH)
                                )
                            }
                            val wy = midY - pt.legOscillator * (h * 0.40f)
                            if (!started) {
                                wavePath.moveTo(x, wy)
                                started = true
                            } else {
                                wavePath.lineTo(x, wy)
                            }
                        }
                        if (started) {
                            drawPath(path = wavePath, color = SkyCyan, style = Stroke(width = 3f, cap = StrokeCap.Round))
                        }

                        for (ev in result.telemetryEvents) {
                            if (ev.timestampMs !in tLo..tHi) continue
                            val x = ((ev.timestampMs - tLo).toFloat() / (winHalfMs * 2f)) * w
                            val isRight = (ev.jumpNumber % 2 == 1)
                            drawCircle(
                                color = if (isRight) IceMint else SkyCyan,
                                radius = if (isRight) 5.2f else 4.0f,
                                center = Offset(x, if (isRight) h * 0.22f else h * 0.78f)
                            )
                        }

                        drawLine(Color.White, Offset(w * 0.5f, 0f), Offset(w * 0.5f, h), 2f)
                    }
                }
            }
        }
    }
}

/**
 * Legacy combined wrapper preserved for compatibility.
 */
@Composable
fun VideoTelemetryDashboard(
    result: VideoAnalysisResult,
    scrubTimeMs: Long,
    onScrubTimeChanged: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        result.ijrcSummary?.takeIf { it.isCompetitionSprintDetected }?.let {
            IjrcCompetitionScoreboardCard(it)
        }
        SpeedAndSplitsTabPanel(result, scrubTimeMs, onScrubTimeChanged)
        VarRopeAndWaveTabPanel(result, scrubTimeMs, onScrubTimeChanged)
    }
}

@Composable
private fun IjrcStatBox(
    title: String,
    value: String,
    sub: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = DeepMidnight,
        border = BorderStroke(1.dp, MidnightBorder),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = title, color = TextSecondary, fontSize = 11.sp, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(text = value, color = accent, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text(text = sub, color = TextSecondary, fontSize = 11.sp, maxLines = 1)
        }
    }
}

private fun findNearestPoseFrame(poses: List<PoseFrame>, timeMs: Long): PoseFrame? {
    if (poses.isEmpty()) return null
    var lo = 0
    var hi = poses.lastIndex
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (poses[mid].timestampMs < timeMs) lo = mid + 1 else hi = mid
    }
    val cand1 = poses[lo]
    val cand0 = if (lo > 0) poses[lo - 1] else cand1
    return if (abs(cand0.timestampMs - timeMs) <= abs(cand1.timestampMs - timeMs)) cand0 else cand1
}

private fun MediaPlayer.seekToSafe(timeMs: Long) {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            seekTo(timeMs, MediaPlayer.SEEK_CLOSEST)
        } else {
            seekTo(timeMs.toInt())
        }
    }
}

private fun applySpeedSafe(mp: MediaPlayer, speed: Float) {
    runCatching {
        val params: PlaybackParams = mp.playbackParams ?: PlaybackParams()
        params.speed = speed.coerceIn(0.15f, 2.0f)
        mp.playbackParams = params
    }
}
