package com.jumpropeai.share

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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

/**
 * Hardware-accelerated custom video watermark exporter powered by Jetpack Media3 Transformer.
 * Burns the transparent JumpRope AI stats sticker (scaled to user preference) directly onto
 * any user-selected video while preserving the original audio track.
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

        val overlaySettings = OverlaySettings.Builder()
            .setAlphaScale(1.0f)
            .build()

        val bitmapOverlay = BitmapOverlay.createStaticBitmapOverlay(watermarkBitmap, overlaySettings)
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

        // Poll Media3 Transformer progress smoothly on main looper
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
