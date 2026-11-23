package com.jumpropeai.debug

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.jumpropeai.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PerfStats(
    val liveFps: Int = 0,
    val aiProcessUs: Int = 0,
    val usedHeapMb: Int = 0,
    val loggedRows: Int = 0,
    val lastSavedLogPath: String? = null
)

/**
 * Unlimited-length, chunk-flushed Diagnostic Telemetry Logger for Jumpvex.
 *
 * - NO row limit: flushes in lightweight 60-row chunks directly to disk so even 2-hour sessions
 *   use near-zero RAM.
 * - Auto-saves every completed Live Workout & Video Studio log directly into
 *   `Documents/Jumpvex/DebugLogs/` (visible immediately in Phone File Manager, zero permissions needed on Android 10+).
 */
class SessionDiagnosticLogger {

    private companion object {
        const val FLUSH_EVERY_ROWS = 60
        const val RELATIVE_LOG_DIR = "Documents/Jumpvex/DebugLogs"
        const val HEADER =
            "ts_ms,fps,ai_us,heap_mb,jumps,airborne,move,form_score,bpm,sync_pct," +
                "sh_ratio,ank_sep,ank_dy,kn_asym,vlx_vrx,hip_vx,hip_std,l_pitch,r_pitch,v_yaw_l,v_yaw_r"
    }

    private val chunkBuffer = StringBuilder(8192)
    private var activeCacheFile: File? = null
    private var activeFileName: String = ""
    private var rowCount = 0
    private var bufferedRows = 0
    private var alreadyAutoSaved = false

    fun startSession(
        context: Context,
        modeLabel: String,
        sensorLabel: String,
        filePrefix: String = "Live"
    ) {
        chunkBuffer.setLength(0)
        rowCount = 0
        bufferedRows = 0
        alreadyAutoSaved = false

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safePrefix = filePrefix
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .take(36)
            .ifBlank { "Session" }
        activeFileName = "Jumpvex_${safePrefix}_${stamp}.csv"

        val outDir = File(context.cacheDir, "shared_cards").apply { mkdirs() }
        val file = File(outDir, activeFileName)
        activeCacheFile = file

        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val headerBlock = buildString {
            append("# Jumpvex Diagnostic Log (Unlimited)\n")
            append("# App: v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})\n")
            append("# Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})\n")
            append("# Mode: $modeLabel | Sensor: $sensorLabel | Started: $ts\n")
            append(HEADER).append('\n')
        }
        runCatching { file.writeText(headerBlock) }
    }

    fun recordFrame(
        tsMs: Long,
        fps: Int,
        aiUs: Int,
        heapMb: Int,
        jumps: Int,
        isAirborne: Boolean,
        moveName: String,
        formScore: Int,
        bpm: Int,
        syncPct: Int,
        shRatio: Float,
        ankSep: Float,
        ankDy: Float,
        knAsym: Float,
        vLxTimesVRx: Float,
        hipVx: Float,
        hipStd: Float,
        lPitch: Float,
        rPitch: Float,
        vYawL: Float,
        vYawR: Float
    ) {
        chunkBuffer.append(tsMs).append(',')
            .append(fps).append(',')
            .append(aiUs).append(',')
            .append(heapMb).append(',')
            .append(jumps).append(',')
            .append(if (isAirborne) 1 else 0).append(',')
            .append(moveName).append(',')
            .append(formScore).append(',')
            .append(bpm).append(',')
            .append(syncPct).append(',')
            .append(fmt2(shRatio)).append(',')
            .append(fmt2(ankSep)).append(',')
            .append(fmt2(ankDy)).append(',')
            .append(fmt2(knAsym)).append(',')
            .append(fmt2(vLxTimesVRx)).append(',')
            .append(fmt2(hipVx)).append(',')
            .append(fmt3(hipStd)).append(',')
            .append(fmt2(lPitch)).append(',')
            .append(fmt2(rPitch)).append(',')
            .append(fmt2(vYawL)).append(',')
            .append(fmt2(vYawR)).append('\n')
        rowCount++
        bufferedRows++
        alreadyAutoSaved = false

        if (bufferedRows >= FLUSH_EVERY_ROWS) {
            flushChunkToCache()
        }
    }

    fun loggedRowCount(): Int = rowCount

    private fun flushChunkToCache() {
        val file = activeCacheFile ?: return
        if (chunkBuffer.isEmpty()) return
        runCatching {
            FileOutputStream(file, true).use { fos ->
                fos.write(chunkBuffer.toString().toByteArray(Charsets.UTF_8))
            }
        }
        chunkBuffer.setLength(0)
        bufferedRows = 0
    }

    /**
     * Flushes all remaining rows and auto-saves the CSV into `Documents/Jumpvex/DebugLogs/`.
     * Returns the display path (e.g. "Documents/Jumpvex/DebugLogs/Jumpvex_...csv") or null if empty.
     */
    fun autoSaveToDebugFolder(context: Context): String? {
        if (rowCount == 0) return null
        flushChunkToCache()
        val file = activeCacheFile ?: return null
        if (!file.exists()) return null
        if (alreadyAutoSaved) return "$RELATIVE_LOG_DIR/$activeFileName"

        val savedPath = saveFileToDebugFolder(context, file, activeFileName)
        if (savedPath != null) {
            alreadyAutoSaved = true
        }
        return savedPath
    }

    fun exportToFile(context: Context): File? {
        if (rowCount == 0) return null
        flushChunkToCache()
        autoSaveToDebugFolder(context)
        return activeCacheFile?.takeIf { it.exists() }
    }

    fun shareLogFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Jumpvex AI Diagnostic Log (${Build.MODEL})")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, "Share Jumpvex Diagnostic Log").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun saveFileToDebugFolder(context: Context, sourceFile: File, fileName: String): String? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_LOG_DIR)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val collection = MediaStore.Files.getContentUri("external")
                val uri = resolver.insert(collection, values) ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(out)
                    }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "$RELATIVE_LOG_DIR/$fileName"
            } else {
                val dir = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                    "Jumpvex/DebugLogs"
                ).apply { mkdirs() }
                val dest = File(dir, fileName)
                sourceFile.copyTo(dest, overwrite = true)
                dest.absolutePath
            }
        }.getOrNull()
    }

    private fun fmt2(v: Float): String = String.format(Locale.US, "%.2f", v)
    private fun fmt3(v: Float): String = String.format(Locale.US, "%.3f", v)
}
