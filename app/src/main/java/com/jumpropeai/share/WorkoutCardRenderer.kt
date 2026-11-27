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

internal data class HudPlacement(
    val fitScale: Float,
    val offsetLeft: Float,
    val offsetTop: Float,
    val drawW: Float,
    val drawH: Float
)

internal fun computeProportionalHudPlacement(
    canvasW: Int,
    canvasH: Int,
    template: WatermarkTemplate,
    virtualWidth: Float = 1080f,
    virtualHeight: Float = 1350f
): HudPlacement {
    val w = canvasW.coerceAtLeast(1).toFloat()
    val h = canvasH.coerceAtLeast(1).toFloat()
    val videoAspect = w / h
    val isLandscape = videoAspect > 1.15f
    val isTallPortrait = videoAspect < 0.72f

    val marginFrac = if (isLandscape) 0.04f else 0.035f
    val safeW = w * (1f - 2f * marginFrac)
    val safeH = h * (1f - 2f * marginFrac)
    val baseFit = minOf(safeW / virtualWidth, safeH / virtualHeight)
    val fitScale = when {
        isLandscape && template == WatermarkTemplate.CORNER_STAMP -> baseFit * 0.94f
        isLandscape -> baseFit * 0.90f
        else -> baseFit
    }
    val drawW = virtualWidth * fitScale
    val drawH = virtualHeight * fitScale

    val offsetLeft = when {
        template == WatermarkTemplate.CORNER_STAMP -> w * marginFrac
        else -> (w - drawW) / 2f
    }
    val isBottomAnchored = template == WatermarkTemplate.MINIMAL_STRAVA ||
        template == WatermarkTemplate.ACTIVITY_RING ||
        template == WatermarkTemplate.CORNER_STAMP
    val offsetTop = when {
        isBottomAnchored && isTallPortrait -> (h - drawH - h * 0.045f).coerceAtLeast(h * marginFrac)
        isBottomAnchored -> (h - drawH - h * marginFrac).coerceAtLeast(h * marginFrac)
        isTallPortrait -> (h - drawH) * 0.62f
        else -> (h - drawH) / 2f
    }
    return HudPlacement(fitScale, offsetLeft, offsetTop, drawW, drawH)
}

internal data class EdgeAnchoredHudLayout(
    val unitScale: Float,
    val marginX: Float,
    val marginTop: Float,
    val marginBottom: Float,
    val logoSize: Float,
    val bottomAnchorY: Float
)

/**
 * Resolution-independent edge-anchored HUD layout for dynamic video exports across EVERY aspect ratio
 * (9:16, 9:20, 4:5, 1:1, 16:9) and resolution (720p, 1080p, 4K):
 * - Pins the larger Golden-Ratio `JV` logo + `JUMPVEX` header strictly to the TOP-LEFT corner.
 * - Anchors jump counts & telemetry cleanly near the BOTTOM edge so the athlete stays unobstructed.
 */
internal fun computeEdgeAnchoredHudLayout(
    canvasW: Int,
    canvasH: Int,
    overlayScale: Float = 1.0f
): EdgeAnchoredHudLayout {
    val w = canvasW.coerceAtLeast(1).toFloat()
    val h = canvasH.coerceAtLeast(1).toFloat()
    val shortSide = minOf(w, h)
    val sliderClamped = overlayScale.coerceIn(0.60f, 1.30f)
    val userMult = 0.78f + ((sliderClamped - 0.60f) / 0.70f) * 0.34f // 0.78x .. 1.12x (1.0 -> ~0.97x)
    val unitScale = (shortSide / 1080f) * userMult
    val marginX = (w * 0.044f).coerceAtLeast(24f * unitScale)
    val marginTop = (h * 0.038f).coerceAtLeast(24f * unitScale)
    val marginBottom = (h * 0.034f).coerceAtLeast(24f * unitScale)
    val logoSize = 88f * unitScale
    val bottomAnchorY = h - marginBottom
    return EdgeAnchoredHudLayout(
        unitScale = unitScale,
        marginX = marginX,
        marginTop = marginTop,
        marginBottom = marginBottom,
        logoSize = logoSize,
        bottomAnchorY = bottomAnchorY
    )
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

        // True edge-anchored HUD across EVERY resolution & aspect ratio (9:16, 9:20, 4:5, 1:1, 16:9, 4K):
        // Larger JV logo pinned strictly to TOP-LEFT; jump counters & stats anchored slightly lower at BOTTOM.
        drawEdgeAnchoredDynamicHud(
            canvas = canvas,
            canvasW = canvasW,
            canvasH = canvasH,
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
    }

    private fun drawTopLeftBrandHeader(
        canvas: Canvas,
        canvasW: Float,
        layout: EdgeAnchoredHudLayout,
        rightTag: String?
    ) {
        val s = layout.unitScale
        val logoSize = layout.logoSize
        val left = layout.marginX
        val top = layout.marginTop

        drawJumpvexLogoBadge(canvas, left, top, logoSize)
        val wordX = left + logoSize + 20f * s
        val baselineY = top + logoSize * 0.66f
        canvas.drawText(
            "JUMPVEX",
            wordX,
            baselineY,
            textPaint(40f * s, COLOR_ICE_MINT, Typeface.BOLD, letterSpace = 0.08f)
        )

        if (!rightTag.isNullOrBlank()) {
            canvas.drawText(
                rightTag,
                canvasW - layout.marginX,
                baselineY - 2f * s,
                textPaint(24f * s, Color.argb(225, 248, 250, 252), Typeface.BOLD, Paint.Align.RIGHT)
            )
        }
    }

    private fun drawEdgeAnchoredDynamicHud(
        canvas: Canvas,
        canvasW: Int,
        canvasH: Int,
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
        val w = canvasW.toFloat()
        val h = canvasH.toFloat()
        val layout = computeEdgeAnchoredHudLayout(canvasW, canvasH, overlayScale)
        val s = layout.unitScale
        val mX = layout.marginX
        val mTop = layout.marginTop
        val bottomY = layout.bottomAnchorY

        val dateStr = SimpleDateFormat("dd MMM yyyy", Locale.US)
            .format(Date(timestampMillis))
            .uppercase(Locale.US)
        val rightTag = if (badgeSubtitle.isNotBlank()) "$dateStr • $badgeSubtitle" else dateStr

        when (template) {
            WatermarkTemplate.MINIMAL_STRAVA -> {
                // Larger JV logo strictly in TOP-LEFT corner on every video resolution
                drawTopLeftBrandHeader(canvas, w, layout, rightTag)

                // Lowered bottom-anchored telemetry block (numbers sit cleanly near bottom edge)
                val labelRowY = bottomY
                val valueRowY = bottomY - 38f * s
                val divY = bottomY - 96f * s
                val heroBaseY = bottomY - 128f * s
                val totalLabelY = heroBaseY - 168f * s

                canvas.drawText(
                    "TOTAL JUMPS",
                    mX,
                    totalLabelY,
                    textPaint(24f * s, Color.argb(210, 248, 250, 252), Typeface.BOLD, letterSpace = 0.14f)
                )

                val countText = jumpCount.toString()
                val heroSize = (186f + jumpPopBoost * 12f) * s
                val heroColor = if (jumpPopBoost > 0.25f) COLOR_ICE_MINT else COLOR_FROST_WHITE
                val heroPaint = textPaint(heroSize, heroColor, Typeface.BOLD)
                canvas.drawText(countText, mX - 4f * s, heroBaseY, heroPaint)

                val countW = heroPaint.measureText(countText)
                val pillW = 236f * s
                val pillH = 62f * s
                val pillLeft = (mX + countW + 28f * s).coerceAtMost(w - mX - pillW)
                val pillRect = RectF(pillLeft, heroBaseY - pillH - 8f * s, pillLeft + pillW, heroBaseY - 8f * s)
                canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, glassFillPaint(140, 13, 19, 33))
                canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, strokePaint(3f * s, COLOR_ICE_MINT))
                canvas.drawText(
                    "$rpm RPM",
                    pillRect.centerX(),
                    pillRect.centerY() + 10f * s,
                    textPaint(29f * s, COLOR_ICE_MINT, Typeface.BOLD, Paint.Align.CENTER, shadow = false)
                )

                val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(90, 46, 229, 157)
                    strokeWidth = (2.5f * s).coerceAtLeast(1.5f)
                }
                canvas.drawLine(mX, divY, w - mX, divY, divPaint)

                val cols = listOf(
                    formatDuration(durationMs) to "DURATION",
                    String.format(Locale.US, "%.1f", calories) to "KCAL",
                    "$peakRpm" to "PEAK RPM",
                    "$streak" to "STREAK"
                )
                val colW = (w - 2f * mX) / 4f
                cols.forEachIndexed { idx, (v, l) ->
                    val x = mX + colW * idx
                    canvas.drawText(v, x, valueRowY, textPaint(44f * s, COLOR_FROST_WHITE, Typeface.BOLD))
                    canvas.drawText(l, x, labelRowY, textPaint(20f * s, COLOR_MUTED_SLATE, Typeface.BOLD, letterSpace = 0.08f))
                }
            }

            WatermarkTemplate.NEON_PULSE -> {
                // Viewfinder brackets hugging the ACTUAL video corners
                val brPaint = strokePaint((5.5f * s).coerceAtLeast(2.5f), Color.argb(215, 46, 229, 157))
                val len = 46f * s
                val bM = (minOf(w, h) * 0.028f).coerceAtLeast(16f * s)
                val corners = listOf(
                    floatArrayOf(bM, bM, bM + len, bM, bM, bM + len),
                    floatArrayOf(w - bM, bM, w - bM - len, bM, w - bM, bM + len),
                    floatArrayOf(bM, h - bM, bM + len, h - bM, bM, h - bM - len),
                    floatArrayOf(w - bM, h - bM, w - bM - len, h - bM, w - bM, h - bM - len)
                )
                for (c in corners) {
                    canvas.drawLine(c[0], c[1], c[2], c[3], brPaint)
                    canvas.drawLine(c[0], c[1], c[4], c[5], brPaint)
                }

                // Larger JV logo strictly in TOP-LEFT corner
                drawTopLeftBrandHeader(canvas, w, layout, rightTag = null)

                // Compact live status pill aligned cleanly at TOP-RIGHT
                val pillText = "$badgeSubtitle • $rpm RPM"
                val pillPaint = textPaint(22f * s, COLOR_FROST_WHITE, Typeface.BOLD, Paint.Align.CENTER, mono = true, shadow = false)
                val pillW = (pillPaint.measureText(pillText) + 84f * s).coerceAtMost(w * 0.52f)
                val pillH = 56f * s
                val pillTop = mTop + (layout.logoSize - pillH) / 2f
                val topPill = RectF(w - mX - pillW, pillTop, w - mX, pillTop + pillH)
                canvas.drawRoundRect(topPill, pillH / 2f, pillH / 2f, glassFillPaint(155, 13, 19, 33))
                canvas.drawRoundRect(topPill, pillH / 2f, pillH / 2f, strokePaint(2.5f * s, Color.argb(200, 46, 229, 157)))
                canvas.drawCircle(
                    topPill.left + 28f * s,
                    topPill.centerY(),
                    (8f + jumpPopBoost * 3.5f) * s,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_ICE_MINT }
                )
                canvas.drawText(pillText, topPill.centerX() + 12f * s, topPill.centerY() + 8f * s, pillPaint)

                // Lowered waveform + hero jump count + stats near bottom edge
                val footerY = bottomY - 6f * s
                val statsY = footerY - 38f * s
                val heroY = statsY - 56f * s
                val waveBaseY = heroY - 148f * s

                val wavePath = Path()
                val spanW = w - 2f * mX
                val phaseShift = progressFraction * 24.0
                val ampBoost = 1f + jumpPopBoost * 0.65f
                for (i in 0..120) {
                    val t = i / 120f
                    val env = sin(PI * t).toFloat()
                    val yOff = (sin(t * 28.0 + phaseShift) * 28.0 + sin(t * 63.0 - phaseShift) * 12.0).toFloat() * env * ampBoost * s
                    val px = mX + spanW * t
                    val py = waveBaseY + yOff
                    if (i == 0) wavePath.moveTo(px, py) else wavePath.lineTo(px, py)
                }
                canvas.drawPath(wavePath, strokePaint(12f * s, Color.argb(65, 46, 229, 157)))
                canvas.drawPath(wavePath, strokePaint(4.5f * s, COLOR_ICE_MINT))

                canvas.drawText(
                    jumpCount.toString(),
                    w / 2f,
                    heroY,
                    textPaint((154f + jumpPopBoost * 12f) * s, COLOR_ICE_MINT, Typeface.BOLD, Paint.Align.CENTER, mono = true)
                )
                canvas.drawText(
                    "${formatDuration(durationMs)}   |   ${String.format(Locale.US, "%.1f", calories)} KCAL   |   STREAK $streak",
                    w / 2f,
                    statsY,
                    textPaint(26f * s, COLOR_FROST_WHITE, Typeface.BOLD, Paint.Align.CENTER, mono = true)
                )
                canvas.drawText(
                    "JUMPVEX AI ENGINE",
                    w / 2f,
                    footerY,
                    textPaint(19f * s, COLOR_MUTED_SLATE, Typeface.BOLD, Paint.Align.CENTER, mono = true, letterSpace = 0.18f)
                )
            }

            WatermarkTemplate.NIKE_EDITORIAL -> {
                drawTopLeftBrandHeader(canvas, w, layout, rightTag)

                val boxW = (440f * s).coerceAtMost(w * 0.48f)
                val boxH = 204f * s
                val box = RectF(w - mX - boxW, bottomY - boxH, w - mX, bottomY)
                canvas.drawRoundRect(box, 28f * s, 28f * s, glassFillPaint(150, 13, 19, 33))
                canvas.drawRoundRect(box, 28f * s, 28f * s, strokePaint(2.8f * s, Color.argb(200, 46, 229, 157)))

                val rows = listOf(
                    "DURATION" to formatDuration(durationMs),
                    "CALORIES" to "${String.format(Locale.US, "%.1f", calories)} kcal",
                    "STREAK" to "$streak"
                )
                rows.forEachIndexed { i, (k, v) ->
                    val y = box.top + (56f + i * 56f) * s
                    canvas.drawText(k, box.left + 28f * s, y, textPaint(22f * s, COLOR_MUTED_SLATE, Typeface.BOLD))
                    canvas.drawText(v, box.right - 28f * s, y, textPaint(30f * s, COLOR_FROST_WHITE, Typeface.BOLD, Paint.Align.RIGHT))
                }

                // Bottom-left bold italic jump hero with Ice Mint accent bar
                val barTop = bottomY - 224f * s
                canvas.drawRoundRect(RectF(mX, barTop, mX + 14f * s, bottomY), 7f * s, 7f * s, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_ICE_MINT })
                canvas.drawText(
                    jumpCount.toString(),
                    mX + 34f * s,
                    bottomY - 62f * s,
                    textPaint((168f + jumpPopBoost * 12f) * s, COLOR_FROST_WHITE, Typeface.BOLD_ITALIC)
                )
                canvas.drawText(
                    "JUMPS  /  $rpm RPM",
                    mX + 38f * s,
                    bottomY - 12f * s,
                    textPaint(32f * s, COLOR_ICE_MINT, Typeface.BOLD_ITALIC)
                )
            }

            WatermarkTemplate.ACTIVITY_RING -> {
                drawTopLeftBrandHeader(canvas, w, layout, rightTag)

                val panelH = 336f * s
                val panelW = (w - 2f * mX).coerceAtMost(960f * s)
                val panelLeft = (w - panelW) / 2f
                val panel = RectF(panelLeft, bottomY - panelH, panelLeft + panelW, bottomY)
                canvas.drawRoundRect(panel, 46f * s, 46f * s, glassFillPaint(155, 13, 19, 33))
                canvas.drawRoundRect(panel, 46f * s, 46f * s, strokePaint(2.5f * s, Color.argb(145, 46, 229, 157)))

                val cx = panel.left + 172f * s
                val cy = panel.centerY()
                val rOuter = (110f + jumpPopBoost * 5f) * s
                val rInner = 80f * s
                val outerRect = RectF(cx - rOuter, cy - rOuter, cx + rOuter, cy + rOuter)
                val innerRect = RectF(cx - rInner, cy - rInner, cx + rInner, cy + rInner)

                canvas.drawArc(outerRect, 0f, 360f, false, strokePaint(20f * s, Color.argb(50, 46, 229, 157)))
                val outerSweep = if (progressFraction < 0.999f) {
                    (progressFraction * 340f).coerceAtLeast(if (jumpCount > 0) 24f else 0f)
                } else {
                    (jumpCount.coerceIn(10, 200) / 200f) * 330f
                }
                if (outerSweep > 0f) {
                    canvas.drawArc(outerRect, -90f, outerSweep, false, strokePaint(20f * s, COLOR_ICE_MINT))
                }
                canvas.drawArc(innerRect, 0f, 360f, false, strokePaint(14f * s, Color.argb(45, 56, 189, 248)))
                val innerSweep = (rpm.coerceIn(0, 190) / 190f) * 310f
                if (innerSweep > 4f) {
                    canvas.drawArc(innerRect, -90f, innerSweep, false, strokePaint(14f * s, COLOR_SKY_MINT))
                }

                canvas.drawText(jumpCount.toString(), cx, cy + 14f * s, textPaint(62f * s, COLOR_FROST_WHITE, Typeface.BOLD, Paint.Align.CENTER))
                canvas.drawText("JUMPS", cx, cy + 44f * s, textPaint(17f * s, COLOR_ICE_MINT, Typeface.BOLD, Paint.Align.CENTER, letterSpace = 0.1f))

                val rx = panel.left + 336f * s
                val rows = listOf(
                    Triple("DURATION", formatDuration(durationMs), COLOR_FROST_WHITE),
                    Triple("PACE", "$rpm RPM", COLOR_SKY_MINT),
                    Triple("CALORIES", "${String.format(Locale.US, "%.1f", calories)} kcal", COLOR_FROST_WHITE),
                    Triple("STREAK", "$streak Best", COLOR_ICE_MINT)
                )
                rows.forEachIndexed { idx, (k, v, col) ->
                    val y = panel.top + (78f + idx * 62f) * s
                    canvas.drawText(k, rx, y, textPaint(22f * s, COLOR_MUTED_SLATE, Typeface.BOLD))
                    canvas.drawText(v, panel.right - 38f * s, y, textPaint(30f * s, col, Typeface.BOLD, Paint.Align.RIGHT))
                }
            }

            WatermarkTemplate.CORNER_STAMP -> {
                drawTopLeftBrandHeader(canvas, w, layout, rightTag = null)

                val pillW = (700f * s).coerceAtMost(w - 2f * mX)
                val pillH = 186f * s
                val pill = RectF(mX, bottomY - pillH, mX + pillW, bottomY)
                canvas.drawRoundRect(pill, 40f * s, 40f * s, glassFillPaint(160, 13, 19, 33))
                canvas.drawRoundRect(pill, 40f * s, 40f * s, strokePaint(2.5f * s, Color.argb(170, 46, 229, 157)))

                val pad = 18f * s
                val badgeW = 196f * s
                val badge = RectF(pill.left + pad, pill.top + pad, pill.left + pad + badgeW, pill.bottom - pad)
                canvas.drawRoundRect(badge, 28f * s, 28f * s, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_ICE_MINT })
                canvas.drawText(
                    jumpCount.toString(),
                    badge.centerX(),
                    badge.centerY() + 12f * s,
                    textPaint((68f + jumpPopBoost * 6f) * s, COLOR_DEEP_MIDNIGHT, Typeface.BOLD, Paint.Align.CENTER, shadow = false)
                )
                canvas.drawText(
                    "JUMPS",
                    badge.centerX(),
                    badge.bottom - 18f * s,
                    textPaint(18f * s, COLOR_DEEP_MIDNIGHT, Typeface.BOLD, Paint.Align.CENTER, shadow = false)
                )

                val tx = badge.right + 26f * s
                canvas.drawText(badgeSubtitle, tx, pill.top + 54f * s, textPaint(20f * s, COLOR_ICE_MINT, Typeface.BOLD, letterSpace = 0.08f))
                canvas.drawText("${formatDuration(durationMs)}  •  $rpm RPM", tx, pill.top + 104f * s, textPaint(32f * s, COLOR_FROST_WHITE, Typeface.BOLD))
                canvas.drawText("${String.format(Locale.US, "%.1f", calories)} kcal  •  Streak $streak", tx, pill.top + 148f * s, textPaint(24f * s, COLOR_MUTED_SLATE, Typeface.BOLD))
            }
        }
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

        drawJumpvexLogoBadge(canvas, 54f, 48f, 84f)
        canvas.drawText("JUMPVEX", 156f, 104f, textPaint(40f, COLOR_ICE_MINT, Typeface.BOLD, letterSpace = 0.08f))
        canvas.drawText(
            rightTag,
            WIDTH - 54f,
            102f,
            textPaint(24f, Color.argb(220, 248, 250, 252), Typeface.BOLD, Paint.Align.RIGHT)
        )

        canvas.drawText(
            "TOTAL JUMPS",
            64f,
            HEIGHT - 392f,
            textPaint(25f, Color.argb(205, 248, 250, 252), Typeface.BOLD, letterSpace = 0.14f)
        )

        val countText = jumpCount.toString()
        val heroSize = 196f + jumpPopBoost * 12f
        val heroColor = if (jumpPopBoost > 0.25f) COLOR_ICE_MINT else COLOR_FROST_WHITE
        val heroPaint = textPaint(heroSize, heroColor, Typeface.BOLD)
        canvas.drawText(countText, 60f, HEIGHT - 210f, heroPaint)

        val countW = heroPaint.measureText(countText)
        val pillLeft = (64f + countW + 34f).coerceAtMost(WIDTH - 296f)
        val pillRect = RectF(pillLeft, HEIGHT - 278f, pillLeft + 242f, HEIGHT - 212f)
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
