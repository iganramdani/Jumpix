# Contributing to Jumpvex

Thank you for your interest in improving **Jumpvex**! Every bug report, real-device CSV log, trick calibration PR, and UI improvement helps keep Jumpvex the most accurate and lightweight open-source jump rope AI app on Android.

## Quick Setup

1. Fork and clone the repository:
   ```bash
   git clone https://github.com/iganramdani/Jumpvex.git
   cd Jumpvex
   ```
2. Open in **Android Studio** (JDK 17, Android SDK 35) or run from terminal:
   ```bash
   ./gradlew testDebugUnitTest
   ```

## Engineering Rules (Please Respect)

1. **"99% Accuracy & 99% Lightweight":**
   - Keep hot paths (`JumpDetector`, `FusionEngine`, `KineticTrickCoach`, `StftAudioEngine`, `TouchlessGestureController`) **Zero-GC** (no per-frame `List`/`Map`/`String.format` allocations).
   - Never add heavy third-party dependencies that inflate the ~46 MB Release APK without strong justification.
2. **100% International English UI & Docs:**
   - All user-facing UI strings, TTS voice cues, and documentation must be in clean International English.
3. **Zero Accuracy Regression:**
   - Always run `./gradlew testDebugUnitTest` before opening a Pull Request.
   - If you tune `JumpDetector` or `FusionEngine` thresholds, verify zero regression against `ground_truth_all_videos.csv`.
4. **Submitting Device Calibration Logs:**
   - Enable **AI Performance & Full CSV Log** in Settings (or analyze a clip in Video Studio).
   - Attach the generated CSV from `Documents/Jumpvex/DebugLogs/` to your GitHub Issue — no video upload needed!

## License

By contributing to Jumpvex, you agree that your contributions will be licensed under the **GNU General Public License v3.0 (GPL-3.0)** ([`LICENSE`](LICENSE)).
