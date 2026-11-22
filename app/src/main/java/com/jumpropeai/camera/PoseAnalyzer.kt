package com.jumpropeai.camera

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.jumpropeai.ai.BodyLandmark
import com.jumpropeai.ai.LandmarkPoint
import com.jumpropeai.ai.PoseFrame

/**
 * CameraX ImageAnalysis.Analyzer backed by ML Kit Pose Detection (STREAM_MODE).
 * Extracts key body landmarks and delivers rotation-adjusted PoseFrames to the caller.
 */
class PoseAnalyzer(
    private val isFrontCameraProvider: () -> Boolean,
    private val onPoseFrame: (PoseFrame) -> Unit
) : ImageAnalysis.Analyzer {

    private val detector: PoseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val isRotated = rotationDegrees == 90 || rotationDegrees == 270
        val effectiveWidth = if (isRotated) imageProxy.height else imageProxy.width
        val effectiveHeight = if (isRotated) imageProxy.width else imageProxy.height
        val isMirrored = isFrontCameraProvider()
        val timestampMs = SystemClock.elapsedRealtime()

        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        detector.process(inputImage)
            .addOnSuccessListener { pose ->
                val frame = pose.toPoseFrame(
                    timestampMs = timestampMs,
                    imageWidth = effectiveWidth,
                    imageHeight = effectiveHeight,
                    isMirrored = isMirrored
                )
                onPoseFrame(frame)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    fun close() {
        detector.close()
    }

    private fun Pose.toPoseFrame(
        timestampMs: Long,
        imageWidth: Int,
        imageHeight: Int,
        isMirrored: Boolean
    ): PoseFrame {
        val map = mutableMapOf<BodyLandmark, LandmarkPoint>()
        for ((mlType, bodyLandmark) in LANDMARK_MAPPING) {
            val landmark = getPoseLandmark(mlType) ?: continue
            map[bodyLandmark] = LandmarkPoint(
                x = landmark.position.x,
                y = landmark.position.y,
                confidence = landmark.inFrameLikelihood
            )
        }
        return PoseFrame(
            landmarks = map,
            timestampMs = timestampMs,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            isMirrored = isMirrored
        )
    }

    companion object {
        private val LANDMARK_MAPPING = listOf(
            PoseLandmark.NOSE to BodyLandmark.NOSE,
            PoseLandmark.LEFT_SHOULDER to BodyLandmark.LEFT_SHOULDER,
            PoseLandmark.RIGHT_SHOULDER to BodyLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_ELBOW to BodyLandmark.LEFT_ELBOW,
            PoseLandmark.RIGHT_ELBOW to BodyLandmark.RIGHT_ELBOW,
            PoseLandmark.LEFT_WRIST to BodyLandmark.LEFT_WRIST,
            PoseLandmark.RIGHT_WRIST to BodyLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_HIP to BodyLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_HIP to BodyLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_KNEE to BodyLandmark.LEFT_KNEE,
            PoseLandmark.RIGHT_KNEE to BodyLandmark.RIGHT_KNEE,
            PoseLandmark.LEFT_ANKLE to BodyLandmark.LEFT_ANKLE,
            PoseLandmark.RIGHT_ANKLE to BodyLandmark.RIGHT_ANKLE
        )
    }
}
