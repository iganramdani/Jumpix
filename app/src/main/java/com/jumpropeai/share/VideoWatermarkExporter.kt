package com.jumpropeai.share

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Hardware-accelerated custom video watermark exporter powered by Jetpack Media3 Transformer.
 *
 * Automatically inspects the source video's exact aspect ratio (9:16 portrait, 4:5, 1:1, 16:9)
 * and fits the transparent watermark inside a matching-aspect transparent canvas so ZERO pixels
 * are ever clipped on left, right, top, or bottom!
 */
object VideoWatermarkExporter {

    @OptIn(UnstableApi::class)
    fun exportWatermarkedVideo(
        context: Context,
        sourceVideoUri: Uri,
        watermarkBitmap: Bitmap,
        onProgress: (Int) -> Unit,
        onSuccess: (File, Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        val outDir = File(context.cacheDir, "shared_cards").apply { mkdirs() }
        val outFile = File(outDir, "JumpRopeAI_${System.currentTimeMillis()}.mp4")

        // Match the watermark canvas aspect ratio to the source video so Media3 never clips edges!
        val fittedWatermark = fitWatermarkToVideoAspect(context, sourceVideoUri, watermarkBitmap)

        val overlaySettings = OverlaySettings.Builder()
            .setAlphaScale(1.0f)
            .build()

        val bitmapOverlay = BitmapOverlay.createStaticBitmapOverlay(fittedWatermark, overlaySettings)
        val overlayEffect = OverlayEffect(ImmutableList.of(bitmapOverlay))
        val effects = Effects(emptyList(), listOf(overlayEffect))

        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(sourceVideoUri))
            .setEffects(effects)
            .build()

        val progressHolder = ProgressHolder()
        var isFinished = false

        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    isFinished = true
                    val savedToGallery = saveVideoToGallery(context, outFile)
                    onProgress(100)
                    onSuccess(outFile, savedToGallery)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    isFinished = true
                    onError(exportException.localizedMessage ?: "Gagal mengekspor video")
                }
            })
            .build()

        transformer.start(editedMediaItem, outFile.absolutePath)

        val pollRunnable = object : Runnable {
            override fun run() {
                if (isFinished) return
                val state = transformer.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(progressHolder.progress.coerceIn(0, 99))
                }
                mainHandler.postDelayed(this, 200L)
            }
        }
        mainHandler.postDelayed(pollRunnable, 200L)
    }

    /**
     * Reads the display-oriented video aspect ratio and embeds the watermark inside a transparent
     * bitmap with the exact same aspect ratio + safe horizontal margin so edges are never clipped.
     */
    private fun fitWatermarkToVideoAspect(
        context: Context,
        videoUri: Uri,
        watermark: Bitmap
    ): Bitmap {
        val (vidW, vidH) = readVideoDisplaySize(context, videoUri)
        if (vidW <= 0 || vidH <= 0) return watermark

        val videoAspect = vidW.toFloat() / vidH.toFloat()
        val wmAspect = watermark.width.toFloat() / watermark.height.toFloat()

        // Build a crisp high-res transparent canvas matching the exact video aspect ratio
        val canvasW: Int
        val canvasH: Int
        if (videoAspect <= 1f) {
            // Portrait or square video (e.g. 9:16 -> 1080 x 1920)
            canvasW = 1080
            canvasH = (1080f / videoAspect).roundToInt().coerceIn(1080, 2400)
        } else {
            // Landscape video (e.g. 16:9 -> 1920 x 1080)
            canvasH = 1080
            canvasW = (1080f * videoAspect).roundToInt().coerceIn(1080, 2400)
        }

        val out = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.TRANSPARENT)

        // Fit watermark cleanly inside canvas with 4% safe padding on every side
        val safeW = canvasW * 0.94f
        val safeH = canvasH * 0.94f
        val scale = minOf(safeW / watermark.width.toFloat(), safeH / watermark.height.toFloat())
        val drawW = watermark.width * scale
        val drawH = watermark.height * scale
        val left = (canvasW - drawW) / 2f
        // On tall 9:16 videos, place watermark slightly lower-centered so top/bottom feel balanced
        val top = if (videoAspect < wmAspect) {
            (canvasH - drawH) * 0.65f
        } else {
            (canvasH - drawH) / 2f
        }

        canvas.drawBitmap(
            watermark,
            null,
            RectF(left, top, left + drawW, top + drawH),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        return out
    }

    private fun readVideoDisplaySize(context: Context, uri: Uri): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) h to w else w to h
        } catch (_: Exception) {
            0 to 0
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun shareVideoFile(context: Context, videoFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                videoFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "Latihan lompat tali dengan JumpRope AI!")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Bagikan Video Latihan"))
        } catch (_: Exception) {
            // Ignore gracefully
        }
    }

    private fun saveVideoToGallery(context: Context, videoFile: File): Boolean {
        val displayName = "JumpRopeAI_${System.currentTimeMillis()}.mp4"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/JumpRopeAI")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(videoFile).use { input -> input.copyTo(out) }
                } ?: return false
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "JumpRopeAI"
                )
                dir.mkdirs()
                val dest = File(dir, displayName)
                FileInputStream(videoFile).use { input ->
                    FileOutputStream(dest).use { out -> input.copyTo(out) }
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
