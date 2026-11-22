package com.jumpropeai.ui.components

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.jumpropeai.data.WorkoutSession
import com.jumpropeai.share.CardTheme
import com.jumpropeai.share.WorkoutCardRenderer
import com.jumpropeai.ui.theme.DarkSurface
import com.jumpropeai.ui.theme.ElectricCyan
import com.jumpropeai.ui.theme.GlassBorder
import com.jumpropeai.ui.theme.GlassCard
import com.jumpropeai.ui.theme.MintGreen
import com.jumpropeai.ui.theme.NeonLime

/**
 * Strava-style Share / Save Screenshot Card Dialog.
 * Lets the user preview and switch between Dark Poster, Transparent Sticker, or Video Backdrop,
 * then save directly to Gallery (Pictures/JumpRopeAI) or share to Instagram/WhatsApp.
 */
@Composable
fun ShareCardDialog(
    session: WorkoutSession,
    backdropBitmap: Bitmap? = null,
    styleLabel: String? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val availableThemes = remember(backdropBitmap) {
        if (backdropBitmap != null && !backdropBitmap.isRecycled) {
            listOf(CardTheme.PHOTO_BACKDROP, CardTheme.DARK_POSTER, CardTheme.TRANSPARENT_STICKER)
        } else {
            listOf(CardTheme.DARK_POSTER, CardTheme.TRANSPARENT_STICKER)
        }
    }
    var selectedTheme by remember(availableThemes) { mutableStateOf(availableThemes.first()) }
    var savedSuccess by remember(selectedTheme) { mutableStateOf(false) }

    val renderedBitmap = remember(session, selectedTheme, backdropBitmap, styleLabel) {
        WorkoutCardRenderer.renderCard(
            session = session,
            theme = selectedTheme,
            backdropBitmap = backdropBitmap,
            styleLabel = styleLabel
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, GlassBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Kartu Statistik",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                // Theme selector chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                ) {
                    availableThemes.forEach { theme ->
                        val selected = theme == selectedTheme
                        FilterChip(
                            selected = selected,
                            onClick = { selectedTheme = theme },
                            label = {
                                Text(
                                    text = theme.label,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricCyan,
                                selectedLabelColor = Color(0xFF0A0D10),
                                containerColor = GlassCard,
                                labelColor = Color.White
                            )
                        )
                    }
                }

                // Live Preview of the generated Strava-style PNG (with checkerboard behind transparent sticker!)
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.84f)
                        .aspectRatio(1080f / 1350f)
                        .clip(RoundedCornerShape(18.dp))
                ) {
                    if (selectedTheme == CardTheme.TRANSPARENT_STICKER) {
                        CheckerboardBackground(modifier = Modifier.fillMaxSize())
                    }
                    Image(
                        bitmap = renderedBitmap.asImageBitmap(),
                        contentDescription = "Preview Kartu Statistik",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Save to Gallery + Share buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val ok = WorkoutCardRenderer.saveToGallery(context, renderedBitmap, "JumpRopeAI")
                            savedSuccess = ok
                            Toast.makeText(
                                context,
                                if (ok) "Tersimpan di Galeri (Pictures/JumpRopeAI)" else "Gagal menyimpan gambar",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, if (savedSuccess) MintGreen else GlassBorder),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                    ) {
                        Icon(
                            imageVector = if (savedSuccess) Icons.Rounded.CheckCircle else Icons.Rounded.Download,
                            contentDescription = null,
                            tint = if (savedSuccess) MintGreen else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (savedSuccess) "TERSIMPAN" else "SIMPAN",
                            color = if (savedSuccess) MintGreen else Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }

                    Button(
                        onClick = {
                            WorkoutCardRenderer.shareBitmap(context, renderedBitmap, "JumpRopeAI")
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonLime,
                            contentColor = Color(0xFF0A0D10)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "BAGIKAN",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckerboardBackground(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val tile = 20f
        val light = Color(0xFF2B313C)
        val dark = Color(0xFF1C2129)
        val cols = (size.width / tile).toInt() + 1
        val rows = (size.height / tile).toInt() + 1
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                drawRect(
                    color = if ((r + c) % 2 == 0) light else dark,
                    topLeft = Offset(c * tile, r * tile),
                    size = Size(tile, tile)
                )
            }
        }
    }
}
