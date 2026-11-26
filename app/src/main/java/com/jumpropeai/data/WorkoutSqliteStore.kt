package com.jumpropeai.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.jumpropeai.workout.WorkoutModeType
import org.json.JSONObject

/**
 * Jumpvex v5.0 — Zero-Bloat Native Android SQLite WAL Store (0 KB extra APK size!).
 *
 * Stores unlimited workout sessions with O(log N) indexed timestamp queries, WAL crash resilience,
 * plyometric lab metrics, camera rPPG recovery scores, and 365-day heatmap aggregations.
 */
class WorkoutSqliteStore(context: Context) : SQLiteOpenHelper(
    context,
    DB_NAME,
    null,
    DB_VERSION
) {
    companion object {
        private const val DB_NAME = "jumpvex_telemetry.db"
        private const val DB_VERSION = 1
        private const val TABLE_SESSIONS = "workout_sessions"
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SESSIONS (
                id TEXT PRIMARY KEY,
                timestamp_ms INTEGER NOT NULL,
                mode_label TEXT NOT NULL,
                mode_type TEXT NOT NULL,
                jump_count INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                average_rpm INTEGER NOT NULL,
                peak_rpm INTEGER NOT NULL,
                calories REAL NOT NULL,
                best_streak INTEGER NOT NULL,
                trips INTEGER NOT NULL DEFAULT 0,
                form_score INTEGER NOT NULL DEFAULT 0,
                beat_sync_score INTEGER NOT NULL DEFAULT 0,
                detected_bpm INTEGER NOT NULL DEFAULT 0,
                combo_count INTEGER NOT NULL DEFAULT 0,
                rope_type TEXT NOT NULL DEFAULT 'Speed Cable',
                moves_json TEXT NOT NULL DEFAULT '{}',
                avg_height_cm REAL NOT NULL DEFAULT 0,
                avg_gct_ms INTEGER NOT NULL DEFAULT 0,
                avg_rsi REAL NOT NULL DEFAULT 0,
                post_bpm INTEGER NOT NULL DEFAULT 0,
                hrr60_drop INTEGER NOT NULL DEFAULT 0,
                top_trip_cause TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sessions_ts ON $TABLE_SESSIONS(timestamp_ms DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun upsertSession(session: WorkoutSession) {
        val db = writableDatabase
        val movesJson = JSONObject().apply {
            for ((k, v) in session.moveBreakdown) {
                if (v > 0) put(k, v)
            }
        }.toString()
        val cv = ContentValues().apply {
            put("id", session.id)
            put("timestamp_ms", session.timestampMillis)
            put("mode_label", session.modeLabel)
            put("mode_type", session.modeType.name)
            put("jump_count", session.jumpCount)
            put("duration_ms", session.durationMs)
            put("average_rpm", session.averageRpm)
            put("peak_rpm", session.peakRpm)
            put("calories", session.estimatedCalories)
            put("best_streak", session.bestStreak)
            put("trips", session.trips)
            put("form_score", session.formScore)
            put("beat_sync_score", session.beatSyncScore)
            put("detected_bpm", session.detectedBpm)
            put("combo_count", session.comboCount)
            put("rope_type", session.ropeTypeLabel)
            put("moves_json", movesJson)
            put("avg_height_cm", session.avgJumpHeightCm)
            put("avg_gct_ms", session.avgGctMs)
            put("avg_rsi", session.avgRsi)
            put("post_bpm", session.postWorkoutBpm)
            put("hrr60_drop", session.hrr60DropBpm)
            put("top_trip_cause", session.topTripCause)
        }
        db.insertWithOnConflict(TABLE_SESSIONS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteSession(sessionId: String) {
        writableDatabase.delete(TABLE_SESSIONS, "id = ?", arrayOf(sessionId))
    }

    fun loadAllSessions(limit: Int = 5_000): List<WorkoutSession> {
        val out = ArrayList<WorkoutSession>()
        val db = readableDatabase
        db.rawQuery(
            "SELECT * FROM $TABLE_SESSIONS ORDER BY timestamp_ms DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val modeType = runCatching {
                    WorkoutModeType.valueOf(c.getString(c.getColumnIndexOrThrow("mode_type")))
                }.getOrDefault(WorkoutModeType.FREE)

                val movesMap = LinkedHashMap<String, Int>()
                runCatching {
                    val obj = JSONObject(c.getString(c.getColumnIndexOrThrow("moves_json")))
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = obj.optInt(k, 0)
                        if (v > 0) movesMap[k] = v
                    }
                }

                out.add(
                    WorkoutSession(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        timestampMillis = c.getLong(c.getColumnIndexOrThrow("timestamp_ms")),
                        modeLabel = c.getString(c.getColumnIndexOrThrow("mode_label")),
                        modeType = modeType,
                        jumpCount = c.getInt(c.getColumnIndexOrThrow("jump_count")),
                        durationMs = c.getLong(c.getColumnIndexOrThrow("duration_ms")),
                        averageRpm = c.getInt(c.getColumnIndexOrThrow("average_rpm")),
                        peakRpm = c.getInt(c.getColumnIndexOrThrow("peak_rpm")),
                        estimatedCalories = c.getDouble(c.getColumnIndexOrThrow("calories")),
                        bestStreak = c.getInt(c.getColumnIndexOrThrow("best_streak")),
                        trips = c.getInt(c.getColumnIndexOrThrow("trips")),
                        formScore = c.getInt(c.getColumnIndexOrThrow("form_score")),
                        beatSyncScore = c.getInt(c.getColumnIndexOrThrow("beat_sync_score")),
                        detectedBpm = c.getInt(c.getColumnIndexOrThrow("detected_bpm")),
                        comboCount = c.getInt(c.getColumnIndexOrThrow("combo_count")),
                        ropeTypeLabel = c.getString(c.getColumnIndexOrThrow("rope_type")),
                        moveBreakdown = movesMap,
                        avgJumpHeightCm = c.getFloat(c.getColumnIndexOrThrow("avg_height_cm")),
                        avgGctMs = c.getInt(c.getColumnIndexOrThrow("avg_gct_ms")),
                        avgRsi = c.getFloat(c.getColumnIndexOrThrow("avg_rsi")),
                        postWorkoutBpm = c.getInt(c.getColumnIndexOrThrow("post_bpm")),
                        hrr60DropBpm = c.getInt(c.getColumnIndexOrThrow("hrr60_drop")),
                        topTripCause = c.getString(c.getColumnIndexOrThrow("top_trip_cause"))
                    )
                )
            }
        }
        return out
    }

    /**
     * One-time transparent migration from legacy JSON history into WAL SQLite.
     */
    fun migrateIfEmpty(legacySessions: List<WorkoutSession>) {
        if (legacySessions.isEmpty()) return
        val db = readableDatabase
        val count = db.rawQuery("SELECT COUNT(*) FROM $TABLE_SESSIONS", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
        if (count > 0) return
        writableDatabase.beginTransaction()
        try {
            for (s in legacySessions) upsertSession(s)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }
}
