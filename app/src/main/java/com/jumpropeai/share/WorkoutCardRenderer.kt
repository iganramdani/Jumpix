package com.jumpropeai.share

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.jumpropeai.ai.KineticMove
import com.jumpropeai.data.WorkoutSession
import com.jumpropeai.video.VideoTelemetryEvent
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

enum class CardTheme(val label: String) {
    DARK_POSTER("Dark Poster"),
    TRANSPARENT_STICKER("Transparent Sticker"),
    PHOTO_BACKDROP("Photo Backdrop")
}

enum class WatermarkTemplate(val label: String) {
    MINIMAL_STRAVA("Strava"),
    NIKE_EDITORIAL("Editorial"),
    NEON_PULSE("Pulse HUD"),
    ACTIVITY_RING("Ring"),
    CORNER_STAMP("Stamp")
}

/**
 * Official Jumpvex Share Card & Dynamic Animated Video Telemetry Renderer (v4.0).
 *
 * Unified on the strict Two-Tone Jumpvex Brand System:
 * - Deep Midnight (`#0D1321`) + Ice Mint (`#2EE59D`) + Frost White (`#F8FAFC`)
 * - Official Golden Ratio (`φ = 1.618`) `JV` vector monogram
 * - Supports both static 1080x1350 workout posters AND real-time frame-accurate animated video HUDs!
 */
object WorkoutCardRenderer {

    private const val WIDTH = 1080
    private const val HEIGHT = 1350

    private val COLOR_ICE_MINT = Color.parseColor("#2EE59D")
    private val COLOR_SKY_MINT = Color.parseColor("#38BDF8")
    private val COLOR_DEEP_MIDNIGHT = Color.parseColor("#0D1321")
    private val COLOR_FROST_WHITE = Color.parseColor("#F8FAFC")
    private val COLOR_MUTED_SLATE = Color.parseColor("#94A3B8")

    fun renderCard(
        session: WorkoutSession,
        theme: CardTheme,
        template: WatermarkTemplate = WatermarkTemplate.MINIMAL_STRAVA,
        backdropBitmap: Bitmap? = null,
        styleLabel: String? = null,
        overlayScale: Float = 1.0f
    ): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        when (theme) {
            CardTheme.TRANSPARENT_STICKER -> canvas.drawColor(Color.TRANSPARENT)
            CardTheme.PHOTO_BACKDROP -> {
                if (backdropBitmap != null && !backdropBitmap.isRecycled) {
                    drawCenterCropBitmap(canvas, backdropBitmap, WIDTH, HEIGHT)
                    drawSubtleTemplateScrim(canvas, template)
                } else {
                    drawDarkPosterBackground(canvas)
                }
            }
            CardTheme.DARK_POSTER -> drawDarkPosterBackground(canvas)
        }

        drawScaledTemplateContent(
            canvas = canvas,
            template = template,
            overlayScale = overlayScale,
            jumpCount = session.jumpCount,
            rpm = session.averageRpm,
            peakRpm = session.peakRpm,
            durationMs = session.durationMs,
            calories = session.estimatedCalories,
            streak = session.bestStreak,
            timestampMillis = session.timestampMillis,
            badgeSubtitle = buildTrickOrStyleTag(session, styleLabel),
            jumpPopBoost = 0f,
            progressFraction = 1f
        )
        return bmp
    }

    /**
     * Renders a frame-accurate live animated telemetry overlay into a reusable [targetBitmap]
     * (`canvasW` x `canvasH`) at [presentationTimeMs] with zero Bitmap allocations!
     */
    fun renderDynamicTelemetryFrame(
        targetBitmap: Bitmap,
        session: WorkoutSession,
        events: List<VideoTelemetryEvent>,
        presentationTimeMs: Long,
        totalDurationMs: Long,
        template: WatermarkTemplate,
        overlayScale: Float
    ) {
        val canvasW = targetBitmap.width
        val canvasH = targetBitmap.height
        val canvas = Canvas(targetBitmap)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // O(log N) binary search for latest jump event at or before presentationTimeMs
        val idx = findLastEventAtOrBefore(events, presentationTimeMs)
        val latestEv = if (idx >= 0) events[idx] else null
        val liveJumps = latestEv?.jumpNumber ?: 0
        val sinceJumpMs = if (latestEv != null) (presentationTimeMs - latestEv.timestampMs) else 10_000L
        val liveRpm = when {
            latestEv == null -> 0
            sinceJumpMs > 3_000L -> 0
            else -> latestEv.currentRpm
        }
        val liveStreak = when {
            latestEv == null -> 0
            sinceJumpMs > 2_400L -> 0
            else -> latestEv.currentStreak
        }
        val liveCalories = latestEv?.calories ?: 0.0
        val liveMoveLabel = if (latestEv != null && sinceJumpMs <= 1_800L) {
            latestEv.move.label.uppercase(Locale.US)
        } else {
            "LIVE AI"
        }
        val popBoost = if (sinceJumpMs in 0L..180L) {
            1f - (sinceJumpMs / 180f)
        } else {
            0f
        }
        val progressFrac = if (totalDurationMs > 0L) {
            (presentationTimeMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

        // Map 1080x1350 virtual design coordinates cleanly into target video canvas with 3% safe margin
        val videoAspect = canvasW.toFloat() / canvasH.toFloat()
        val wmAspect = WIDTH.toFloat() / HEIGHT.toFloat()
        val safeW = canvasW * 0.94f
        val safeH = canvasH * 0.94f
        val fitScale = minOf(safeW / WIDTH.toFloat(), safeH / HEIGHT.toFloat())
        val drawW = WIDTH * fitScale
        val drawH = HEIGHT * fitScale
        val offsetLeft = (canvasW - drawW) / 2f
        val offsetTop = if (videoAspect < wmAspect) {
            (canvasH - drawH) * 0.56f
        } else {
            (canvasH - drawH) / 2f
        }

        canvas.save()
        canvas.translate(offsetLeft, offsetTop)
        canvas.scale(fitScale, fitScale)

        drawScaledTemplateContent(
            canvas = canvas,
            template = template,
            overlayScale = overlayScale,
            jumpCount = liveJumps,
            rpm = liveRpm,
            peakRpm = session.peakRpm,
            durationMs = presentationTimeMs.coerceAtMost(totalDurationMs),
            calories = liveCalories,
            streak = liveStreak,
            timestampMillis = session.timestampMillis,
            badgeSubtitle = liveMoveLabel,
            jumpPopBoost = popBoost,
            progressFraction = progressFrac
        )
        canvas.restore()
    }

    private fun findLastEventAtOrBefore(events: List<VideoTelemetryEvent>, timeMs: Long): Int {
        var lo = 0
        var hi = events.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (events[mid].timestampMs <= timeMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    private fun buildTrickOrStyleTag(session: WorkoutSession, styleLabel: String?): String {
        val topNonRegular = session.moveBreakdown.entries
            .filter { !it.key.equals("Regular", ignoreCase = true) && it.value > 0 }
            .maxByOrNull { it.value }
        return when {
            topNonRegular != null && session.formScore > 0 ->
                "${topNonRegular.key.uppercase(Locale.US)} • FORM ${session.formScore}%"
            session.formScore > 0 ->
                "FORM ${session.formScore}%"
            !styleLabel.isNullOrBlank() ->
                styleLabel.uppercase(Locale.US)
            else ->
                session.modeLabel.uppercase(Locale.US).take(22)
        }
    }

    private fun drawScaledTemplateContent(
        canvas: Canvas,
        template: WatermarkTemplate,
        overlayScale: Float,
        jumpCount: Int,
        rpm: Int,
        peakRpm: Int,
        durationMs: Long,
        calories: Double,
        streak: Int,
        timestampMillis: Long,
        badgeSubtitle: String,
        jumpPopBoost: Float,
        progressFraction: Float
    ) {
        val sliderClamped = overlayScale.coerceIn(0.60f, 1.30f)
        val safeScale = if (sliderClamped <= 1.0f) {
            0.56f + ((sliderClamped - 0.60f) / 0.40f) * 0.32f
        } else {
            0.88f + ((sliderClamped - 1.00f) / 0.30f) * 0.12f
        }
        val pivotY = when (template) {
            WatermarkTemplate.MINIMAL_STRAVA,
            WatermarkTemplate.ACTIVITY_RING,
            WatermarkTemplate.CORNER_STAMP -> HEIGHT * 0.78f
            else -> HEIGHT * 0.50f
        }
        val pivotX = if (template == WatermarkTemplate.CORNER_STAMP) WIDTH * 0.34f else WIDTH * 0.50f

        canvas.save()
        canvas.scale(safeScale, safeScale, pivotX, pivotY)

        when (template) {
            WatermarkTemplate.MINIMAL_STRAVA -> drawMinimalStrava(
                canvas, jumpCount, rpm, peakRpm, durationMs, calories, streak, timestampMillis, badgeSubtitle, jumpPopBoost
            )
            WatermarkTemplate.NIKE_EDITORIAL -> drawEditorialGlass(
                canvas, jumpCount, rpm, durationMs, calories, streak, badgeSubtitle, jumpPopBoost
            )
            WatermarkTemplate.NEON_PULSE -> drawCyberPulseHud(
                canvas, jumpCount, rpm, durationMs, calories, streak, badgeSubtitle, jumpPopBoost, progressFraction
            )
            WatermarkTemplate.ACTIVITY_RING -> drawActivityRing(
                canvas, jumpCount, rpm, durationMs, calories, streak, badgeSubtitle, jumpPopBoost, progressFraction
            )
            WatermarkTemplate.CORNER_STAMP -> drawCornerStamp(
                canvas, jumpCount, rpm, durationMs, calories, streak, badgeSubtitle, jumpPopBoost
            )
        }

        canvas.restore()
    }

    /**
     * Draws the official Golden Ratio (`φ = 1.618`) Jumpvex `JV` vector badge.
     */
    private fun drawJumpvexLogoBadge(canvas: Canvas, left: Float, top: Float, size: Float) {
        val s = size / 512f
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(s, s)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_DEEP_MIDNIGHT }
        val borderPaint = strokePaint(14f, Color.argb(190, 46, 229, 157))
        val rect = RectF(0f, 0f, 512f, 512f)
        canvas.drawRoundRect(rect, 112f, 112f, bgPaint)
        canvas.drawRoundRect(rect, 112f, 112f, borderPaint)

        val jPath = Path().apply {
            moveTo(236.25f, 143.21f)
            lineTo(263.63f, 204.42f)
            lineTo(163.97f, 427.26f)
            lineTo(91.64f, 265.54f)
            lineTo(146.39f, 265.54f)
            lineTo(163.97f, 304.85f)
            close()
        }
        val vPath = Path().apply {
            moveTo(244.83f, 115.62f)
            lineTo(299.57f, 115.62f)
            lineTo(325.69f, 174.02f)
            lineTo(365.61f, 84.74f)
            lineTo(420.36f, 84.74f)
            lineTo(325.69f, 296.43f)
            close()
        }
        canvas.drawPath(jPath, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_FROST_WHITE })
        canvas.drawPath(vPath, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_ICE_MINT })
        canvas.restore()
    }

    // --- TEMPLATE 1: Minimal Strava (Signature Ice Mint + Golden Ratio JV Header) ---
    private fun drawMinimalStrava(
        canvas: Canvas,
        jumpCount: Int,
        rpm: Int,
        peakRpm: Int,
        durationMs: Long,
        calories: Double,
        streak: Int,
        timestampMillis: Long,
        badgeSubtitle: String,
        jumpPopBoost: Float
    ) {
        val dateStr = SimpleDateFormat("dd MMM yyyy", Locale.US)
            .format(Date(timestampMillis))
            .uppercase(Locale.US)
        val rightTag = if (badgeSubtitle.isNotBlank()) "$dateStr • $badgeSubtitle" else dateStr

        drawJumpvexLogoBadge(canvas, 64f, 54f, 56f)
        canvas.drawText("JUMPVEX", 136f, 94f, textPaint(32f, COLOR_ICE_MINT, Typeface.BOLD, letterSpace = 0.08f))
        canvas.drawText(
            rightTag,
            WIDTH - 64f,
            94f,
            textPaint(24f, Color.argb(220, 248, 250, 252), Typeface.BOLD, Paint.Align.RIGHT)
        )

        canvas.drawText(
            "TOTAL JUMPS",
            64f,
            HEIGHT - 418f,
            textPaint(25f, Color.argb(205, 248, 250, 252), Typeface.BOLD, letterSpace = 0.14f)
        )

        val countText = jumpCount.toString()
        val heroSize = 196f + jumpPopBoost * 12f
        val heroColor = if (jumpPopBoost > 0.25f) COLOR_ICE_MINT else COLOR_FROST_WHITE
        val heroPaint = textPaint(heroSize, heroColor, Typeface.BOLD)
        canvas.drawText(countText, 60f, HEIGHT - 236f, heroPaint)

        val countW = heroPaint.measureText(countText)
        val pillLeft = (64f + countW + 34f).coerceAtMost(WIDTH - 296f)
        val pillRect = RectF(pillLeft, HEIGHT - 304f, pillLeft + 242f, HEIGHT - 238f)
        canvas.drawRoundRect(pillRect, 33f, 33f, glassFillPaint(135, 13, 19, 33))
        canvas.drawRoundRect(pillRect, 33f, 33f, strokePaint(3f, COLOR_ICE_MINT))
        canvas.drawText(
            "$rpm RPM",
            pillRect.centerX(),
            pillRect.centerY() + 11f,
            textPaint(30f, COLOR_ICE_MINT, Typeface.BOLD, Paint.Align.CENTER, shadow = false)
        )

        val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(85, 46, 229, 157)
            strokeWidth = 2.5f
        }
        canvas.drawLine(64f, HEIGHT - 182f, WIDTH - 64f, HEIGHT - 182f, divPaint)

        val cols = listOf(
            formatDuration(durationMs) to "DURATION",
            String.format(Locale.US, "%.1f", calories) to "KCAL",
            "$peakRpm" to "PEAK RPM",
            "$streak" to "STREAK"
        )
        val colW = (WIDTH - 128f) / 4f
        cols.forEachIndexed { idx, (v, l) ->
            val x = 64f + colW * idx
            canvas.drawText(v, x, HEIGHT - 114f, textPaint(48f, COLOR_FROST_WHITE, Typeface.BOLD))
            canvas.drawText(l, x, HEIGHT - 72f, textPaint(21f, COLOR_MUTED_SLATE, Typeface.BOLD, letterSpace = 0.08f))
        }
    }

    // --- TEMPLATE 2: Editorial Glass (Bold Athletic Split + Frosted Deep Midnight Card) ---
    private fun drawEditorialGlass(
        canvas: Canvas,
        jumpCount: Int,
        rpm: Int,
        durationMs: Long,
        calories: Double,
        streak: Int,
        badgeSubtitle: String,
        jumpPopBoost: Float
    ) {
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_ICE_MINT }
        canvas.drawRoundRect(RectF(56f, 68f, 72f, 342f), 8f, 8f, barPaint)

        val heroSize = 192f + jumpPopBoost * 12f
        canvas.drawText(
            jumpCount.toString(),
            100f,
            234f,
            textPaint(heroSize, COLOR_FROST_WHITE, Typeface.BOLD_ITALIC)
        )
        canvas.drawText(
            "JUMPS  /  $rpm RPM",
            104f,
            290f,
            textPaint(36f, COLOR_ICE_MINT, Typeface.BOLD_ITALIC)
        )
        canvas.drawText(
            "JUMPVEX • $badgeSubtitle",
            104f,
            334f,
            textPaint(22f, Color.argb(215, 248, 250, 252), Typeface.BOLD, letterSpace = 0.10f)
        )

        val box = RectF(WIDTH - 520f, HEIGHT - 274f, WIDTH - 54f, HEIGHT - 58f)
        canvas.drawRoundRect(box, 32f, 32f, glassFillPaint(145, 13, 19, 33))
        canvas.drawRoundRect(box, 32f, 32f, strokePaint(3f, Color.argb(200, 46, 229, 157)))

        val rows = listOf(
            "DURATION" to formatDuration(durationMs),
            "CALORIES" to "${String.format(Locale.US, "%.1f", calories)} kcal",
            "STREAK" to "$streak"
        )
        rows.forEachIndexed { i, (k, v) ->
            val y = box.top + 64f + i * 62f
            canvas.drawText(k, box.left + 34f, y, textPaint(24f, COLOR_MUTED_SLATE, Typeface.BOLD))
            canvas.drawText(v, box.right - 34f, y, textPaint(32f, COLOR_FROST_WHITE, Typeface.BOLD, Paint.Align.RIGHT))
        }
    }

    // --- TEMPLATE 3: Cyber Pulse HUD (Viewfinder + Animated Acoustic Waveform) ---
    private fun drawCyberPulseHud(
        canvas: Canvas,
        jumpCount: Int,
        rpm: Int,
        durationMs: Long,
        calories: Double,
        streak: Int,
        badgeSubtitle: String,
        jumpPopBoost: Float,
        progressFraction: Float
    ) {
        val brPaint = strokePaint(6f, Color.argb(215, 46, 229, 157))
        val len = 48f
        val m = 44f
        val corners = listOf(
            floatArrayOf(m, m, m + len, m, m, m + len),
            floatArrayOf(WIDTH - m, m, WIDTH - m - len, m, WIDTH - m, m + len),
            floatArrayOf(m, HEIGHT - m, m + len, HEIGHT - m, m, HEIGHT - m - len),
            floatArrayOf(WIDTH - m, HEIGHT - m, WIDTH - m - len, HEIGHT - m, WIDTH - m, HEIGHT - m - len)
        )
        for (c in corners) {
            canvas.drawLine(c[0], c[1], c[2], c[3], brPaint)
            canvas.drawLine(c[0], c[1], c[4], c[5], brPaint)
        }

        val topPill = RectF(WIDTH / 2f - 280f, 56f, WIDTH / 2f + 280f, 118f)
        canvas.drawRoundRect(topPill, 31f, 31f, glassFillPaint(150, 13, 19, 33))
        canvas.drawRoundRect(topPill, 31f, 31f, strokePaint(2.5f, Color.argb(200, 46, 229, 157)))
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_ICE_MINT }
        canvas.drawCircle(topPill.left + 36f, topPill.centerY(), 9f + jumpPopBoost * 4f, dotPaint)
        canvas.drawText(
            "$badgeSubtitle • $rpm RPM",
            topPill.centerX() + 14f,
            topPill.centerY() + 9f,
            textPaint(24f, COLOR_FROST_WHITE, Typeface.BOLD, Paint.Align.CENTER, mono = true, shadow = false)
        )

        val wavePath = Path()
        val startX = 64f
        val spanW = WIDTH - 128f
        val baseY = HEIGHT - 368f
        val phaseShift = progressFraction * 24.0
        val ampBoost = 1f + jumpPopBoost * 0.65f
        for (i in 0..120) {
            val t = i / 120f
            val env = sin(PI * t).toFloat()
            val yOff = (sin(t * 28.0 + phaseShift) * 32.0 + sin(t * 63.0 - phaseShift) * 14.0).toFloat() * env * ampBoost
            val px = startX + spanW * t
            val py = baseY + yOff
            if (i == 0) wavePath.moveTo(px, py) else wavePath.lineTo(px, py)
        }
        canvas.drawPath(wavePath, strokePaint(14f, Color.argb(65, 46, 229, 157)))
        canvas.drawPath(wavePath, strokePaint(5f, COLOR_ICE_MINT))

        canvas.drawText(
            jumpCount.toString(),
            WIDTH / 2f,
            HEIGHT - 196f,
            textPaint(164f + jumpPopBoost * 12f, COLOR_ICE_MINT, Typeface.BOLD, Paint.Align.CENTER, mono = true)
        )
        canvas.drawText(
            "${formatDuration(durationMs)}   |   ${String.format(Locale.US, "%.1f", calories)} KCAL   |   STREAK $streak",
            WIDTH / 2f,
            HEIGHT - 126f,
            textPaint(28f, COLOR_FROST_WHITE, Typeface.BOLD, Paint.Align.CENTER, mono = true)
        )
        canvas.drawText(
            "JUMPVEX AI ENGINE",
            WIDTH / 2f,
            HEIGHT - 78f,
            textPaint(21f, COLOR_MUTED_SLATE, Typeface.BOLD, Paint.Align.CENTER, mono = true, letterSpace = 0.18f)
        )
    }

    // --- TEMPLATE 4: Activity Ring (Dual Concentric Ice Mint + Sky Mint Rings) ---
    private fun drawActivityRing(
        canvas: Canvas,
        jumpCount: Int,
        rpm: Int,
        durationMs: Long,
        calories: Double,
        streak: Int,
        badgeSubtitle: String,
        jumpPopBoost: Float,
        progressFraction: Float
    ) {
        val panel = RectF(48f, HEIGHT - 420f, WIDTH - 48f, HEIGHT - 52f)
        canvas.drawRoundRect(panel, 54f, 54f, glassFillPaint(150, 13, 19, 33))
        canvas.drawRoundRect(panel, 54f, 54f, strokePaint(2.5f, Color.argb(140, 46, 229, 157)))

        val cx = panel.left + 196f
        val cy = panel.centerY()
        val rOuter = 122f + jumpPopBoost * 5f
        val rInner = 90f

        val outerRect = RectF(cx - rOuter, cy - rOuter, cx + rOuter, cy + rOuter)
        val innerRect = RectF(cx - rInner, cy - rInner, cx + rInner, cy + rInner)

        canvas.drawArc(outerRect, 0f, 360f, false, strokePaint(22f, Color.argb(50, 46, 229, 157)))
        val outerSweep = if (progressFraction < 0.999f) {
            (progressFraction * 340f).coerceAtLeast(if (jumpCount > 0) 24f else 0f)
        } else {
            (jumpCount.coerceIn(10, 200) / 200f) * 330f
        }
        if (outerSweep > 0f) {
            canvas.drawArc(outerRect, -90f, outerSweep, false, strokePaint(22f, COLOR_ICE_MINT))
        }

        canvas.drawArc(innerRect, 0f, 360f, false, strokePaint(16f, Color.argb(45, 56, 189, 248)))
        val innerSweep = (rpm.coerceIn(0, 190) / 190f) * 310f
        if (innerSweep > 4f) {
            canvas.drawArc(innerRect, -90f, innerSweep, false, strokePaint(16f, COLOR_SKY_MINT))
        }

        canvas.drawText(
            jumpCount.toString(),
            cx,
            cy + 14f,
            textPaint(66f, COLOR_FROST_WHITE, Typeface.BOLD, Paint.Align.CENTER)
        )
        canvas.drawText(
            "JUMPS",
            cx,
            cy + 46f,
            textPaint(18f, COLOR_ICE_MINT, Typeface.BOLD, Paint.Align.CENTER, letterSpace = 0.1f)
        )

        val rx = panel.left + 382f
        canvas.drawText(
            "JUMPVEX • $badgeSubtitle",
            rx,
            panel.top + 68f,
            textPaint(21f, COLOR_ICE_MINT, Typeface.BOLD, letterSpace = 0.08f)
        )

        val rows = listOf(
            Triple("DURATION", formatDuration(durationMs), COLOR_FROST_WHITE),
            Triple("PACE", "$rpm RPM", COLOR_SKY_MINT),
            Triple("CALORIES", "${String.format(Locale.US, "%.1f", calories)} kcal", COLOR_FROST_WHITE),
            Triple("STREAK", "$streak Best", COLOR_ICE_MINT)
        )
        rows.forEachIndexed { idx, (k, v, col) ->
            val y = panel.top + 132f + idx * 64f
            canvas.drawText(k, rx, y, textPaint(24f, COLOR_MUTED_SLATE, Typeface.BOLD))
            canvas.drawText(v, panel.right - 44f, y, textPaint(32f, col, Typeface.BOLD, Paint.Align.RIGHT))
        }
    }

    // --- TEMPLATE 5: Corner Stamp (Compact Ice Mint Badge Pill) ---
    private fun drawCornerStamp(
        canvas: Canvas,
        jumpCount: Int,
        rpm: Int,
        durationMs: Long,
        calories: Double,
        streak: Int,
        badgeSubtitle: String,
        jumpPopBoost: Float
    ) {
        val pill = RectF(48f, HEIGHT - 254f, 780f, HEIGHT - 52f)
        canvas.drawRoundRect(pill, 44f, 44f, glassFillPaint(155, 13, 19, 33))
        canvas.drawRoundRect(pill, 44f, 44f, strokePaint(2.5f, Color.argb(165, 46, 229, 157)))

        val badge = RectF(pill.left + 22f, pill.top + 22f, pill.left + 232f, pill.bottom - 22f)
        val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_ICE_MINT }
        canvas.drawRoundRect(badge, 32f, 32f, badgeFill)
        canvas.drawText(
            jumpCount.toString(),
            badge.centerX(),
            badge.centerY() + 14f,
            textPaint(72f + jumpPopBoost * 6f, COLOR_DEEP_MIDNIGHT, Typeface.BOLD, Paint.Align.CENTER, shadow = false)
        )
        canvas.drawText(
            "JUMPS",
            badge.centerX(),
            badge.bottom - 22f,
            textPaint(19f, COLOR_DEEP_MIDNIGHT, Typeface.BOLD, Paint.Align.CENTER, shadow = false)
        )

        val tx = badge.right + 32f
        canvas.drawText(
            "JUMPVEX • $badgeSubtitle",
            tx,
            pill.top + 62f,
            textPaint(21f, COLOR_ICE_MINT, Typeface.BOLD, letterSpace = 0.08f)
        )
        canvas.drawText(
            "${formatDuration(durationMs)}  •  $rpm RPM",
            tx,
            pill.top + 114f,
            textPaint(34f, COLOR_FROST_WHITE, Typeface.BOLD)
        )
        canvas.drawText(
            "${String.format(Locale.US, "%.1f", calories)} kcal  •  Streak $streak",
            tx,
            pill.top + 162f,
            textPaint(25f, COLOR_MUTED_SLATE, Typeface.BOLD)
        )
    }

    fun saveToGallery(context: Context, bitmap: Bitmap, baseName: String): Boolean {
        val fileName = "${baseName}_${System.currentTimeMillis()}.png"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Jumpvex")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                } ?: return false
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "Jumpvex"
                )
                dir.mkdirs()
                val file = File(dir, fileName)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun shareBitmap(context: Context, bitmap: Bitmap, baseName: String) {
        try {
            val shareDir = File(context.cacheDir, "shared_cards").apply { mkdirs() }
            val file = File(shareDir, "${baseName}_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "Jump rope workout tracked with Jumpvex AI!")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Workout Card"))
        } catch (_: Exception) {
            // Ignore share errors gracefully
        }
    }

    private fun drawDarkPosterBackground(canvas: Canvas) {
        val bgPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(),
                intArrayOf(
                    Color.parseColor("#0D1321"),
                    Color.parseColor("#151D2E"),
                    Color.parseColor("#090E18")
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), bgPaint)
    }

    private fun drawSubtleTemplateScrim(canvas: Canvas, template: WatermarkTemplate) {
        if (template == WatermarkTemplate.CORNER_STAMP) return
        val bottomScrim = Paint().apply {
            shader = LinearGradient(
                0f, HEIGHT * 0.48f, 0f, HEIGHT.toFloat(),
                intArrayOf(Color.TRANSPARENT, Color.argb(205, 13, 19, 33)),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, HEIGHT * 0.48f, WIDTH.toFloat(), HEIGHT.toFloat(), bottomScrim)
        if (template == WatermarkTemplate.NIKE_EDITORIAL) {
            val topScrim = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, HEIGHT * 0.34f,
                    intArrayOf(Color.argb(155, 13, 19, 33), Color.TRANSPARENT),
                    null,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT * 0.34f, topScrim)
        }
    }

    private fun textPaint(
        size: Float,
        color: Int,
        style: Int,
        align: Paint.Align = Paint.Align.LEFT,
        mono: Boolean = false,
        letterSpace: Float = 0f,
        shadow: Boolean = true
    ): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        textAlign = align
        letterSpacing = letterSpace
        typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.DEFAULT, style)
        if (shadow) setShadowLayer(12f, 0f, 4f, Color.argb(185, 0, 0, 0))
    }

    private fun glassFillPaint(alpha: Int, r: Int, g: Int, b: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(alpha, r, g, b)
        }

    private fun strokePaint(width: Float, color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
            this.color = color
        }

    private fun drawCenterCropBitmap(canvas: Canvas, src: Bitmap, dstW: Int, dstH: Int) {
        val scale = maxOf(dstW.toFloat() / src.width, dstH.toFloat() / src.height)
        val scaledW = src.width * scale
        val scaledH = src.height * scale
        val left = (dstW - scaledW) / 2f
        val top = (dstH - scaledH) / 2f
        canvas.drawBitmap(src, null, RectF(left, top, left + scaledW, top + scaledH), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val min = totalSec / 60L
        val sec = totalSec % 60L
        return String.format(Locale.US, "%02d:%02d", min, sec)
    }
}
