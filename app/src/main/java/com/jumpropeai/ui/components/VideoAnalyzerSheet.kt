package com.jumpropeai.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jumpropeai.ui.theme.DarkSurface
import com.jumpropeai.ui.theme.ElectricCyan
import com.jumpropeai.ui.theme.FlameOrange
import com.jumpropeai.ui.theme.GlassBorder
import com.jumpropeai.ui.theme.GlassCard
import com.jumpropeai.ui.theme.MintGreen
import com.jumpropeai.ui.theme.NeonLime
import com.jumpropeai.ui.theme.SoftRed
import com.jumpropeai.video.VideoAnalysisProgress
import com.jumpropeai.video.VideoAnalysisResult
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoAnalyzerSheet(
    isAnalyzing: Boolean,
    progress: VideoAnalysisProgress?,
    result: VideoAnalysisResult?,
    errorMessage: String?,
    onPickVideo: () -> Unit,
    onSaveResultToHistory: (VideoAnalysisResult) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Movie,
                    contentDescription = null,
                    tint = NeonLime,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "Hitung dari Video MP4",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
            }

            when {
                isAnalyzing -> {
                    val prog = progress ?: VideoAnalysisProgress("Menyiapkan...", 0.05f, 0)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = prog.currentJumps.toString(),
                        color = NeonLime,
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Black,
                        lineHeight = 74.sp
                    )
                    Text(
                        text = "LOMPATAN TERDETEKSI",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    LinearProgressIndicator(
                        progress = { prog.fraction },
                        color = NeonLime,
                        trackColor = Color.White.copy(alpha = 0.15f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape)
                    )
                    Text(
                        text = prog.stage,
                        color = ElectricCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Fusion v6 menganalisis pose tubuh + spektrum suara tali secara bersamaan...",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }

                result != null -> {
                    Text(
                        text = result.fileName,
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 12.sp,
                        maxLines = 1
                    )

                    Text(
                        text = result.jumpCount.toString(),
                        color = NeonLime,
                        fontSize = 76.sp,
                        fontWeight = FontWeight.Black,
                        lineHeight = 78.sp
                    )

                    // Fun Style & Self-Learning Fingerprint badges
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = ElectricCyan.copy(alpha = 0.16f),
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

                        if (result.hadAudioTrack && result.fingerprint.isCalibrated) {
                            Surface(
                                shape = CircleShape,
                                color = NeonLime.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, NeonLime.copy(alpha = 0.45f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.GraphicEq,
                                        contentDescription = null,
                                        tint = NeonLime,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = result.fingerprint.shortBadge(),
                                        color = NeonLime,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = GlassCard,
                        border = BorderStroke(1.dp, GlassBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                ResultStat("Durasi", formatVideoMs(result.durationMs), Color.White)
                                ResultStat("Avg RPM", "${result.averageRpm}", ElectricCyan)
                                ResultStat("Kalori", String.format(Locale.US, "%.1f", result.estimatedCalories), FlameOrange)
                            }
                            HorizontalDivider(color = GlassBorder)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                ResultStat("Best Streak", "${result.bestStreak}", NeonLime)
                                ResultStat("Trips", "${result.trips}", SoftRed)
                                ResultStat("PLL Rescue", "+${result.pllBridged}", MintGreen)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onPickVideo,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, GlassBorder),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Text("VIDEO LAIN", color = Color.White, fontWeight = FontWeight.Bold)
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
                                .height(50.dp)
                        ) {
                            Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("SIMPAN", fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }

                else -> {
                    Surface(
                        shape = CircleShape,
                        color = NeonLime.copy(alpha = 0.14f),
                        modifier = Modifier.size(72.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.UploadFile,
                                contentDescription = null,
                                tint = NeonLime,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }

                    Text(
                        text = "Pilih video lompat tali (.mp4) dari galeri Anda. Fusion v6 AI akan otomatis mempelajari suara tali & menghitung setiap lompatan secara akurat.",
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

                    Button(
                        onClick = onPickVideo,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonLime,
                            contentColor = Color(0xFF0A0D10)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Icon(Icons.Rounded.Movie, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "PILIH FILE MP4",
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
private fun ResultStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = color, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        Text(text = label, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
    }
}

private fun formatVideoMs(ms: Long): String {
    val totalSec = ms / 1000L
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", min, sec)
}
