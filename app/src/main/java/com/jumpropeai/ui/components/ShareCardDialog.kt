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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VideoCall
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import com.jumpropeai.share.VideoWatermarkExporter
import com.jumpropeai.share.WatermarkTemplate
import com.jumpropeai.share.WorkoutCardRenderer
import com.jumpropeai.ui.theme.DangerSoft
import com.jumpropeai.ui.theme.DeepMidnight
import com.jumpropeai.ui.theme.IceMint
import com.jumpropeai.ui.theme.IceMintSoft
import com.jumpropeai.ui.theme.MidnightBorder
import com.jumpropeai.ui.theme.MidnightElevated
import com.jumpropeai.ui.theme.MidnightSurface
import com.jumpropeai.ui.theme.TextPrimary
import com.jumpropeai.ui.theme.TextSecondary
import com.jumpropeai.video.VideoTelemetryEvent
import java.io.File

/**
 * Jumpvex Share Card & Live Animated Telemetry Video Exporter Dialog (v4.0).
 */
@Composable
fun ShareCardDialog(
    session: WorkoutSession,
    backdropBitmap: Bitmap? = null,
    styleLabel: String? = null,
    sourceVideoUri: Uri? = null,
    telemetryEvents: List<VideoTelemetryEvent> = emptyList(),
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
    var selectedTemplate by remember { mutableStateOf(WatermarkTemplate.MINIMAL_STRAVA) }
    var watermarkScale by remember { mutableFloatStateOf(1.0f) }
    var savedSuccess by remember(selectedTheme, selectedTemplate, watermarkScale, customPhoto) { mutableStateOf(false) }

    var isExportingVideo by remember { mutableStateOf(false) }
    var exportStatusLabel by remember { mutableStateOf("Applying watermark to video...") }
    var exportProgress by remember { mutableIntStateOf(0) }
    var exportedVideoFile by remember { mutableStateOf<File?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }

    val hasLiveTelemetryVideo = sourceVideoUri != null && telemetryEvents.isNotEmpty()

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val loaded = context.decodeSampledBitmap(uri, maxDim = 1440)
            if (loaded != null) {
                customPhoto = loaded
                selectedTheme = CardTheme.PHOTO_BACKDROP
            } else {
                Toast.makeText(context, "Failed to load photo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            isExportingVideo = true
            exportStatusLabel = "Rendering static watermark video..."
            exportProgress = 0
            exportedVideoFile = null
            exportError = null

            val transparentWatermark = WorkoutCardRenderer.renderCard(
                session = session,
                theme = CardTheme.TRANSPARENT_STICKER,
                template = selectedTemplate,
                backdropBitmap = null,
                styleLabel = styleLabel,
                overlayScale = watermarkScale
            )
            VideoWatermarkExporter.exportWatermarkedVideo(
                context = context,
                sourceVideoUri = uri,
                watermarkBitmap = transparentWatermark,
                onProgress = { pct -> exportProgress = pct },
                onSuccess = { outFile, savedToGallery ->
                    isExportingVideo = false
                    exportedVideoFile = outFile
                    Toast.makeText(
                        context,
                        if (savedToGallery) "Watermarked video saved to Movies/Jumpvex!" else "Video ready to share!",
                        Toast.LENGTH_LONG
                    ).show()
                },
                onError = { err ->
                    isExportingVideo = false
                    exportError = err
                }
            )
        }
    }

    val renderedBitmap = remember(session, selectedTheme, selectedTemplate, customPhoto, styleLabel, watermarkScale) {
        WorkoutCardRenderer.renderCard(
            session = session,
            theme = selectedTheme,
            template = selectedTemplate,
            backdropBitmap = customPhoto,
            styleLabel = styleLabel,
            overlayScale = watermarkScale
        )
    }

    Dialog(onDismissRequest = { if (!isExportingVideo) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = DeepMidnight,
            border = BorderStroke(1.dp, MidnightBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Export & Share",
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(
                            onClick = { if (!isExportingVideo) photoPickerLauncher.launch(arrayOf("image/*")) },
                            shape = RoundedCornerShape(12.dp),
                            color = MidnightSurface,
                            border = BorderStroke(1.dp, MidnightBorder)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = "Photo", tint = IceMint, modifier = Modifier.size(15.dp))
                                Text("Photo", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Surface(
                            onClick = { if (!isExportingVideo) videoPickerLauncher.launch(arrayOf("video/*")) },
                            shape = RoundedCornerShape(12.dp),
                            color = MidnightSurface,
                            border = BorderStroke(1.dp, IceMint.copy(alpha = 0.55f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Rounded.VideoCall, contentDescription = "Any Video", tint = IceMint, modifier = Modifier.size(16.dp))
                                Text("Video", color = IceMint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // 5 Design Template Selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    WatermarkTemplate.entries.forEach { tpl ->
                        val selected = tpl == selectedTemplate
                        FilterChip(
                            selected = selected,
                            onClick = { selectedTemplate = tpl },
                            label = {
                                Text(
                                    text = tpl.label,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = IceMint,
                                selectedLabelColor = DeepMidnight,
                                containerColor = MidnightSurface,
                                labelColor = TextPrimary
                            )
                        )
                    }
                }

                // Background Theme Chips
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
                                    fontSize = 10.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = IceMint,
                                selectedLabelColor = DeepMidnight,
                                containerColor = MidnightSurface,
                                labelColor = TextPrimary
                            )
                        )
                    }
                }

                // Live Preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.66f)
                        .aspectRatio(1080f / 1350f)
                        .clip(RoundedCornerShape(18.dp))
                ) {
                    if (selectedTheme == CardTheme.TRANSPARENT_STICKER) {
                        CheckerboardBackground(modifier = Modifier.fillMaxSize())
                    }
                    Image(
                        bitmap = renderedBitmap.asImageBitmap(),
                        contentDescription = "Preview Stats Card",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Dynamic Live-Counter Video Export CTA (when opened from analyzed Video Studio result!)
                if (hasLiveTelemetryVideo && !isExportingVideo) {
                    Button(
                        onClick = {
                            isExportingVideo = true
                            exportStatusLabel = "Rendering live animated jump HUD..."
                            exportProgress = 0
                            exportedVideoFile = null
                            exportError = null
                            VideoWatermarkExporter.exportDynamicTelemetryVideo(
                                context = context,
                                sourceVideoUri = sourceVideoUri!!,
                                session = session,
                                telemetryEvents = telemetryEvents,
                                totalDurationMs = session.durationMs,
                                template = selectedTemplate,
                                overlayScale = watermarkScale,
                                onProgress = { pct -> exportProgress = pct },
                                onSuccess = { outFile, savedToGallery ->
                                    isExportingVideo = false
                                    exportedVideoFile = outFile
                                    Toast.makeText(
                                        context,
                                        if (savedToGallery) "Live HUD video saved to Movies/Jumpvex!" else "Live HUD video ready!",
                                        Toast.LENGTH_LONG
                                    ).show()
                                },
                                onError = { err ->
                                    isExportingVideo = false
                                    exportError = err
                                }
                            )
                        },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = IceMint,
                            contentColor = DeepMidnight
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "EXPORT LIVE ANIMATED VIDEO",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                // Video Export Progress / Result
                if (isExportingVideo) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(exportStatusLabel, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text("$exportProgress%", color = IceMint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        LinearProgressIndicator(
                            progress = { exportProgress / 100f },
                            color = IceMint,
                            trackColor = MidnightElevated,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape)
                        )
                    }
                } else if (exportedVideoFile != null) {
                    Surface(
                        onClick = { VideoWatermarkExporter.shareVideoFile(context, exportedVideoFile!!) },
                        shape = RoundedCornerShape(14.dp),
                        color = IceMintSoft,
                        border = BorderStroke(1.dp, IceMint.copy(alpha = 0.65f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = IceMint, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Video Saved! Tap to Share",
                                color = IceMint,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else if (exportError != null) {
                    Text(text = exportError!!, color = DangerSoft, fontSize = 11.sp)
                }

                // Watermark Size Slider
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
                        Text("Watermark Size", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        Text("${(watermarkScale * 100).toInt()}%", color = IceMint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = watermarkScale,
                        onValueChange = { watermarkScale = it },
                        enabled = !isExportingVideo,
                        valueRange = 0.60f..1.30f,
                        colors = SliderDefaults.colors(
                            thumbColor = IceMint,
                            activeTrackColor = IceMint,
                            inactiveTrackColor = MidnightElevated
                        ),
                        modifier = Modifier.height(24.dp)
                    )
                }

                // Save PNG + Share PNG buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val ok = WorkoutCardRenderer.saveToGallery(context, renderedBitmap, "Jumpvex")
                            savedSuccess = ok
                            Toast.makeText(
                                context,
                                if (ok) "Saved to Gallery (Pictures/Jumpvex)" else "Failed to save image",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        enabled = !isExportingVideo,
                        shape = CircleShape,
                        border = BorderStroke(1.dp, if (savedSuccess) IceMint else MidnightBorder),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                    ) {
                        Icon(
                            imageVector = if (savedSuccess) Icons.Rounded.CheckCircle else Icons.Rounded.Download,
                            contentDescription = null,
                            tint = if (savedSuccess) IceMint else TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (savedSuccess) "SAVED" else "SAVE PNG",
                            color = if (savedSuccess) IceMint else TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    Button(
                        onClick = {
                            WorkoutCardRenderer.shareBitmap(context, renderedBitmap, "Jumpvex")
                        },
                        enabled = !isExportingVideo,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = IceMint,
                            contentColor = DeepMidnight
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("SHARE PNG", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
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
