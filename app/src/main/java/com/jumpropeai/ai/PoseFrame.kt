package com.jumpropeai.ai

enum class BodyLandmark {
    NOSE,
    LEFT_SHOULDER,
    RIGHT_SHOULDER,
    LEFT_ELBOW,
    RIGHT_ELBOW,
    LEFT_WRIST,
    RIGHT_WRIST,
    LEFT_HIP,
    RIGHT_HIP,
    LEFT_KNEE,
    RIGHT_KNEE,
    LEFT_ANKLE,
    RIGHT_ANKLE,
    LEFT_HEEL,
    RIGHT_HEEL,
    LEFT_FOOT_INDEX,
    RIGHT_FOOT_INDEX
}

data class LandmarkPoint(
    val x: Float,
    val y: Float,
    val confidence: Float
)

data class PoseFrame(
    val landmarks: Map<BodyLandmark, LandmarkPoint>,
    val timestampMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val isMirrored: Boolean
) {
    companion object {
        val SKELETON_CONNECTIONS: List<Pair<BodyLandmark, BodyLandmark>> = listOf(
            BodyLandmark.LEFT_SHOULDER to BodyLandmark.RIGHT_SHOULDER,
            BodyLandmark.LEFT_SHOULDER to BodyLandmark.LEFT_ELBOW,
            BodyLandmark.LEFT_ELBOW to BodyLandmark.LEFT_WRIST,
            BodyLandmark.RIGHT_SHOULDER to BodyLandmark.RIGHT_ELBOW,
            BodyLandmark.RIGHT_ELBOW to BodyLandmark.RIGHT_WRIST,
            BodyLandmark.LEFT_SHOULDER to BodyLandmark.LEFT_HIP,
            BodyLandmark.RIGHT_SHOULDER to BodyLandmark.RIGHT_HIP,
            BodyLandmark.LEFT_HIP to BodyLandmark.RIGHT_HIP,
            BodyLandmark.LEFT_HIP to BodyLandmark.LEFT_KNEE,
            BodyLandmark.LEFT_KNEE to BodyLandmark.LEFT_ANKLE,
            BodyLandmark.LEFT_ANKLE to BodyLandmark.LEFT_HEEL,
            BodyLandmark.LEFT_ANKLE to BodyLandmark.LEFT_FOOT_INDEX,
            BodyLandmark.LEFT_HEEL to BodyLandmark.LEFT_FOOT_INDEX,
            BodyLandmark.RIGHT_HIP to BodyLandmark.RIGHT_KNEE,
            BodyLandmark.RIGHT_KNEE to BodyLandmark.RIGHT_ANKLE,
            BodyLandmark.RIGHT_ANKLE to BodyLandmark.RIGHT_HEEL,
            BodyLandmark.RIGHT_ANKLE to BodyLandmark.RIGHT_FOOT_INDEX,
            BodyLandmark.RIGHT_HEEL to BodyLandmark.RIGHT_FOOT_INDEX
        )
    }
}
