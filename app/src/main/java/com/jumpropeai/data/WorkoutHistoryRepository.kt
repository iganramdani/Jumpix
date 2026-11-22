package com.jumpropeai.data

import com.jumpropeai.workout.WorkoutModeType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Lightweight local persistence for workout sessions stored as JSON in app internal storage.
 */
class WorkoutHistoryRepository(private val storageFile: File) {

    fun loadSessions(): List<WorkoutSession> {
        if (!storageFile.exists()) return emptyList()
        return try {
            val content = storageFile.readText()
            if (content.isBlank()) return emptyList()
            val array = JSONArray(content)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val modeType = runCatching {
                        WorkoutModeType.valueOf(obj.optString("modeType", WorkoutModeType.FREE.name))
                    }.getOrDefault(WorkoutModeType.FREE)

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
                            bestStreak = obj.optInt("bestStreak", 0)
                        )
                    )
                }
            }.sortedByDescending { it.timestampMillis }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveSession(session: WorkoutSession): List<WorkoutSession> = upsertSession(session)

    fun upsertSession(session: WorkoutSession): List<WorkoutSession> {
        val existing = loadSessions().filterNot { it.id == session.id }
        val updated = (listOf(session) + existing)
            .sortedByDescending { it.timestampMillis }
            .take(100)
        writeSessions(updated)
        return updated
    }

    fun deleteSession(sessionId: String): List<WorkoutSession> {
        val updated = loadSessions().filterNot { it.id == sessionId }
        writeSessions(updated)
        return updated
    }

    fun clearAll(): List<WorkoutSession> {
        writeSessions(emptyList())
        return emptyList()
    }

    private fun writeSessions(sessions: List<WorkoutSession>) {
        try {
            storageFile.parentFile?.mkdirs()
            val array = JSONArray()
            for (s in sessions) {
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
                }
                array.put(obj)
            }
            storageFile.writeText(array.toString())
        } catch (_: Exception) {
            // Ignore write errors gracefully
        }
    }

    companion object {
        fun computeStats(sessions: List<WorkoutSession>): CumulativeStats {
            if (sessions.isEmpty()) return CumulativeStats()
            return CumulativeStats(
                totalSessions = sessions.size,
                totalJumps = sessions.sumOf { it.jumpCount },
                totalDurationMs = sessions.sumOf { it.durationMs },
                totalCalories = sessions.sumOf { it.estimatedCalories },
                allTimeBestStreak = sessions.maxOf { it.bestStreak }
            )
        }
    }
}
