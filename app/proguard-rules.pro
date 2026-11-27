# Keep ML Kit Pose Detection (Fast + Accurate Heavy) & Vision JNI classes
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_pose_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_pose_accurate.** { *; }
-keep class com.google.android.libraries.vision.** { *; }
-dontwarn com.google.mlkit.**

# Keep CameraX & Media3 Transformer reflective hooks
-keep class androidx.camera.** { *; }
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep Jumpvex data models & enums serialized to SharedPreferences / JSON
-keep class com.jumpropeai.data.** { *; }
-keep class com.jumpropeai.workout.** { *; }
-keep class com.jumpropeai.metrics.RopeType { *; }
