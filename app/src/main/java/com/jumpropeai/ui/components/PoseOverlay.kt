package com.jumpropeai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import com.jumpropeai.ai.LandmarkPoint
import com.jumpropeai.ai.PoseFrame
import com.jumpropeai.ui.theme.IceMint
import com.jumpropeai.ui.theme.TextPrimary
import kotlin.math.max

/**
 * Draws the AI-detected body pose skeleton on top of the CameraX PreviewView.
 * Uses FILL_CENTER coordinate transformation and horizontal mirroring for the front camera.
 */
@Composable
fun PoseOverlay(
    poseFrame: PoseFrame?,
    isAirborne: Boolean,
    modifier: Modifier = Modifier,
    minConfidence: Float = 0.5f
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val frame = poseFrame ?: return@Canvas
        if (frame.imageWidth <= 0 || frame.imageHeight <= 0 || frame.landmarks.isEmpty()) {
            return@Canvas
        }

        // Match PreviewView.ScaleType.FILL_CENTER scaling
        val scale = max(
            size.width / frame.imageWidth.toFloat(),
            size.height / frame.imageHeight.toFloat()
        )
        val scaledWidth = frame.imageWidth * scale
        val scaledHeight = frame.imageHeight * scale
        val offsetX = (size.width - scaledWidth) / 2f
        val offsetY = (size.height - scaledHeight) / 2f

        fun mapPoint(point: LandmarkPoint): Offset {
            val rawX = if (frame.isMirrored) {
                frame.imageWidth - point.x
            } else {
                point.x
            }
            return Offset(
                x = rawX * scale + offsetX,
                y = point.y * scale + offsetY
            )
        }

        val boneColor = if (isAirborne) IceMint else TextPrimary.copy(alpha = 0.75f)
        val glowColor = (if (isAirborne) IceMint else TextPrimary).copy(alpha = 0.24f)

        // Draw skeleton bones
        for ((startType, endType) in PoseFrame.SKELETON_CONNECTIONS) {
            val start = frame.landmarks[startType]?.takeIf { it.confidence >= minConfidence } ?: continue
            val end = frame.landmarks[endType]?.takeIf { it.confidence >= minConfidence } ?: continue

            val p1 = mapPoint(start)
            val p2 = mapPoint(end)

            // Outer soft glow
            drawLine(
                color = glowColor,
                start = p1,
                end = p2,
                strokeWidth = 14f,
                cap = StrokeCap.Round
            )
            // Crisp inner bone line
            drawLine(
                color = boneColor,
                start = p1,
                end = p2,
                strokeWidth = 5f,
                cap = StrokeCap.Round
            )
        }

        // Draw joint points
        for ((_, point) in frame.landmarks) {
            if (point.confidence < minConfidence) continue
            val center = mapPoint(point)
            drawCircle(
                color = glowColor,
                radius = 12f,
                center = center
            )
            drawCircle(
                color = Color.White,
                radius = 5.5f,
                center = center
            )
        }
    }
}
