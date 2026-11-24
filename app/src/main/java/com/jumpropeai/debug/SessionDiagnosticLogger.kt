package com.jumpropeai.debug

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.jumpropeai.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

data class PerfStats(
    val liveFps: Int = 0,
    val aiProcessUs: Int = 0,
    val usedHeapMb: Int = 0,
    val loggedRows: Int = 0,
    val lastSavedLogPath: String? = null
)

/**
 * Zero-GC Unlimited Diagnostic Telemetry Logger for Jumpvex (v14).
 *
 * - Pure integer fixed-point formatting (`appendFixed`) eliminates 11x `String.format` allocations per frame.
 * - Background IO chunk flushing eliminates Main-Thread disk write jank during live workouts.
 * - Automatic stale temp-file pruning prevents `cacheDir/shared_cards` bloat.
 */
class SessionDiagnosticLogger {

    companion object {
        private const val FLUSH_EVERY_ROWS = 60
        private const val RELATIVE_LOG_DIR = "Documents/Jumpvex/DebugLogs"
        private const val STALE_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val HEADER =
            "ts_ms,fps,ai_us,heap_mb,jumps,airborne,move,form_score,bpm,sync_pct," +
                "sh_ratio,ank_sep,ank_dy,kn_asym,vlx_vrx,hip_vx,hip_std,l_pitch,r_pitch,v_yaw_l,v_yaw_r"

        fun pruneStaleSharedCache(context: Context) {
            runCatching {
                val shareDir = File(context.cacheDir, "shared_cards")
                if (!shareDir.exists()) return
                val cutoff = System.currentTimeMillis() - STALE_CACHE_MAX_AGE_MS
                shareDir.listFiles()?.forEach { f ->
                    if (f.isFile && f.lastModified() < cutoff) {
                        runCatching { f.delete() }
                    }
                }
            }
        }
    }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileLock = Any()
    private val chunkBuffer = StringBuilder(8192)
    private var activeCacheFile: File? = null
    private var activeFileName: String = ""
    @Volatile
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
        val appCtx = context.applicationContext
        ioScope.launch {
            pruneStaleSharedCache(appCtx)
            synchronized(fileLock) {
                runCatching { file.writeText(headerBlock) }
            }
        }
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
        appendFixed2(chunkBuffer, shRatio).append(',')
        appendFixed2(chunkBuffer, ankSep).append(',')
        appendFixed2(chunkBuffer, ankDy).append(',')
        appendFixed2(chunkBuffer, knAsym).append(',')
        appendFixed2(chunkBuffer, vLxTimesVRx).append(',')
        appendFixed2(chunkBuffer, hipVx).append(',')
        appendFixed3(chunkBuffer, hipStd).append(',')
        appendFixed2(chunkBuffer, lPitch).append(',')
        appendFixed2(chunkBuffer, rPitch).append(',')
        appendFixed2(chunkBuffer, vYawL).append(',')
        appendFixed2(chunkBuffer, vYawR).append('\n')

        rowCount++
        bufferedRows++
        alreadyAutoSaved = false

        if (bufferedRows >= FLUSH_EVERY_ROWS) {
            val file = activeCacheFile
            if (file != null && chunkBuffer.isNotEmpty()) {
                val payload = chunkBuffer.toString()
                chunkBuffer.setLength(0)
                bufferedRows = 0
                ioScope.launch {
                    synchronized(fileLock) {
                        runCatching {
                            FileOutputStream(file, true).use { fos ->
                                fos.write(payload.toByteArray(Charsets.UTF_8))
                            }
                        }
                    }
                }
            }
        }
    }

    fun loggedRowCount(): Int = rowCount

    private fun flushChunkSync() {
        val file = activeCacheFile ?: return
        val pending = if (chunkBuffer.isNotEmpty()) {
            val s = chunkBuffer.toString()
            chunkBuffer.setLength(0)
            bufferedRows = 0
            s
        } else {
            null
        }
        if (pending != null) {
            synchronized(fileLock) {
                runCatching {
                    FileOutputStream(file, true).use { fos ->
                        fos.write(pending.toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }
    }

    fun autoSaveToDebugFolder(context: Context): String? {
        if (rowCount == 0) return null
        flushChunkSync()
        val file = activeCacheFile ?: return null
        synchronized(fileLock) {
            if (!file.exists()) return null
            if (alreadyAutoSaved) return "$RELATIVE_LOG_DIR/$activeFileName"

            val savedPath = saveFileToDebugFolder(context, file, activeFileName)
            if (savedPath != null) {
                alreadyAutoSaved = true
            }
            return savedPath
        }
    }

    fun exportToFile(context: Context): File? {
        if (rowCount == 0) return null
        flushChunkSync()
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

    private fun appendFixed2(sb: StringBuilder, v: Float): StringBuilder {
        if (v.isNaN() || v.isInfinite()) return sb.append("0.00")
        val scaled = (v * 100f).roundToLong()
        if (scaled < 0L) sb.append('-')
        val a = abs(scaled)
        val whole = a / 100L
        val frac = a % 100L
        sb.append(whole).append('.')
        if (frac < 10L) sb.append('0')
        return sb.append(frac)
    }

    private fun appendFixed3(sb: StringBuilder, v: Float): StringBuilder {
        if (v.isNaN() || v.isInfinite()) return sb.append("0.000")
        val scaled = (v * 1000f).roundToLong()
        if (scaled < 0L) sb.append('-')
        val a = abs(scaled)
        val whole = a / 1000L
        val frac = a % 1000L
        sb.append(whole).append('.')
        if (frac < 100L) sb.append('0')
        if (frac < 10L) sb.append('0')
        return sb.append(frac)
    }
}
