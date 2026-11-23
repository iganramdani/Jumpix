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
import androidx.media3.common.util.Size
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
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Hardware-accelerated custom video watermark exporter powered by Jetpack Media3 Transformer.
 *
 * IMPORTANT Media3 detail:
 * In Media3 1.4.x, `OverlayMatrixProvider` scales every `BitmapOverlay` by
 * `(overlaySize.width / videoSize.width, overlaySize.height / videoSize.height)` in pixel space —
 * it does NOT auto-fit the bitmap to the video frame! Therefore, passing a 1080x1920 bitmap onto
 * a 720x1280 video blows the watermark up by 150% (1080/720 = 1.5x) and clips all 4 edges.
 *
 * `VideoFittedBitmapOverlay` overrides `TextureOverlay.configure(videoSize)` to receive Media3's
 * exact post-rotation frame dimensions and builds the transparent canvas at exact 1:1 pixel size
 * (with exact scale compensation if capped for 4K), guaranteeing ZERO unintended enlargement or
 * edge clipping across 480p, 720p, 1080p, 4K, portrait, square, and landscape videos.
 */
object VideoWatermarkExporter {

    private const val MAX_OVERLAY_DIM = 2160

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
        val outFile = File(outDir, "Jumpvex_${System.currentTimeMillis()}.mp4")

        val fallbackSize = readVideoDisplaySize(context, sourceVideoUri)
        val bitmapOverlay = VideoFittedBitmapOverlay(
            sourceWatermark = watermarkBitmap,
            fallbackVideoWidth = fallbackSize.first,
            fallbackVideoHeight = fallbackSize.second
        )
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
     * Custom Media3 [BitmapOverlay] that hooks [configure] to match the exact post-rotation video
     * frame resolution, preventing Media3's `overlaySize / backgroundSize` matrix from enlarging
     * or shrinking the watermark on 480p / 720p / 1080p / 4K videos.
     */
    @UnstableApi
    private class VideoFittedBitmapOverlay(
        private val sourceWatermark: Bitmap,
        private val fallbackVideoWidth: Int,
        private val fallbackVideoHeight: Int
    ) : BitmapOverlay() {

        private var configuredVideoSize: Size? = null
        private var fittedBitmap: Bitmap? = null
        private var overlaySettings: OverlaySettings = OverlaySettings.Builder()
            .setAlphaScale(1.0f)
            .build()

        override fun configure(videoSize: Size) {
            super.configure(videoSize)
            if (configuredVideoSize == videoSize && fittedBitmap != null) return
            configuredVideoSize = videoSize
            rebuildFittedBitmap(videoSize.width, videoSize.height)
        }

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            fittedBitmap?.let { if (!it.isRecycled) return it }
            val w = configuredVideoSize?.width ?: fallbackVideoWidth
            val h = configuredVideoSize?.height ?: fallbackVideoHeight
            return rebuildFittedBitmap(w, h)
        }

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = overlaySettings

        override fun release() {
            super.release()
            fittedBitmap?.let { bmp ->
                if (bmp !== sourceWatermark && !bmp.isRecycled) {
                    bmp.recycle()
                }
            }
            fittedBitmap = null
        }

        private fun rebuildFittedBitmap(videoW: Int, videoH: Int): Bitmap {
            if (videoW <= 0 || videoH <= 0) {
                overlaySettings = OverlaySettings.Builder().setAlphaScale(1.0f).build()
                fittedBitmap = sourceWatermark
                return sourceWatermark
            }

            // Cap bitmap dimensions at MAX_OVERLAY_DIM for 4K memory safety; compensate in OverlaySettings
            val maxSide = max(videoW, videoH)
            val downscale = if (maxSide > MAX_OVERLAY_DIM) MAX_OVERLAY_DIM.toFloat() / maxSide else 1.0f
            val canvasW = (videoW * downscale).roundToInt().coerceAtLeast(1)
            val canvasH = (videoH * downscale).roundToInt().coerceAtLeast(1)

            val out = renderWatermarkIntoExactCanvas(sourceWatermark, canvasW, canvasH)

            // Exact scale so (canvasW / videoW) * scaleX == 1.0f and (canvasH / videoH) * scaleY == 1.0f
            val scaleX = videoW.toFloat() / canvasW.toFloat()
            val scaleY = videoH.toFloat() / canvasH.toFloat()
            overlaySettings = OverlaySettings.Builder()
                .setAlphaScale(1.0f)
                .setScale(scaleX, scaleY)
                .build()

            fittedBitmap?.let { old ->
                if (old !== sourceWatermark && old !== out && !old.isRecycled) {
                    old.recycle()
                }
            }
            fittedBitmap = out
            return out
        }
    }

    /**
     * Draws [watermark] centered inside a transparent [canvasW] x [canvasH] bitmap with 3% safe
     * margin on every edge so zero pixels are ever clipped.
     */
    internal fun renderWatermarkIntoExactCanvas(
        watermark: Bitmap,
        canvasW: Int,
        canvasH: Int
    ): Bitmap {
        val out = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.TRANSPARENT)

        val videoAspect = canvasW.toFloat() / canvasH.toFloat()
        val wmAspect = watermark.width.toFloat() / watermark.height.toFloat()

        // Fit cleanly inside exact video canvas with 3% safe margin on every side
        val safeW = canvasW * 0.94f
        val safeH = canvasH * 0.94f
        val scale = minOf(safeW / watermark.width.toFloat(), safeH / watermark.height.toFloat())
        val drawW = watermark.width * scale
        val drawH = watermark.height * scale
        val left = (canvasW - drawW) / 2f
        val top = if (videoAspect < wmAspect) {
            // Tall portrait (e.g. 9:16): slightly lower-centered to stay balanced & avoid top notch
            (canvasH - drawH) * 0.56f
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
                putExtra(Intent.EXTRA_TEXT, "Jump rope workout with Jumpvex!")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Workout Video"))
        } catch (_: Exception) {
            // Ignore gracefully
        }
    }

    private fun saveVideoToGallery(context: Context, videoFile: File): Boolean {
        val displayName = "Jumpvex_${System.currentTimeMillis()}.mp4"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Jumpvex")
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
                    "Jumpvex"
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
