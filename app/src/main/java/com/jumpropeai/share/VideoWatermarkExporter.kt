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
import com.jumpropeai.data.WorkoutSession
import com.jumpropeai.debug.SessionDiagnosticLogger
import com.jumpropeai.video.VideoTelemetryEvent
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Hardware-accelerated video watermark exporter powered by Jetpack Media3 Transformer (v4.0).
 *
 * Supports TWO modes:
 * 1. **Dynamic Animated Telemetry HUD (`exportDynamicTelemetryVideo`)**:
 *    Increments the Jump Counter, Live RPM, Calories, Streak, and Active Trick Callout in real time
 *    synchronized to every jump timestamp in the video, reusing a single OpenGL texture & mutable Bitmap!
 * 2. **Static Summary Watermark (`exportWatermarkedVideo`)**:
 *    Burns a clean static summary card onto any chosen video at 1:1 fitted resolution.
 */
object VideoWatermarkExporter {

    private const val MAX_OVERLAY_DIM = 1920

    @OptIn(UnstableApi::class)
    fun exportDynamicTelemetryVideo(
        context: Context,
        sourceVideoUri: Uri,
        session: WorkoutSession,
        telemetryEvents: List<VideoTelemetryEvent>,
        totalDurationMs: Long,
        template: WatermarkTemplate,
        overlayScale: Float,
        onProgress: (Int) -> Unit,
        onSuccess: (File, Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        SessionDiagnosticLogger.pruneStaleSharedCache(context)
        val mainHandler = Handler(Looper.getMainLooper())
        val outDir = File(context.cacheDir, "shared_cards").apply { mkdirs() }
        val outFile = File(outDir, "Jumpvex_LiveHUD_${System.currentTimeMillis()}.mp4")

        val fallbackSize = readVideoDisplaySize(context, sourceVideoUri)
        val dynamicOverlay = DynamicTelemetryBitmapOverlay(
            session = session,
            events = telemetryEvents,
            totalDurationMs = totalDurationMs.coerceAtLeast(1000L),
            template = template,
            userOverlayScale = overlayScale,
            fallbackVideoWidth = fallbackSize.first,
            fallbackVideoHeight = fallbackSize.second
        )
        val overlayEffect = OverlayEffect(ImmutableList.of(dynamicOverlay))
        val effects = Effects(emptyList(), listOf(overlayEffect))

        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(sourceVideoUri))
            .setEffects(effects)
            .build()

        runTransformerExport(
            context = context,
            editedMediaItem = editedMediaItem,
            outFile = outFile,
            mainHandler = mainHandler,
            onProgress = onProgress,
            onSuccess = onSuccess,
            onError = onError
        )
    }

    @OptIn(UnstableApi::class)
    fun exportWatermarkedVideo(
        context: Context,
        sourceVideoUri: Uri,
        watermarkBitmap: Bitmap,
        clipStartMs: Long? = null,
        clipEndMs: Long? = null,
        onProgress: (Int) -> Unit,
        onSuccess: (File, Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        SessionDiagnosticLogger.pruneStaleSharedCache(context)
        val mainHandler = Handler(Looper.getMainLooper())
        val outDir = File(context.cacheDir, "shared_cards").apply { mkdirs() }
        val prefix = if (clipStartMs != null && clipEndMs != null) "Jumpvex_Highlight_" else "Jumpvex_"
        val outFile = File(outDir, "${prefix}${System.currentTimeMillis()}.mp4")

        val fallbackSize = readVideoDisplaySize(context, sourceVideoUri)
        val bitmapOverlay = VideoFittedBitmapOverlay(
            sourceWatermark = watermarkBitmap,
            fallbackVideoWidth = fallbackSize.first,
            fallbackVideoHeight = fallbackSize.second
        )
        val overlayEffect = OverlayEffect(ImmutableList.of(bitmapOverlay))
        val effects = Effects(emptyList(), listOf(overlayEffect))

        val mediaItemBuilder = MediaItem.Builder().setUri(sourceVideoUri)
        if (clipStartMs != null && clipEndMs != null && clipEndMs > clipStartMs) {
            mediaItemBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clipStartMs.coerceAtLeast(0L))
                    .setEndPositionMs(clipEndMs)
                    .build()
            )
        }

        val editedMediaItem = EditedMediaItem.Builder(mediaItemBuilder.build())
            .setEffects(effects)
            .build()

        runTransformerExport(
            context = context,
            editedMediaItem = editedMediaItem,
            outFile = outFile,
            mainHandler = mainHandler,
            onProgress = onProgress,
            onSuccess = onSuccess,
            onError = onError
        )
    }

    @OptIn(UnstableApi::class)
    private fun runTransformerExport(
        context: Context,
        editedMediaItem: EditedMediaItem,
        outFile: File,
        mainHandler: Handler,
        onProgress: (Int) -> Unit,
        onSuccess: (File, Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
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
                    onError(exportException.localizedMessage ?: "Failed to export video")
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
     * Frame-accurate Dynamic Telemetry Media3 [BitmapOverlay]:
     * Reuses a SINGLE mutable [Bitmap] across the entire video export and redraws at ~12.5 Hz (every 80ms)
     * or immediately upon any jump timestamp. Media3's `getGenerationId()` check uploads to the GPU
     * texture ONLY when the canvas actually redrew!
     */
    @UnstableApi
    private class DynamicTelemetryBitmapOverlay(
        private val session: WorkoutSession,
        private val events: List<VideoTelemetryEvent>,
        private val totalDurationMs: Long,
        private val template: WatermarkTemplate,
        private val userOverlayScale: Float,
        private val fallbackVideoWidth: Int,
        private val fallbackVideoHeight: Int
    ) : BitmapOverlay() {

        private var configuredVideoSize: Size? = null
        private var reusableBitmap: Bitmap? = null
        private var overlaySettings: OverlaySettings = OverlaySettings.Builder()
            .setAlphaScale(1.0f)
            .build()
        private var lastRenderedBucket: Long = Long.MIN_VALUE

        override fun configure(videoSize: Size) {
            super.configure(videoSize)
            if (configuredVideoSize == videoSize && reusableBitmap != null) return
            configuredVideoSize = videoSize
            allocateCanvasForSize(videoSize.width, videoSize.height)
        }

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val bmp = reusableBitmap?.takeIf { !it.isRecycled } ?: run {
                val w = configuredVideoSize?.width ?: fallbackVideoWidth
                val h = configuredVideoSize?.height ?: fallbackVideoHeight
                allocateCanvasForSize(w, h)
            }

            val timeMs = (presentationTimeUs / 1000L).coerceAtLeast(0L)
            // Redraw at ~12.5 Hz (80ms buckets) for smooth timer & pop decay while skipping redundant 60fps draws
            val bucket = timeMs / 80L
            if (bucket != lastRenderedBucket) {
                lastRenderedBucket = bucket
                WorkoutCardRenderer.renderDynamicTelemetryFrame(
                    targetBitmap = bmp,
                    session = session,
                    events = events,
                    presentationTimeMs = timeMs,
                    totalDurationMs = totalDurationMs,
                    template = template,
                    overlayScale = userOverlayScale
                )
            }
            return bmp
        }

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = overlaySettings

        override fun release() {
            super.release()
            reusableBitmap?.let { if (!it.isRecycled) it.recycle() }
            reusableBitmap = null
            lastRenderedBucket = Long.MIN_VALUE
        }

        private fun allocateCanvasForSize(videoW: Int, videoH: Int): Bitmap {
            val effW = if (videoW > 0) videoW else 1080
            val effH = if (videoH > 0) videoH else 1920
            val maxSide = max(effW, effH)
            val downscale = if (maxSide > MAX_OVERLAY_DIM) MAX_OVERLAY_DIM.toFloat() / maxSide else 1.0f
            val canvasW = (effW * downscale).roundToInt().coerceAtLeast(1)
            val canvasH = (effH * downscale).roundToInt().coerceAtLeast(1)

            val scaleX = effW.toFloat() / canvasW.toFloat()
            val scaleY = effH.toFloat() / canvasH.toFloat()
            overlaySettings = OverlaySettings.Builder()
                .setAlphaScale(1.0f)
                .setScale(scaleX, scaleY)
                .build()

            reusableBitmap?.let { old -> if (!old.isRecycled) old.recycle() }
            val fresh = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
            reusableBitmap = fresh
            lastRenderedBucket = Long.MIN_VALUE
            return fresh
        }
    }

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

            val maxSide = max(videoW, videoH)
            val downscale = if (maxSide > MAX_OVERLAY_DIM) MAX_OVERLAY_DIM.toFloat() / maxSide else 1.0f
            val canvasW = (videoW * downscale).roundToInt().coerceAtLeast(1)
            val canvasH = (videoH * downscale).roundToInt().coerceAtLeast(1)

            val out = renderWatermarkIntoExactCanvas(sourceWatermark, canvasW, canvasH)

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

        val safeW = canvasW * 0.94f
        val safeH = canvasH * 0.94f
        val scale = minOf(safeW / watermark.width.toFloat(), safeH / watermark.height.toFloat())
        val drawW = watermark.width * scale
        val drawH = watermark.height * scale
        val left = (canvasW - drawW) / 2f
        val top = if (videoAspect < wmAspect) {
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
                putExtra(Intent.EXTRA_TEXT, "Jump rope workout tracked with Jumpvex AI!")
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
