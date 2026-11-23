package com.jumpropeai.debug

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.jumpropeai.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PerfStats(
    val liveFps: Int = 0,
    val aiProcessUs: Int = 0,
    val usedHeapMb: Int = 0,
    val loggedRows: Int = 0
)

/**
 * Lightweight, Zero-I/O-on-hot-path Diagnostic Telemetry Logger for Jumpvex.
 *
 * When disabled (default): 0 bytes allocated, 0 µs overhead.
 * When enabled in Settings: buffers compact CSV rows (~60 bytes/frame, capped at 15,000 rows)
 * with live FPS, AI CPU microseconds, heap RAM, and full body/foot kinematics so real-device
 * logs can be shared in 1 tap for precision AI calibration.
 */
class SessionDiagnosticLogger {

    private companion object {
        const val MAX_ROWS = 15_000
        const val HEADER =
            "ts_ms,fps,ai_us,heap_mb,jumps,airborne,move,form_score,bpm,sync_pct," +
                "sh_ratio,ank_sep,ank_dy,kn_asym,vlx_vrx,hip_vx,hip_std,l_pitch,r_pitch,v_yaw_l,v_yaw_r"
    }

    private val buffer = StringBuilder(32_768)
    private var rowCount = 0
    private var sessionMeta = ""

    fun startSession(modeLabel: String, sensorLabel: String) {
        buffer.setLength(0)
        rowCount = 0
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        sessionMeta = buildString {
            append("# Jumpvex Diagnostic Log\n")
            append("# App: v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})\n")
            append("# Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})\n")
            append("# Mode: $modeLabel | Sensor: $sensorLabel | Started: $ts\n")
            append(HEADER).append('\n')
        }
        buffer.append(sessionMeta)
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
        if (rowCount >= MAX_ROWS) return
        if (buffer.isEmpty()) {
            startSession("Active", "Fusion")
        }
        buffer.append(tsMs).append(',')
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
    }

    fun loggedRowCount(): Int = rowCount

    fun exportToFile(context: Context): File? {
        if (rowCount == 0) return null
        val outDir = File(context.cacheDir, "shared_cards").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(outDir, "Jumpvex_Log_${stamp}.csv")
        outFile.writeText(buffer.toString())
        return outFile
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

    private fun fmt2(v: Float): String = String.format(Locale.US, "%.2f", v)
    private fun fmt3(v: Float): String = String.format(Locale.US, "%.3f", v)
}
