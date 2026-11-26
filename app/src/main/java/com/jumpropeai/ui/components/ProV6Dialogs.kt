package com.jumpropeai.ui.components

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jumpropeai.ai.BattleArenaState
import com.jumpropeai.ai.BattleMode
import com.jumpropeai.data.WorkoutSession
import com.jumpropeai.sensors.HeartRateScanSnapshot
import com.jumpropeai.ui.theme.DeepMidnight
import com.jumpropeai.ui.theme.IceMint
import com.jumpropeai.ui.theme.IceMintSoft
import com.jumpropeai.ui.theme.MidnightBorder
import com.jumpropeai.ui.theme.MidnightElevated
import com.jumpropeai.ui.theme.MidnightSurface
import com.jumpropeai.ui.theme.TextPrimary
import com.jumpropeai.ui.theme.TextSecondary
import com.jumpropeai.video.VideoTelemetryEvent
import com.jumpropeai.workout.AdaptiveWeekPlan
import com.jumpropeai.workout.SkillNode
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * v6.1 — Hardware-Bound Contact Camera-Lens rPPG Heart Rate, Live PPG Waveform & 60s Recovery Dialog.
 *
 * Automatically binds Rear Camera + Torch LED at `176×144` while open, extracts Zero-GC YUV capillary
 * red/green mean intensity at ~30 FPS, and plots a live scrolling photoplethysmogram (PPG) pulse trace.
 */
@Composable
fun OpticalHeartRateDialog(
    snapshot: HeartRateScanSnapshot,
    onOpticalSample: (Float, Float) -> Unit = { _, _ -> },
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentCallback by rememberUpdatedState(onOpticalSample)

    // Ring buffer for live scrolling PPG oscilloscope
    val waveRing = remember { FloatArray(90) }
    var waveHead by remember { mutableIntStateOf(0) }
    var waveTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(snapshot.elapsedSec, snapshot.latestWaveSample, snapshot.isFingerDetected) {
        waveRing[waveHead] = if (snapshot.isFingerDetected) snapshot.latestWaveSample else 0f
        waveHead = (waveHead + 1) % waveRing.size
        waveTick++
    }

    // Bind Rear Camera + Torch LED strictly while this dialog is open
    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var boundProvider: ProcessCameraProvider? = null
        val mainExecutor = ContextCompat.getMainExecutor(context)

        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                boundProvider = provider
                provider.unbindAll()

                val resSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(176, 144),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(executor) { imageProxy ->
                    val (meanR, meanG) = extractYuvCenterRedGreen(imageProxy)
                    imageProxy.close()
                    mainExecutor.execute {
                        currentCallback(meanR, meanG)
                    }
                }

                val cam = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    analysis
                )
                cam.cameraControl.enableTorch(true)
            }
        }, mainExecutor)

        onDispose {
            runCatching { boundProvider?.unbindAll() }
            executor.shutdown()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MidnightSurface,
            border = BorderStroke(1.dp, MidnightBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Favorite,
                            contentDescription = null,
                            tint = IceMint,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Camera rPPG Recovery",
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Rear Lens + Torch Active • 100% Phone-Only",
                    color = IceMint,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(14.dp))

                Surface(
                    shape = CircleShape,
                    color = DeepMidnight,
                    border = BorderStroke(
                        width = 2.dp,
                        color = if (snapshot.isFingerDetected) IceMint else MidnightBorder
                    ),
                    modifier = Modifier.size(124.dp)
                ) {
                    Column(
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (snapshot.currentBpm > 0) "${snapshot.currentBpm}" else "--",
                            color = if (snapshot.isFingerDetected) IceMint else TextPrimary,
                            fontSize = 40.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "BPM",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Live Scrolling Capillary PPG Pulse Waveform Chart
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = DeepMidnight,
                    border = BorderStroke(1.dp, MidnightBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp)
                ) {
                    val triggerRedraw = waveTick
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        if (triggerRedraw >= 0) {
                            val midY = size.height * 0.5f
                            drawLine(
                                color = MidnightBorder,
                                start = Offset(0f, midY),
                                end = Offset(size.width, midY),
                                strokeWidth = 1.dp.toPx()
                            )
                            val path = Path()
                            val n = waveRing.size
                            for (i in 0 until n) {
                                val v = waveRing[(waveHead + i) % n]
                                val px = (i.toFloat() / (n - 1).toFloat()) * size.width
                                val py = (midY - v * midY * 0.82f).coerceIn(2f, size.height - 2f)
                                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                            }
                            drawPath(
                                path = path,
                                color = if (snapshot.isFingerDetected) IceMint else TextSecondary.copy(alpha = 0.45f),
                                style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = snapshot.statusMessage,
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MiniMetricTile(
                        label = "Peak BPM",
                        value = if (snapshot.peakBpm > 0) "${snapshot.peakBpm}" else "--",
                        modifier = Modifier.weight(1f)
                    )
                    MiniMetricTile(
                        label = "HRR-60 Drop",
                        value = if (snapshot.hrr60DropBpm > 0) "-${snapshot.hrr60DropBpm}" else "--",
                        modifier = Modifier.weight(1f)
                    )
                    MiniMetricTile(
                        label = "Readiness",
                        value = if (snapshot.readinessScore > 0) "${snapshot.readinessScore}%" else "--",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = IceMint,
                        contentColor = DeepMidnight
                    )
                ) {
                    Text("Done", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Zero-GC YUV_420_888 center-crop Red & Green mean extractor (`0..255`).
 */
private fun extractYuvCenterRedGreen(image: ImageProxy): Pair<Float, Float> {
    val planes = image.planes
    if (planes.size < 3) return 0f to 0f
    val yBuf = planes[0].buffer
    val uBuf = planes[1].buffer
    val vBuf = planes[2].buffer
    val yRowStride = planes[0].rowStride
    val uvRowStride = planes[1].rowStride
    val uvPixStride = planes[1].pixelStride

    val w = image.width
    val h = image.height
    val x0 = max(0, w / 2 - 20)
    val x1 = min(w - 1, w / 2 + 20)
    val y0 = max(0, h / 2 - 20)
    val y1 = min(h - 1, h / 2 + 20)

    var sumR = 0f
    var sumG = 0f
    var count = 0

    var py = y0
    while (py <= y1) {
        var px = x0
        while (px <= x1) {
            val yIdx = py * yRowStride + px
            val uvIdx = (py / 2) * uvRowStride + (px / 2) * uvPixStride
            if (yIdx < yBuf.limit() && uvIdx < uBuf.limit() && uvIdx < vBuf.limit()) {
                val yVal = (yBuf.get(yIdx).toInt() and 0xFF).toFloat()
                val uVal = (uBuf.get(uvIdx).toInt() and 0xFF) - 128f
                val vVal = (vBuf.get(uvIdx).toInt() and 0xFF) - 128f
                val r = (yVal + 1.402f * vVal).coerceIn(0f, 255f)
                val g = (yVal - 0.344f * uVal - 0.714f * vVal).coerceIn(0f, 255f)
                sumR += r
                sumG += g
                count++
            }
            px += 2
        }
        py += 2
    }
    return if (count > 0) (sumR / count) to (sumG / count) else 0f to 0f
}

/**
 * v6.1 — 1-Phone 2-Player Split-Screen Battle HUD Card for Live Arena Duels.
 */
@Composable
fun SplitBattleArenaBanner(
    battleState: BattleArenaState,
    onCycleMode: () -> Unit,
    onResetBattle: () -> Unit,
    onCloseBattle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = DeepMidnight.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, IceMint.copy(alpha = 0.65f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = onCycleMode,
                    shape = CircleShape,
                    color = IceMintSoft
                ) {
                    Text(
                        text = "2P BATTLE • ${battleState.mode.label}",
                        color = IceMint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Row {
                    IconButton(onClick = onResetBattle, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Restart Duel", tint = IceMint, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onCloseBattle, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close Duel", tint = TextSecondary, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MiniMetricTile(
                    label = if (battleState.player1Alive) "Player 1 (Left)" else "Player 1 (OUT)",
                    value = "${battleState.player1Jumps}",
                    modifier = Modifier.weight(1f)
                )
                MiniMetricTile(
                    label = if (battleState.player2Alive) "Player 2 (Right)" else "Player 2 (OUT)",
                    value = "${battleState.player2Jumps}",
                    modifier = Modifier.weight(1f)
                )
            }
            battleState.winnerLabel?.let { win ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = win,
                    color = IceMint,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

/**
 * v5.0 — AI-Verified Skill Tree DAG & 6-Week Adaptive Training Plan Dialog.
 */
@Composable
fun SkillAcademyDialog(
    skillNodes: List<SkillNode>,
    adaptivePlan: List<AdaptiveWeekPlan>,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MidnightSurface,
            border = BorderStroke(1.dp, MidnightBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.EmojiEvents,
                            contentDescription = null,
                            tint = IceMint,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "AI Skill Academy",
                            color = TextPrimary,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Text(
                    text = "Nodes unlock strictly when on-device AI verifies your physical reps & form.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(14.dp))

                skillNodes.forEach { node ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (node.unlocked) IceMintSoft else DeepMidnight,
                        border = BorderStroke(1.dp, if (node.unlocked) IceMint else MidnightBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = node.title,
                                    color = if (node.unlocked) IceMint else TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Icon(
                                    imageVector = if (node.unlocked) Icons.Rounded.CheckCircle else Icons.Rounded.Lock,
                                    contentDescription = null,
                                    tint = if (node.unlocked) IceMint else TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = node.requirementText,
                                color = TextSecondary,
                                fontSize = 11.5.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { node.progressPct / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape),
                                color = IceMint,
                                trackColor = MidnightElevated,
                                strokeCap = StrokeCap.Round
                            )
                        }
                    }
                }

                if (adaptivePlan.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "6-Week Adaptive Training Plan",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    adaptivePlan.forEach { wk ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "W${wk.weekNumber} • ${wk.focusTitle}",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${wk.targetRpm} RPM • ${wk.dailyJumpTarget}/d",
                                color = IceMint,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MidnightBorder)
                ) {
                    Text("Close", color = TextPrimary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * v5.0 — GitHub-Style Consistency Heatmap & Rope Gear Wear Tracker for HistoryTabScreen.
 */
@Composable
fun ConsistencyHeatmapAndGearCard(
    sessions: List<WorkoutSession>,
    gearWearRopeRevs: Int,
    onOpenSkillAcademy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dayCounts = remember(sessions) {
        val map = HashMap<Long, Int>()
        for (s in sessions) {
            val c = Calendar.getInstance().apply {
                timeInMillis = s.timestampMillis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val key = c.timeInMillis
            map[key] = (map[key] ?: 0) + s.jumpCount
        }
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val out = IntArray(84) // 12 weeks x 7 days
        for (i in 83 downTo 0) {
            out[i] = map[today.timeInMillis] ?: 0
            today.add(Calendar.DAY_OF_YEAR, -1)
        }
        out
    }

    val gearMaxRevs = 150_000
    val gearWearFrac = (gearWearRopeRevs.toFloat() / gearMaxRevs.toFloat()).coerceIn(0f, 1f)

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MidnightSurface,
        border = BorderStroke(1.dp, MidnightBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Consistency Heatmap (12 Weeks)",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Native SQLite WAL • Gear Wear & Skill Academy",
                        color = TextSecondary,
                        fontSize = 11.5.sp
                    )
                }
                Surface(
                    onClick = onOpenSkillAcademy,
                    shape = CircleShape,
                    color = IceMintSoft,
                    border = BorderStroke(1.dp, IceMint.copy(alpha = 0.6f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.EmojiEvents,
                            contentDescription = null,
                            tint = IceMint,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Skills",
                            color = IceMint,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                for (week in 0 until 12) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (day in 0 until 7) {
                            val jumps = dayCounts[week * 7 + day]
                            val cellColor = when {
                                jumps >= 1_000 -> IceMint
                                jumps >= 400 -> IceMint.copy(alpha = 0.68f)
                                jumps > 0 -> IceMint.copy(alpha = 0.34f)
                                else -> MidnightElevated
                            }
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(cellColor)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Rope Cable Wear",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${String.format(Locale.US, "%,d", gearWearRopeRevs)} / 150,000 revs",
                    color = IceMint,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { gearWearFrac },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                color = IceMint,
                trackColor = MidnightElevated,
                strokeCap = StrokeCap.Round
            )
        }
    }
}

/**
 * v4.1 — Slow-Mo Biomechanical Telemetry Event Inspector Strip for VideoAnalyzerScreen.
 * Clicking any trick chip jumps the SyncedSlowMoVideoPlayer + Telemetry Charts directly to that timestamp!
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoSlowMoInspectorCard(
    events: List<VideoTelemetryEvent>,
    onJumpToTimestampMs: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (events.isEmpty()) return
    val trickEvents = remember(events) {
        events.filter { !it.move.label.equals("Regular", ignoreCase = true) }.take(16)
    }
    if (trickEvents.isEmpty()) return

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MidnightSurface,
        border = BorderStroke(1.dp, MidnightBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Speed,
                    contentDescription = null,
                    tint = IceMint,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Trick & Footwork Jump Bookmarks (Tap to Scrub)",
                    color = TextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                trickEvents.forEach { ev ->
                    val sec = String.format(Locale.US, "%.1fs", ev.timestampMs / 1000.0)
                    Surface(
                        onClick = { onJumpToTimestampMs?.invoke(ev.timestampMs) },
                        shape = RoundedCornerShape(10.dp),
                        color = IceMintSoft,
                        border = BorderStroke(1.dp, IceMint.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Bolt,
                                contentDescription = null,
                                tint = IceMint,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "${ev.move.label} @ $sec",
                                color = IceMint,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniMetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DeepMidnight,
        border = BorderStroke(1.dp, MidnightBorder),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, color = TextSecondary, fontSize = 10.5.sp)
            Spacer(Modifier.height(2.dp))
            Text(text = value, color = Color(0xFF2EE59D), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}
