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
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.jumpropeai.data.WorkoutSession
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

enum class CardTheme(val label: String) {
    DARK_POSTER("Poster Gelap"),
    TRANSPARENT_STICKER("Stiker Transparan"),
    PHOTO_BACKDROP("Latar Foto")
}

enum class WatermarkTemplate(val label: String) {
    MINIMAL_STRAVA("Strava"),
    NIKE_EDITORIAL("Editorial"),
    NEON_PULSE("Pulse HUD"),
    ACTIVITY_RING("Ring"),
    CORNER_STAMP("Stamp")
}

/**
 * Generates high-resolution (1080x1350) Strava/athletic workout summary cards & transparent stickers
 * in 5 modern face-friendly templates, with airy semi-transparent frosted glass panels and
 * customizable watermark scaling.
 */
object WorkoutCardRenderer {

    private const val WIDTH = 1080
    private const val HEIGHT = 1350

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
        val isTransparent = theme == CardTheme.TRANSPARENT_STICKER

        // 1. Background Layer
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

        // 2. Apply user watermark scale around bottom-center (or center for editorial/HUD)
        val safeScale = overlayScale.coerceIn(0.60f, 1.30f)
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
            WatermarkTemplate.MINIMAL_STRAVA -> drawMinimalStrava(canvas, session, styleLabel)
            WatermarkTemplate.NIKE_EDITORIAL -> drawNikeEditorial(canvas, session)
            WatermarkTemplate.NEON_PULSE -> drawNeonPulseHud(canvas, session)
            WatermarkTemplate.ACTIVITY_RING -> drawActivityRing(canvas, session)
            WatermarkTemplate.CORNER_STAMP -> drawCornerStamp(canvas, session)
        }

        canvas.restore()
        return bmp
    }

    // --- TEMPLATE 1: Minimal Strava (Clean Bottom Dock) ---
    private fun drawMinimalStrava(canvas: Canvas, session: WorkoutSession, styleLabel: String?) {
        val dateStr = SimpleDateFormat("dd MMM", Locale.getDefault())
            .format(Date(session.timestampMillis))
            .uppercase(Locale.getDefault())
        val rightTag = if (styleLabel != null) "$dateStr • ${styleLabel.uppercase(Locale.getDefault())}" else dateStr

        canvas.drawText("JUMPROPE AI", 64f, 94f, textPaint(32f, Color.parseColor("#C6FF00"), Typeface.BOLD))
        canvas.drawText(
            rightTag,
            WIDTH - 64f,
            94f,
            textPaint(26f, Color.argb(210, 255, 255, 255), Typeface.NORMAL, Paint.Align.RIGHT)
        )

        canvas.drawText(
            "TOTAL LOMPATAN",
            64f,
            HEIGHT - 418f,
            textPaint(26f, Color.argb(200, 255, 255, 255), Typeface.BOLD, letterSpace = 0.14f)
        )

        val countText = session.jumpCount.toString()
        val heroPaint = textPaint(196f, Color.WHITE, Typeface.BOLD)
        canvas.drawText(countText, 60f, HEIGHT - 236f, heroPaint)

        // Crisp translucent lime RPM pill right next to the hero number
        val countW = heroPaint.measureText(countText)
        val pillLeft = (64f + countW + 34f).coerceAtMost(WIDTH - 290f)
        val pillRect = RectF(pillLeft, HEIGHT - 304f, pillLeft + 236f, HEIGHT - 238f)
        canvas.drawRoundRect(pillRect, 33f, 33f, glassFillPaint(95, 16, 22, 14))
        canvas.drawRoundRect(pillRect, 33f, 33f, strokePaint(3f, Color.argb(225, 198, 255, 0)))
        canvas.drawText(
            "${session.averageRpm} RPM",
            pillRect.centerX(),
            pillRect.centerY() + 11f,
            textPaint(30f, Color.parseColor("#C6FF00"), Typeface.BOLD, Paint.Align.CENTER, shadow = false)
        )

        // Hairline divider
        val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(75, 255, 255, 255)
            strokeWidth = 2f
        }
        canvas.drawLine(64f, HEIGHT - 182f, WIDTH - 64f, HEIGHT - 182f, divPaint)

        // 4 clean bottom columns
        val cols = listOf(
            formatDuration(session.durationMs) to "DURASI",
            String.format(Locale.US, "%.1f", session.estimatedCalories) to "KKAL",
            "${session.peakRpm}" to "PEAK RPM",
            "${session.bestStreak}" to "STREAK"
        )
        val colW = (WIDTH - 128f) / 4f
        cols.forEachIndexed { idx, (v, l) ->
            val x = 64f + colW * idx
            canvas.drawText(v, x, HEIGHT - 114f, textPaint(48f, Color.WHITE, Typeface.BOLD))
            canvas.drawText(l, x, HEIGHT - 72f, textPaint(22f, Color.argb(190, 215, 225, 235), Typeface.BOLD, letterSpace = 0.08f))
        }
    }

    // --- TEMPLATE 2: Nike Editorial (Bold Split + Airy Semi-Transparent Glass) ---
    private fun drawNikeEditorial(canvas: Canvas, session: WorkoutSession) {
        val accentOrange = Color.parseColor("#FF5C00")
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentOrange }
        canvas.drawRoundRect(RectF(56f, 68f, 72f, 332f), 8f, 8f, barPaint)

        canvas.drawText(
            session.jumpCount.toString(),
            100f,
            236f,
            textPaint(196f, Color.WHITE, Typeface.BOLD_ITALIC)
        )
        canvas.drawText(
            "JUMPS  /  ${session.averageRpm} RPM",
            104f,
            290f,
            textPaint(36f, Color.parseColor("#FF8833"), Typeface.BOLD_ITALIC)
        )
        canvas.drawText(
            "JUMPROPE AI • FUSION",
            104f,
            330f,
            textPaint(22f, Color.argb(195, 255, 255, 255), Typeface.BOLD, letterSpace = 0.12f)
        )

        // Semi-transparent airy glass card bottom-right (lets photo/video shine through!)
        val box = RectF(WIDTH - 510f, HEIGHT - 270f, WIDTH - 54f, HEIGHT - 58f)
        canvas.drawRoundRect(box, 30f, 30f, glassFillPaint(88, 14, 18, 24))
        canvas.drawRoundRect(box, 30f, 30f, strokePaint(3f, Color.argb(190, 255, 110, 30)))

        val rows = listOf(
            "DURASI" to formatDuration(session.durationMs),
            "KALORI" to "${String.format(Locale.US, "%.1f", session.estimatedCalories)} kkal",
            "STREAK" to "${session.bestStreak}"
        )
        rows.forEachIndexed { i, (k, v) ->
            val y = box.top + 64f + i * 62f
            canvas.drawText(k, box.left + 34f, y, textPaint(24f, Color.argb(215, 230, 235, 242), Typeface.BOLD))
            canvas.drawText(v, box.right - 34f, y, textPaint(32f, Color.WHITE, Typeface.BOLD, Paint.Align.RIGHT))
        }
    }

    // --- TEMPLATE 3: Neon Pulse HUD (Viewfinder + Waveform) ---
    private fun drawNeonPulseHud(canvas: Canvas, session: WorkoutSession) {
        val cyan = Color.parseColor("#00E5FF")
        val lime = Color.parseColor("#C6FF00")

        // Viewfinder corner brackets
        val brPaint = strokePaint(6f, Color.argb(210, 0, 229, 255))
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

        // Top live telemetry pill
        val topPill = RectF(WIDTH / 2f - 236f, 56f, WIDTH / 2f + 236f, 116f)
        canvas.drawRoundRect(topPill, 30f, 30f, glassFillPaint(105, 10, 16, 24))
        canvas.drawRoundRect(topPill, 30f, 30f, strokePaint(2.5f, Color.argb(185, 0, 229, 255)))
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lime }
        canvas.drawCircle(topPill.left + 38f, topPill.centerY(), 9f, dotPaint)
        canvas.drawText(
            "FUSION AI • ${session.averageRpm} RPM",
            topPill.centerX() + 14f,
            topPill.centerY() + 9f,
            textPaint(26f, Color.WHITE, Typeface.BOLD, Paint.Align.CENTER, mono = true, shadow = false)
        )

        // Glowing acoustic pulse waveform across lower third
        val wavePath = Path()
        val startX = 64f
        val spanW = WIDTH - 128f
        val baseY = HEIGHT - 368f
        for (i in 0..120) {
            val t = i / 120f
            val env = sin(PI * t).toFloat()
            val yOff = (sin(t * 28.0) * 34.0 + sin(t * 63.0) * 14.0).toFloat() * env
            val px = startX + spanW * t
            val py = baseY + yOff
            if (i == 0) wavePath.moveTo(px, py) else wavePath.lineTo(px, py)
        }
        val glowWave = strokePaint(14f, Color.argb(55, 0, 229, 255))
        val crispWave = strokePaint(5f, Color.argb(230, 0, 229, 255))
        canvas.drawPath(wavePath, glowWave)
        canvas.drawPath(wavePath, crispWave)

        // Big mono jump counter + telemetry line
        canvas.drawText(
            session.jumpCount.toString(),
            WIDTH / 2f,
            HEIGHT - 196f,
            textPaint(164f, lime, Typeface.BOLD, Paint.Align.CENTER, mono = true)
        )
        canvas.drawText(
            "${formatDuration(session.durationMs)}   |   ${String.format(Locale.US, "%.1f", session.estimatedCalories)} KKAL   |   STREAK ${session.bestStreak}",
            WIDTH / 2f,
            HEIGHT - 126f,
            textPaint(28f, Color.argb(240, 235, 245, 255), Typeface.BOLD, Paint.Align.CENTER, mono = true)
        )
        canvas.drawText(
            "JUMPROPE AI",
            WIDTH / 2f,
            HEIGHT - 78f,
            textPaint(22f, Color.argb(190, 0, 229, 255), Typeface.BOLD, Paint.Align.CENTER, mono = true, letterSpace = 0.18f)
        )
    }

    // --- TEMPLATE 4: Activity Ring (Watch Style + Airy Semi-Transparent Glass) ---
    private fun drawActivityRing(canvas: Canvas, session: WorkoutSession) {
        val panel = RectF(48f, HEIGHT - 420f, WIDTH - 48f, HEIGHT - 52f)
        // Airy semi-transparent frosted glass (not heavy dark!)
        canvas.drawRoundRect(panel, 54f, 54f, glassFillPaint(82, 16, 22, 30))
        canvas.drawRoundRect(panel, 54f, 54f, strokePaint(2.5f, Color.argb(110, 255, 255, 255)))

        val cx = panel.left + 196f
        val cy = panel.centerY()
        val rOuter = 122f
        val rInner = 90f

        val outerRect = RectF(cx - rOuter, cy - rOuter, cx + rOuter, cy + rOuter)
        val innerRect = RectF(cx - rInner, cy - rInner, cx + rInner, cy + rInner)

        canvas.drawArc(outerRect, 0f, 360f, false, strokePaint(22f, Color.argb(55, 198, 255, 0)))
        val outerSweep = (session.jumpCount.coerceIn(10, 200) / 200f) * 320f
        canvas.drawArc(outerRect, -90f, outerSweep.coerceAtLeast(120f), false, strokePaint(22f, Color.parseColor("#C6FF00")))

        canvas.drawArc(innerRect, 0f, 360f, false, strokePaint(16f, Color.argb(50, 0, 229, 255)))
        val innerSweep = (session.averageRpm.coerceIn(40, 180) / 180f) * 300f
        canvas.drawArc(innerRect, -90f, innerSweep.coerceAtLeast(90f), false, strokePaint(16f, Color.parseColor("#00E5FF")))

        canvas.drawText(
            session.jumpCount.toString(),
            cx,
            cy + 14f,
            textPaint(66f, Color.WHITE, Typeface.BOLD, Paint.Align.CENTER)
        )
        canvas.drawText(
            "LOMPAT",
            cx,
            cy + 46f,
            textPaint(18f, Color.parseColor("#C6FF00"), Typeface.BOLD, Paint.Align.CENTER, letterSpace = 0.1f)
        )

        val rx = panel.left + 382f
        canvas.drawText("JUMPROPE AI", rx, panel.top + 68f, textPaint(24f, Color.parseColor("#C6FF00"), Typeface.BOLD, letterSpace = 0.1f))

        val rows = listOf(
            Triple("DURASI", formatDuration(session.durationMs), Color.WHITE),
            Triple("PACE", "${session.averageRpm} RPM", Color.parseColor("#00E5FF")),
            Triple("KALORI", "${String.format(Locale.US, "%.1f", session.estimatedCalories)} kkal", Color.parseColor("#FF7A1A")),
            Triple("STREAK", "${session.bestStreak} Best", Color.parseColor("#C6FF00"))
        )
        rows.forEachIndexed { idx, (k, v, col) ->
            val y = panel.top + 132f + idx * 64f
            canvas.drawText(k, rx, y, textPaint(24f, Color.argb(215, 225, 232, 240), Typeface.BOLD))
            canvas.drawText(v, panel.right - 44f, y, textPaint(32f, col, Typeface.BOLD, Paint.Align.RIGHT))
        }
    }

    // --- TEMPLATE 5: Corner Stamp (Ultra-Minimal Airy Semi-Transparent Pill) ---
    private fun drawCornerStamp(canvas: Canvas, session: WorkoutSession) {
        val pill = RectF(48f, HEIGHT - 254f, 756f, HEIGHT - 52f)
        // Light, airy semi-transparent frosted glass!
        canvas.drawRoundRect(pill, 44f, 44f, glassFillPaint(84, 14, 18, 26))
        canvas.drawRoundRect(pill, 44f, 44f, strokePaint(2.5f, Color.argb(120, 255, 255, 255)))

        // Vibrant semi-translucent lime badge on the left
        val badge = RectF(pill.left + 22f, pill.top + 22f, pill.left + 224f, pill.bottom - 22f)
        val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 198, 255, 0)
        }
        canvas.drawRoundRect(badge, 32f, 32f, badgeFill)
        canvas.drawText(
            session.jumpCount.toString(),
            badge.centerX(),
            badge.centerY() + 14f,
            textPaint(74f, Color.parseColor("#0A0D12"), Typeface.BOLD, Paint.Align.CENTER, shadow = false)
        )
        canvas.drawText(
            "LOMPAT",
            badge.centerX(),
            badge.bottom - 22f,
            textPaint(20f, Color.argb(215, 10, 13, 18), Typeface.BOLD, Paint.Align.CENTER, shadow = false)
        )

        val tx = badge.right + 32f
        canvas.drawText("JUMPROPE AI", tx, pill.top + 62f, textPaint(24f, Color.parseColor("#C6FF00"), Typeface.BOLD, letterSpace = 0.1f))
        canvas.drawText(
            "${formatDuration(session.durationMs)}  •  ${session.averageRpm} RPM",
            tx,
            pill.top + 114f,
            textPaint(34f, Color.WHITE, Typeface.BOLD)
        )
        canvas.drawText(
            "${String.format(Locale.US, "%.1f", session.estimatedCalories)} kkal  •  Streak ${session.bestStreak}",
            tx,
            pill.top + 162f,
            textPaint(26f, Color.parseColor("#00E5FF"), Typeface.BOLD)
        )
    }

    fun saveToGallery(context: Context, bitmap: Bitmap, baseName: String): Boolean {
        val fileName = "${baseName}_${System.currentTimeMillis()}.png"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/JumpRopeAI")
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
                    "JumpRopeAI"
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
                putExtra(Intent.EXTRA_TEXT, "Latihan lompat tali dengan JumpRope AI!")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Bagikan Kartu Latihan"))
        } catch (_: Exception) {
            // Ignore share errors gracefully
        }
    }

    private fun drawDarkPosterBackground(canvas: Canvas) {
        val bgPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(),
                intArrayOf(
                    Color.parseColor("#0B0F14"),
                    Color.parseColor("#131C28"),
                    Color.parseColor("#0A0D12")
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), bgPaint)
    }

    private fun drawSubtleTemplateScrim(canvas: Canvas, template: WatermarkTemplate) {
        if (template == WatermarkTemplate.CORNER_STAMP) return // Keep photo 100% bright & untouched!
        val bottomScrim = Paint().apply {
            shader = LinearGradient(
                0f, HEIGHT * 0.48f, 0f, HEIGHT.toFloat(),
                intArrayOf(Color.TRANSPARENT, Color.argb(195, 8, 11, 16)),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, HEIGHT * 0.48f, WIDTH.toFloat(), HEIGHT.toFloat(), bottomScrim)
        if (template == WatermarkTemplate.NIKE_EDITORIAL) {
            val topScrim = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, HEIGHT * 0.34f,
                    intArrayOf(Color.argb(150, 8, 11, 16), Color.TRANSPARENT),
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
        val totalSec = ms / 1000L
        val min = totalSec / 60L
        val sec = totalSec % 60L
        return String.format(Locale.US, "%02d:%02d", min, sec)
    }
}
