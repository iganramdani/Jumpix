# Credits, Open-Source Attributions & Respect

**Jumpvex** is built with deep respect for the open-source ecosystem, computer vision researchers, audio DSP pioneers, and the global jump rope & Melbourne Shuffle communities.

Every upstream library, algorithm, and community contribution below made it possible to ship a **100% free, zero-cloud, 100% offline Dual-Tier Neural VAR & Real-Time 30 FPS** competition-grade AI counter for everyone — using only an Android smartphone.

---

## 1. Open-Source Libraries & Toolchains

### Kotlin & Coroutines — JetBrains & Contributors
- **Repository:** https://github.com/JetBrains/kotlin | https://github.com/Kotlin/kotlinx.coroutines
- **License:** Apache License 2.0
- **Usage in Jumpvex:** Core language (`2.0.21`), Compose compiler plugin, and structured background concurrency (`kotlinx-coroutines-android`).

### AndroidX Jetpack Compose, Material 3 & Core Libraries — Google AndroidX Team
- **Repository:** https://github.com/androidx/androidx
- **License:** Apache License 2.0
- **Usage in Jumpvex:** Declarative UI (`compose-bom:2024.10.01`, Material 3, Material Icons Extended), `androidx.core:core-ktx`, `androidx.lifecycle`, and `androidx.activity:activity-compose`.

### AndroidX CameraX — Google CameraX Team
- **Repository:** https://github.com/androidx/androidx/tree/androidx-main/camera
- **License:** Apache License 2.0
- **Usage in Jumpvex:** Low-latency `360×480` `ImageAnalysis` pipeline (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view` `1.4.0`) with headless AMOLED Eco power mode and rear-camera + torch optical rPPG heart rate scanning.

### AndroidX Media3 Transformer & ExoPlayer — Google Media3 Team
- **Repository:** https://github.com/androidx/media
- **License:** Apache License 2.0
- **Usage in Jumpvex:** Hardware-accelerated video rendering (`media3-transformer`, `media3-effect`, `media3-common` `1.4.1`) powering Jumpvex's `VideoFittedBitmapOverlay` and `DynamicTelemetryBitmapOverlay` live animated counter exports, plus frame-accurate `0.2x` slow-motion scrubbing.

### Google ML Kit Pose Detection (BlazePose Fast & Heavy Accurate) — Google ML Kit & MediaPipe Teams
- **Documentation:** https://developers.google.com/ml-kit/vision/pose-detection
- **Research Paper:** *BlazePose: On-device Real-time Body Pose tracking* (Bazarevsky et al., Google Research, 2020)
- **Usage in Jumpvex:** Dual-tier on-device 2D & 3D skeletal landmark detection — `com.google.mlkit:pose-detection:18.0.0-beta5` (*Tier 1 Real-Time 30 FPS Live Camera*) and `com.google.mlkit:pose-detection-accurate:18.0.0-beta5` (*Tier 2 Heavy 3D Pose for Video Studio VAR*).

### JUnit 4 & AndroidX Test — JUnit Team & Contributors
- **Repository:** https://github.com/junit-team/junit4
- **License:** Eclipse Public License 1.0
- **Usage in Jumpvex:** Automated unit tests and the official 64-real-video / 3,767-jump ground-truth benchmark suite (`RealVideosBenchmarkTest.kt`).

### Gradle & Android Gradle Plugin
- **Repository:** https://github.com/gradle/gradle
- **License:** Apache License 2.0
- **Usage in Jumpvex:** Build automation, R8 whole-program minification, and resource shrinking.

---

## 2. Scientific, DSP & Sport Standards

- **Cooley–Tukey Radix-2 Fast Fourier Transform (1965) & Fitzgerald Harmonic-Percussive Source Separation (2010):** Basis of Jumpvex's pure-Kotlin Zero-GC 256-point 100/200 Hz STFT engine (`StftAudioEngine.kt`) and `NeuralAudioRopeIsolator.kt` time-axis median harmonic suppression.
- **Multi-Stage Dilated Temporal Convolutional Networks (Lea et al., 2017; Farha & Gall, CVPR 2019):** Inspiration for Jumpvex's compact 5-fold cross-validated `CausalJumpTcnLite` and Bidirectional `NeuralVarEngine` (`.jvxmodel`).
- **ACSM Compendium of Physical Activities (Ainsworth et al.):** Basis of Jumpvex's intensity-scaled jump rope MET calorie estimation (`MetricsTracker.kt`), adjusted by athlete body weight and rope resistance.
- **International Jump Rope Union (IJRU):** Official competition timing & Right-Foot Judge standards inspiring Jumpvex's **30-Second Speed Sprint**, **3-Minute Endurance**, and `IjrcSpeedSprintEngine.kt`.

---

## 3. Athlete & Community Acknowledgments

Special thanks and respect to the jump rope and shuffle dance athletes whose public training clips and community discussions helped calibrate Jumpvex against real-world edge cases (IJRC 30s stage sprints, bent-over 320–430 SPM speed rope, Double/Triple Unders, boxing footwork, and side-view camera angles):

- **`@D.LEE___1`** — Official IJRC 30s stage speed-sprint (`97` Right-Foot Judge Score / `194` total steps) benchmark reference.
- **`@cenxiaolin1`** — Competition bent-over 30s speed-sprint (~272–328 RPM) benchmark reference.
- **`@alwaysghadi`** — Boxing footwork & rhythm-change tutorial benchmark reference.
- **`r/jumprope` & Melbourne Shuffle communities** — Real-world feedback on footwork mechanics, Double Unders, and practical phone-placement ergonomics.

---

*If you believe an open-source contributor or reference should be added or updated here, please open a Pull Request or Issue — credit is always given where credit is due.*
