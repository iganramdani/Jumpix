# Credits, Open-Source Attributions & Respect

**Jumpvex** is built with deep respect for the open-source ecosystem, computer vision researchers, audio DSP pioneers, and the global jump rope & Melbourne Shuffle communities.

Every upstream library, algorithm, and community contribution below made it possible to ship a **100% free, zero-cloud, 46 MB** competition-grade AI counter for everyone — especially athletes using budget Android smartphones.

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
- **Usage in Jumpvex:** Low-latency `360×480` `ImageAnalysis` pipeline (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view` `1.4.0`) with headless AMOLED Eco power mode.

### AndroidX Media3 Transformer — Google Media3 Team
- **Repository:** https://github.com/androidx/media
- **License:** Apache License 2.0
- **Usage in Jumpvex:** Hardware-accelerated video rendering (`media3-transformer`, `media3-effect`, `media3-common` `1.4.1`) powering Jumpvex's `VideoFittedBitmapOverlay` and `DynamicTelemetryBitmapOverlay` live animated counter exports.

### Google ML Kit Pose Detection (BlazePose) — Google ML Kit & MediaPipe Teams
- **Documentation:** https://developers.google.com/ml-kit/vision/pose-detection
- **Research Paper:** *BlazePose: On-device Real-time Body Pose tracking* (Bazarevsky et al., Google Research, 2020)
- **Usage in Jumpvex:** Real-time on-device 2D & 3D skeletal landmark detection (`com.google.mlkit:pose-detection:18.0.0-beta5`), including `LEFT/RIGHT_HEEL` and `LEFT/RIGHT_FOOT_INDEX` for Jumpvex's Foot-Triangle biomechanics.

### JUnit 4 & AndroidX Test — JUnit Team & Contributors
- **Repository:** https://github.com/junit-team/junit4
- **License:** Eclipse Public License 1.0
- **Usage in Jumpvex:** Automated unit tests and the 46-video / 2,212-jump multi-smartphone regression benchmark suite.

### Gradle & Android Gradle Plugin
- **Repository:** https://github.com/gradle/gradle
- **License:** Apache License 2.0
- **Usage in Jumpvex:** Build automation, R8 whole-program minification, and resource shrinking.

---

## 2. Scientific, DSP & Sport Standards

- **Cooley–Tukey Radix-2 Fast Fourier Transform (1965):** Basis of Jumpvex's pure-Kotlin Zero-GC 256-point 100 Hz STFT acoustic flux engine (`StftAudioEngine.kt`).
- **ACSM Compendium of Physical Activities (Ainsworth et al.):** Basis of Jumpvex's intensity-scaled jump rope MET calorie estimation (`MetricsTracker.kt`), adjusted by athlete body weight and rope resistance.
- **International Jump Rope Union (IJRU):** Official competition timing standards inspiring Jumpvex's **30-Second Speed Sprint** and **3-Minute Speed Endurance** modes.

---

## 3. Athlete & Community Acknowledgments

Special thanks and respect to the jump rope and shuffle dance athletes whose public training clips and community discussions helped calibrate Jumpvex against real-world edge cases (low FPS, bent-over 320+ RPM sprints, boxing footwork, and side-view camera angles):

- **`@cenxiaolin1`** — Competition bent-over 30s speed-sprint (~272–328 RPM) benchmark reference.
- **`@alwaysghadi`** — Boxing footwork & rhythm-change tutorial benchmark reference.
- **`r/jumprope` & Melbourne Shuffle communities** — Real-world feedback on footwork mechanics, Double Unders, and practical phone-placement ergonomics.

---

*If you believe an open-source contributor or reference should be added or updated here, please open a Pull Request or Issue — credit is always given where credit is due.*
