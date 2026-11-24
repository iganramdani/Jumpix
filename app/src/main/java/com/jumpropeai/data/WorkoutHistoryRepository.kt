package com.jumpropeai.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.jumpropeai.workout.WorkoutModeType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Crash-safe, thread-safe local persistence for rich workout sessions (up to 5,000 sessions / ~13+ years).
 *
 * Uses atomic write-temp-then-rename (`workout_history.json.tmp` -> `workout_history.json`) plus automatic
 * `.bak` recovery so unexpected battery loss or process termination NEVER corrupts athlete history.
 */
class WorkoutHistoryRepository(private val storageFile: File) {

    private val lock = Any()
    private var memoryCache: List<WorkoutSession>? = null

    private val tempFile: File
        get() = File(storageFile.parentFile, "${storageFile.name}.tmp")
    private val backupFile: File
        get() = File(storageFile.parentFile, "${storageFile.name}.bak")

    fun loadSessions(): List<WorkoutSession> = synchronized(lock) {
        memoryCache?.let { return it }
        val loaded = readFromFileOrNull(storageFile)
            ?: readFromFileOrNull(backupFile)
            ?: emptyList()
        memoryCache = loaded
        loaded
    }

    fun saveSession(session: WorkoutSession): List<WorkoutSession> = upsertSession(session)

    fun upsertSession(session: WorkoutSession): List<WorkoutSession> = synchronized(lock) {
        val existing = loadSessions().filterNot { it.id == session.id }
        val updated = (listOf(session) + existing)
            .sortedByDescending { it.timestampMillis }
            .take(MAX_SESSIONS)
        memoryCache = updated
        writeSessionsAtomically(updated)
        updated
    }

    fun deleteSession(sessionId: String): List<WorkoutSession> = synchronized(lock) {
        val updated = loadSessions().filterNot { it.id == sessionId }
        memoryCache = updated
        writeSessionsAtomically(updated)
        updated
    }

    fun clearAll(): List<WorkoutSession> = synchronized(lock) {
        memoryCache = emptyList()
        writeSessionsAtomically(emptyList())
        emptyList()
    }

    fun exportBackupCsvAndShare(context: Context): Boolean {
        val sessions = loadSessions()
        if (sessions.isEmpty()) return false
        return runCatching {
            val outDir = File(context.cacheDir, "shared_cards").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            val outFile = File(outDir, "Jumpvex_History_Backup_$stamp.csv")
            val sb = StringBuilder(4096)
            sb.append("date_utc,mode,jumps,duration_sec,avg_rpm,peak_rpm,calories_kcal,best_streak,trips,form_score,beat_sync_pct,bpm,combos,rope_type,tricks\n")
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            for (s in sessions) {
                val tricksStr = s.moveBreakdown.entries.joinToString(";") { "${it.key}:${it.value}" }
                sb.append(fmt.format(Date(s.timestampMillis))).append(',')
                    .append('"').append(s.modeLabel.replace('"', '\'')).append('"').append(',')
                    .append(s.jumpCount).append(',')
                    .append(s.durationMs / 1000L).append(',')
                    .append(s.averageRpm).append(',')
                    .append(s.peakRpm).append(',')
                    .append(String.format(Locale.US, "%.1f", s.estimatedCalories)).append(',')
                    .append(s.bestStreak).append(',')
                    .append(s.trips).append(',')
                    .append(s.formScore).append(',')
                    .append(s.beatSyncScore).append(',')
                    .append(s.detectedBpm).append(',')
                    .append(s.comboCount).append(',')
                    .append('"').append(s.ropeTypeLabel).append('"').append(',')
                    .append('"').append(tricksStr).append('"').append('\n')
            }
            outFile.writeText(sb.toString())

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Jumpvex Workout History Backup (${sessions.size} Sessions)")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "Export Workout History Backup").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        }.getOrDefault(false)
    }

    private fun readFromFileOrNull(file: File): List<WorkoutSession>? {
        if (!file.exists()) return null
        return try {
            val content = file.readText()
            if (content.isBlank()) return emptyList()
            val array = JSONArray(content)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val modeType = runCatching {
                        WorkoutModeType.valueOf(obj.optString("modeType", WorkoutModeType.FREE.name))
                    }.getOrDefault(WorkoutModeType.FREE)

                    val movesMap = LinkedHashMap<String, Int>()
                    obj.optJSONObject("moveBreakdown")?.let { mb ->
                        val keys = mb.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val v = mb.optInt(k, 0)
                            if (v > 0) movesMap[k] = v
                        }
                    }

                    add(
                        WorkoutSession(
                            id = obj.getString("id"),
                            timestampMillis = obj.getLong("timestampMillis"),
                            modeLabel = obj.optString("modeLabel", modeType.label),
                            modeType = modeType,
                            jumpCount = obj.getInt("jumpCount"),
                            durationMs = obj.getLong("durationMs"),
                            averageRpm = obj.optInt("averageRpm", 0),
                            peakRpm = obj.optInt("peakRpm", 0),
                            estimatedCalories = obj.optDouble("estimatedCalories", 0.0),
                            bestStreak = obj.optInt("bestStreak", 0),
                            trips = obj.optInt("trips", 0),
                            formScore = obj.optInt("formScore", 0),
                            beatSyncScore = obj.optInt("beatSyncScore", 0),
                            detectedBpm = obj.optInt("detectedBpm", 0),
                            comboCount = obj.optInt("comboCount", 0),
                            ropeTypeLabel = obj.optString("ropeTypeLabel", "Speed Cable"),
                            moveBreakdown = movesMap
                        )
                    )
                }
            }.sortedByDescending { it.timestampMillis }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeSessionsAtomically(sessions: List<WorkoutSession>) {
        try {
            storageFile.parentFile?.mkdirs()
            val array = JSONArray()
            for (s in sessions) {
                val movesObj = JSONObject()
                for ((k, v) in s.moveBreakdown) {
                    if (v > 0) movesObj.put(k, v)
                }
                val obj = JSONObject().apply {
                    put("id", s.id)
                    put("timestampMillis", s.timestampMillis)
                    put("modeLabel", s.modeLabel)
                    put("modeType", s.modeType.name)
                    put("jumpCount", s.jumpCount)
                    put("durationMs", s.durationMs)
                    put("averageRpm", s.averageRpm)
                    put("peakRpm", s.peakRpm)
                    put("estimatedCalories", s.estimatedCalories)
                    put("bestStreak", s.bestStreak)
                    put("trips", s.trips)
                    put("formScore", s.formScore)
                    put("beatSyncScore", s.beatSyncScore)
                    put("detectedBpm", s.detectedBpm)
                    put("comboCount", s.comboCount)
                    put("ropeTypeLabel", s.ropeTypeLabel)
                    if (movesObj.length() > 0) {
                        put("moveBreakdown", movesObj)
                    }
                }
                array.put(obj)
            }
            val serialized = array.toString()
            tempFile.writeText(serialized)
            if (storageFile.exists()) {
                runCatching { storageFile.copyTo(backupFile, overwrite = true) }
            }
            if (!tempFile.renameTo(storageFile)) {
                tempFile.copyTo(storageFile, overwrite = true)
                tempFile.delete()
            }
        } catch (_: Exception) {
            // Preserve existing files if storage is full
        }
    }

    companion object {
        private const val MAX_SESSIONS = 5_000

        fun computeStats(sessions: List<WorkoutSession>): CumulativeStats {
            if (sessions.isEmpty()) return CumulativeStats()

            val todayCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val todayStartMs = todayCal.timeInMillis
            val oneDayMs = 24L * 60L * 60L * 1000L

            var todayJumps = 0
            var best30s = 0
            var totalDoubleUnders = 0
            val activeDayBuckets = HashSet<Long>()

            for (s in sessions) {
                if (s.timestampMillis >= todayStartMs && s.timestampMillis < todayStartMs + oneDayMs) {
                    todayJumps += s.jumpCount
                }
                if (s.modeType == WorkoutModeType.IJRU_SPEED_30S || s.durationMs in 25_000L..35_000L) {
                    if (s.jumpCount > best30s) best30s = s.jumpCount
                }
                totalDoubleUnders += (s.moveBreakdown["Double Under"] ?: 0) +
                    (s.moveBreakdown["DOUBLE_UNDER"] ?: 0) +
                    (s.moveBreakdown["Triple Under"] ?: 0) * 2

                val c = Calendar.getInstance().apply {
                    timeInMillis = s.timestampMillis
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                activeDayBuckets.add(c.timeInMillis)
            }

            // Compute consecutive day streak (active today OR yesterday keeps streak alive)
            var dayStreak = 0
            val cursor = (todayCal.clone() as Calendar)
            if (!activeDayBuckets.contains(cursor.timeInMillis)) {
                cursor.add(Calendar.DAY_OF_YEAR, -1)
            }
            while (activeDayBuckets.contains(cursor.timeInMillis)) {
                dayStreak++
                cursor.add(Calendar.DAY_OF_YEAR, -1)
            }

            return CumulativeStats(
                totalSessions = sessions.size,
                totalJumps = sessions.sumOf { it.jumpCount },
                totalDurationMs = sessions.sumOf { it.durationMs },
                totalCalories = sessions.sumOf { it.estimatedCalories },
                allTimeBestStreak = sessions.maxOf { it.bestStreak },
                activeDayStreak = dayStreak,
                todayJumps = todayJumps,
                highestSingleSessionJumps = sessions.maxOf { it.jumpCount },
                fastestPeakRpm = sessions.maxOf { it.peakRpm },
                bestFormScore = sessions.maxOf { it.formScore },
                best30sSprintJumps = best30s,
                totalDoubleUnders = totalDoubleUnders
            )
        }
    }
}
