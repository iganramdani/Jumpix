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
import android.graphics.Rect
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

enum class CardTheme(val label: String) {
    DARK_POSTER("Poster Gelap"),
    TRANSPARENT_STICKER("Stiker Transparan"),
    PHOTO_BACKDROP("Latar Foto")
}

/**
 * Generates high-resolution (1080x1350) Strava-style workout summary cards & transparent stickers,
 * saves them directly to the Android Gallery (Pictures/JumpRopeAI), and shares via FileProvider.
 */
object WorkoutCardRenderer {

    private const val WIDTH = 1080
    private const val HEIGHT = 1350

    fun renderCard(
        session: WorkoutSession,
        theme: CardTheme,
        backdropBitmap: Bitmap? = null,
        styleLabel: String? = null,
        overlayScale: Float = 1.0f
    ): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val isTransparent = theme == CardTheme.TRANSPARENT_STICKER

        // 1. Background Layer (always fills full 1080x1350 canvas)
        when (theme) {
            CardTheme.TRANSPARENT_STICKER -> {
                canvas.drawColor(Color.TRANSPARENT)
            }

            CardTheme.PHOTO_BACKDROP -> {
                if (backdropBitmap != null && !backdropBitmap.isRecycled) {
                    drawCenterCropBitmap(canvas, backdropBitmap, WIDTH, HEIGHT)
                    val scrim = Paint().apply {
                        shader = LinearGradient(
                            0f, 0f, 0f, HEIGHT.toFloat(),
                            intArrayOf(
                                Color.argb(175, 10, 13, 18),
                                Color.argb(120, 10, 13, 18),
                                Color.argb(225, 10, 13, 18)
                            ),
                            floatArrayOf(0f, 0.45f, 1f),
                            Shader.TileMode.CLAMP
                        )
                    }
                    canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), scrim)
                } else {
                    drawDarkPosterBackground(canvas)
                }
            }

            CardTheme.DARK_POSTER -> {
                drawDarkPosterBackground(canvas)
            }
        }

        // Apply user-chosen watermark/stats scale cleanly around canvas center
        val safeScale = overlayScale.coerceIn(0.55f, 1.35f)
        canvas.save()
        canvas.scale(safeScale, safeScale, WIDTH / 2f, HEIGHT / 2f)

        // 2. Top Brand Header + Date
        val dateText = SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.getDefault())
            .format(Date(session.timestampMillis))

        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C6FF00")
            textSize = 40f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.14f
            if (isTransparent) setShadowLayer(12f, 0f, 4f, Color.argb(180, 0, 0, 0))
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 255, 255, 255)
            textSize = 30f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            if (isTransparent) setShadowLayer(10f, 0f, 3f, Color.argb(180, 0, 0, 0))
        }

        canvas.drawText("JUMPROPE AI", 84f, 126f, brandPaint)
        canvas.drawText(dateText, 84f, 174f, subPaint)

        // Mode & Style Pill on top right
        val modeText = styleLabel?.let { "${session.modeLabel} • $it" } ?: session.modeLabel
        val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF")
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val pillBounds = Rect()
        pillTextPaint.getTextBounds(modeText, 0, modeText.length, pillBounds)
        val pillW = (pillBounds.width() + 56f).coerceAtMost(WIDTH - 168f)
        val pillRect = RectF(84f, 206f, 84f + pillW, 266f)
        val pillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(if (isTransparent) 130 else 40, 0, 229, 255)
        }
        val pillBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = Color.argb(150, 0, 229, 255)
        }
        canvas.drawRoundRect(pillRect, 30f, 30f, pillBg)
        canvas.drawRoundRect(pillRect, 30f, 30f, pillBorder)
        canvas.drawText(modeText, pillRect.left + 28f, pillRect.centerY() + 9f, pillTextPaint)

        // 3. Decorative Jump Rope Arc Motif (Strava route-line vibe!)
        drawJumpArcGraphic(canvas, yCenter = 430f, isTransparent = isTransparent)

        // 4. Hero Jump Count
        val heroLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(185, 255, 255, 255)
            textSize = 30f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.2f
            textAlign = Paint.Align.CENTER
            if (isTransparent) setShadowLayer(10f, 0f, 3f, Color.argb(180, 0, 0, 0))
        }
        val heroValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C6FF00")
            textSize = 220f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            setShadowLayer(24f, 0f, 8f, Color.argb(if (isTransparent) 200 else 110, 0, 0, 0))
        }
        canvas.drawText("TOTAL LOMPATAN", WIDTH / 2f, 520f, heroLabelPaint)
        canvas.drawText(session.jumpCount.toString(), WIDTH / 2f, 725f, heroValuePaint)

        // 5. Athletic Stats Grid (2 rows x 3 columns)
        val cardRect = RectF(72f, 815f, WIDTH - 72f, 1185f)
        if (!isTransparent) {
            val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(175, 22, 27, 34)
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
                color = Color.argb(55, 255, 255, 255)
            }
            canvas.drawRoundRect(cardRect, 40f, 40f, glassPaint)
            canvas.drawRoundRect(cardRect, 40f, 40f, borderPaint)

            val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(38, 255, 255, 255)
                strokeWidth = 2f
            }
            canvas.drawLine(cardRect.left + 44f, cardRect.centerY(), cardRect.right - 44f, cardRect.centerY(), divPaint)
        }

        val colW = cardRect.width() / 3f
        val row1Y = cardRect.top + 108f
        val row2Y = cardRect.centerY() + 108f

        drawStatCell(
            canvas, cardRect.left + colW * 0.5f, row1Y,
            value = formatDuration(session.durationMs),
            label = "DURASI",
            valueColor = Color.WHITE,
            shadow = isTransparent
        )
        drawStatCell(
            canvas, cardRect.left + colW * 1.5f, row1Y,
            value = "${session.averageRpm}",
            label = "AVG RPM",
            valueColor = Color.parseColor("#00E5FF"),
            shadow = isTransparent
        )
        drawStatCell(
            canvas, cardRect.left + colW * 2.5f, row1Y,
            value = String.format(Locale.US, "%.1f", session.estimatedCalories),
            label = "KALORI (KKAL)",
            valueColor = Color.parseColor("#FF6D00"),
            shadow = isTransparent
        )

        drawStatCell(
            canvas, cardRect.left + colW * 0.5f, row2Y,
            value = "${session.peakRpm}",
            label = "PEAK RPM",
            valueColor = Color.parseColor("#00E5FF"),
            shadow = isTransparent
        )
        drawStatCell(
            canvas, cardRect.left + colW * 1.5f, row2Y,
            value = "${session.bestStreak}",
            label = "BEST STREAK",
            valueColor = Color.parseColor("#C6FF00"),
            shadow = isTransparent
        )
        val jumpsPerMin = if (session.durationMs >= 1000L) {
            String.format(Locale.US, "%.1f/s", session.jumpCount / (session.durationMs / 1000.0))
        } else {
            "0.0/s"
        }
        drawStatCell(
            canvas, cardRect.left + colW * 2.5f, row2Y,
            value = jumpsPerMin,
            label = "KECEPATAN",
            valueColor = Color.WHITE,
            shadow = isTransparent
        )

        // 6. Footer Watermark
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(145, 255, 255, 255)
            textSize = 24f
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.08f
            if (isTransparent) setShadowLayer(8f, 0f, 2f, Color.argb(180, 0, 0, 0))
        }
        canvas.drawText("TRACKED ON-DEVICE • JUMPROPE AI FUSION", WIDTH / 2f, HEIGHT - 72f, footerPaint)
        canvas.restore()

        return bmp
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
                    Color.parseColor("#111924"),
                    Color.parseColor("#0A0D12")
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), bgPaint)

        // Subtle athletic glow accent behind hero counter
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(26, 198, 255, 0)
        }
        canvas.drawCircle(WIDTH / 2f, 610f, 340f, glowPaint)
    }

    private fun drawJumpArcGraphic(canvas: Canvas, yCenter: Float, isTransparent: Boolean) {
        val path = Path().apply {
            moveTo(140f, yCenter)
            cubicTo(340f, yCenter - 170f, WIDTH - 340f, yCenter - 170f, WIDTH - 140f, yCenter)
        }
        val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 7f
            strokeCap = Paint.Cap.ROUND
            shader = LinearGradient(
                140f, yCenter, WIDTH - 140f, yCenter,
                intArrayOf(
                    Color.argb(30, 0, 229, 255),
                    Color.argb(if (isTransparent) 230 else 210, 198, 255, 0),
                    Color.argb(30, 0, 229, 255)
                ),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(path, arcPaint)
    }

    private fun drawStatCell(
        canvas: Canvas,
        cx: Float,
        baselineY: Float,
        value: String,
        label: String,
        valueColor: Int,
        shadow: Boolean
    ) {
        val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = valueColor
            textSize = 56f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            if (shadow) setShadowLayer(12f, 0f, 4f, Color.argb(200, 0, 0, 0))
        }
        val lblPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(170, 255, 255, 255)
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.1f
            textAlign = Paint.Align.CENTER
            if (shadow) setShadowLayer(8f, 0f, 2f, Color.argb(180, 0, 0, 0))
        }
        canvas.drawText(value, cx, baselineY, valPaint)
        canvas.drawText(label, cx, baselineY + 40f, lblPaint)
    }

    private fun drawCenterCropBitmap(canvas: Canvas, src: Bitmap, dstW: Int, dstH: Int) {
        val scale = maxOf(dstW.toFloat() / src.width, dstH.toFloat() / src.height)
        val scaledW = src.width * scale
        val scaledH = src.height * scale
        val left = (dstW - scaledW) / 2f
        val top = (dstH - scaledH) / 2f
        val dstRect = RectF(left, top, left + scaledW, top + scaledH)
        canvas.drawBitmap(src, null, dstRect, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000L
        val min = totalSec / 60L
        val sec = totalSec % 60L
        return String.format(Locale.US, "%02d:%02d", min, sec)
    }
}
