package com.jumpropeai.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * Supports:
 * - Custom background photo picker from gallery
 * - Resizable stats watermark (60% - 130%) via smooth slider
 * - 3 themes: Dark Poster, Transparent Sticker (PNG), and Custom Photo Backdrop
 * - Save directly to Gallery (Pictures/JumpRopeAI) or Share to Instagram/WhatsApp
 */
@Composable
fun ShareCardDialog(
    session: WorkoutSession,
    backdropBitmap: Bitmap? = null,
    styleLabel: String? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var customPhoto by remember(backdropBitmap) { mutableStateOf(backdropBitmap) }
    var selectedTheme by remember(backdropBitmap) {
        mutableStateOf(
            if (backdropBitmap != null && !backdropBitmap.isRecycled) {
                CardTheme.PHOTO_BACKDROP
            } else {
                CardTheme.DARK_POSTER
            }
        )
    }
    var watermarkScale by remember { mutableFloatStateOf(1.0f) }
    var savedSuccess by remember(selectedTheme, watermarkScale, customPhoto) { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val loaded = context.decodeSampledBitmap(uri, maxDim = 1440)
            if (loaded != null) {
                customPhoto = loaded
                selectedTheme = CardTheme.PHOTO_BACKDROP
            } else {
                Toast.makeText(context, "Gagal memuat foto", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val renderedBitmap = remember(session, selectedTheme, customPhoto, styleLabel, watermarkScale) {
        WorkoutCardRenderer.renderCard(
            session = session,
            theme = selectedTheme,
            backdropBitmap = customPhoto,
            styleLabel = styleLabel,
            overlayScale = watermarkScale
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
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Kartu Statistik",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold
                    )

                    // 1-tap Custom Photo Picker button
                    Surface(
                        onClick = { photoPickerLauncher.launch(arrayOf("image/*")) },
                        shape = RoundedCornerShape(12.dp),
                        color = GlassCard,
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.55f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AddPhotoAlternate,
                                contentDescription = "Pilih Foto",
                                tint = ElectricCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Pilih Foto",
                                color = ElectricCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Theme selector chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                ) {
                    val themes = if (customPhoto != null && !customPhoto!!.isRecycled) {
                        listOf(CardTheme.PHOTO_BACKDROP, CardTheme.DARK_POSTER, CardTheme.TRANSPARENT_STICKER)
                    } else {
                        listOf(CardTheme.DARK_POSTER, CardTheme.TRANSPARENT_STICKER)
                    }
                    themes.forEach { theme ->
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

                // Live Preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.78f)
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

                // Watermark / Stats Overlay Size Slider (60% - 130%)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Ukuran Watermark",
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${(watermarkScale * 100).toInt()}%",
                            color = NeonLime,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = watermarkScale,
                        onValueChange = { watermarkScale = it },
                        valueRange = 0.60f..1.30f,
                        colors = SliderDefaults.colors(
                            thumbColor = NeonLime,
                            activeTrackColor = NeonLime,
                            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier.height(28.dp)
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
                            .height(48.dp)
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
                            .height(48.dp)
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

private fun Context.decodeSampledBitmap(uri: Uri, maxDim: Int): Bitmap? {
    return runCatching {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
        val maxSide = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
        var sample = 1
        while (maxSide / sample > maxDim) sample *= 2
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
    }.getOrNull()
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
