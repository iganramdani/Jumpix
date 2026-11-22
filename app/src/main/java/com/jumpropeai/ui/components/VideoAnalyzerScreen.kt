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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jumpropeai.ui.theme.DarkSurface
import com.jumpropeai.ui.theme.ElectricCyan
import com.jumpropeai.ui.theme.FlameOrange
import com.jumpropeai.ui.theme.GlassBorder
import com.jumpropeai.ui.theme.GlassCard
import com.jumpropeai.ui.theme.GlassDark
import com.jumpropeai.ui.theme.MintGreen
import com.jumpropeai.ui.theme.NeonLime
import com.jumpropeai.ui.theme.SoftRed
import com.jumpropeai.video.VideoAnalysisProgress
import com.jumpropeai.video.VideoAnalysisResult
import java.util.Locale

/**
 * Full-screen MP4 Video Jump Counter studio.
 * Live camera is completely turned OFF while this screen is active.
 * Displays live video frames + real-time AI skeleton overlay + bouncing live counter + live metrics!
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
    onClose: () -> Unit
) {
    val activeBitmap = when {
        isAnalyzing -> progress?.previewBitmap
        result != null -> result.finalPreviewBitmap
        else -> null
    }
    val imageBitmap = remember(activeBitmap) {
        activeBitmap?.takeIf { !it.isRecycled }?.asImageBitmap()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkSurface)
    ) {
        // 1. Live MP4 Video Frame Background (zero live camera!)
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = "Frame Video MP4",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Live AI Skeleton Overlay right on top of the MP4 frame!
            if (isAnalyzing && progress?.latestPose != null) {
                PoseOverlay(
                    poseFrame = progress.latestPose,
                    isAirborne = progress.isAirborne,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Smooth readability gradient scrims
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEB0A0D12))))
            )
            if (result != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x990A0D12))
                )
            }
        }

        // 2. Foreground Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Bar: Back button + Title + Live Fingerprint pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    FilledTonalIconButton(
                        onClick = onClose,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = GlassDark,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Tutup")
                    }

                    Column {
                        Text(
                            text = "Video MP4 Studio",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        val subtitle = when {
                            isAnalyzing -> progress?.stage ?: "Menganalisis..."
                            result != null -> result.fileName
                            else -> "Kamera nonaktif • Fusion v6 AI"
                        }
                        Text(
                            text = subtitle,
                            color = ElectricCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Live Learned Fingerprint Badge
                val fp = if (isAnalyzing) progress?.fingerprint else result?.fingerprint
                if (fp != null && fp.isCalibrated) {
                    Surface(
                        shape = CircleShape,
                        color = GlassDark,
                        border = BorderStroke(1.dp, NeonLime.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = NeonLime,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = fp.shortBadge(),
                                color = NeonLime,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Middle / Bottom depending on state
            when {
                isAnalyzing -> {
                    val prog = progress ?: VideoAnalysisProgress("Menyiapkan...", 0.05f, 0)
                    val bounceScale by animateFloatAsState(
                        targetValue = if (prog.isAirborne) 1.12f else 1.0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "mp4CounterBounce"
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Fun Live Hype & Style Pill
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = GlassDark,
                                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.AutoAwesome,
                                        contentDescription = null,
                                        tint = ElectricCyan,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = prog.style.label,
                                        color = ElectricCyan,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            val hype = when {
                                prog.currentStreak >= 50 -> "UNSTOPPABLE!"
                                prog.currentStreak >= 20 -> "ON FIRE!"
                                else -> null
                            }
                            if (hype != null) {
                                Surface(
                                    shape = CircleShape,
                                    color = FlameOrange.copy(alpha = 0.22f),
                                    border = BorderStroke(1.dp, FlameOrange.copy(alpha = 0.65f))
                                ) {
                                    Text(
                                        text = hype,
                                        color = FlameOrange,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }

                        // Giant Live Jump Counter
                        Text(
                            text = prog.currentJumps.toString(),
                            color = if (prog.isAirborne) NeonLime else Color.White,
                            fontSize = 96.sp,
                            fontWeight = FontWeight.Black,
                            lineHeight = 96.sp,
                            modifier = Modifier.scale(bounceScale)
                        )

                        // Live Metrics Strip
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
                                    text = "${formatVideoMs(prog.currentTimeMs)} / ${formatVideoMs(prog.durationMs)}",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text("•", color = Color.White.copy(alpha = 0.35f))
                                Text(
                                    text = "${prog.currentRpm} RPM",
                                    color = ElectricCyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text("•", color = Color.White.copy(alpha = 0.35f))
                                Text(
                                    text = "${String.format(Locale.US, "%.1f", prog.estimatedCalories)} kkal",
                                    color = FlameOrange,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text("•", color = Color.White.copy(alpha = 0.35f))
                                Text(
                                    text = "Streak ${prog.currentStreak}",
                                    color = NeonLime,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Sleek Live Progress Bar
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            LinearProgressIndicator(
                                progress = { prog.fraction },
                                color = NeonLime,
                                trackColor = Color.White.copy(alpha = 0.2f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(7.dp)
                                    .clip(CircleShape)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = prog.stage,
                                    color = Color.White.copy(alpha = 0.72f),
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "${(prog.fraction * 100).toInt()}%",
                                    color = NeonLime,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                result != null -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "ANALISIS SELESAI",
                            color = NeonLime,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 2.sp
                        )

                        Text(
                            text = result.jumpCount.toString(),
                            color = Color.White,
                            fontSize = 92.sp,
                            fontWeight = FontWeight.Black,
                            lineHeight = 92.sp
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                shape = CircleShape,
                                color = ElectricCyan.copy(alpha = 0.18f),
                                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.55f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.AutoAwesome,
                                        contentDescription = null,
                                        tint = ElectricCyan,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = result.style.label,
                                        color = ElectricCyan,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = GlassCard,
                            border = BorderStroke(1.dp, GlassBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    StudioStat("Durasi", formatVideoMs(result.durationMs), Color.White)
                                    StudioStat("Avg RPM", "${result.averageRpm}", ElectricCyan)
                                    StudioStat("Kalori", String.format(Locale.US, "%.1f", result.estimatedCalories), FlameOrange)
                                }
                                HorizontalDivider(color = GlassBorder)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    StudioStat("Best Streak", "${result.bestStreak}", NeonLime)
                                    StudioStat("Trips", "${result.trips}", SoftRed)
                                    StudioStat("PLL Rescue", "+${result.pllBridged}", MintGreen)
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onPickVideo,
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, GlassBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                            ) {
                                Text("VIDEO LAIN", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = { onShareResult(result) },
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.65f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                            ) {
                                Text("KARTU", color = ElectricCyan, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                            }

                            Button(
                                onClick = { onSaveResultToHistory(result) },
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NeonLime,
                                    contentColor = Color(0xFF0A0D10)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                            ) {
                                Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("SIMPAN", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                            }
                        }
                    }
                }

                else -> {
                    // Empty Picker State (Full-screen clean dark studio, camera OFF!)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = NeonLime.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, NeonLime.copy(alpha = 0.35f)),
                            modifier = Modifier.size(88.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.UploadFile,
                                    contentDescription = null,
                                    tint = NeonLime,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }

                        Text(
                            text = "Hitung Lompatan dari Video",
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = "Pilih video lompat tali (.mp4) dari galeri Anda. Anda bisa menonton live preview video + skeleton AI & angka lompatan bergerak real-time saat dihitung.",
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )

                        if (errorMessage != null) {
                            Text(
                                text = errorMessage,
                                color = SoftRed,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Button(
                            onClick = onPickVideo,
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonLime,
                                contentColor = Color(0xFF0A0D10)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            Icon(Icons.Rounded.Movie, contentDescription = null, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "PILIH FILE MP4",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun StudioStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = color, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
        Text(text = label, color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
    }
}

private fun formatVideoMs(ms: Long): String {
    val totalSec = ms / 1000L
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", min, sec)
}
