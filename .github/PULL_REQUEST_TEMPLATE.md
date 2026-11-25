## Summary
<!-- Briefly describe what this Pull Request changes and why. -->

## Jumpvex Engineering Checklist ("99% Accuracy & 99% Lightweight")
- [ ] **100% Phone-Only & Offline:** No cloud endpoints, no smartwatch/wearable dependencies, no tracking SDKs.
- [ ] **Zero-GC Hot Path Preserved:** No per-frame allocations (`List`, `Pair`, `String.format`, boxed primitives) inside `JumpDetector`, `FusionEngine`, `StftAudioEngine`, or `KineticTrickCoach`.
- [ ] **Unit Tests Pass:** `./gradlew testDebugUnitTest` passes with 0 failures.
- [ ] **APK Size Guard:** Release APK remains `< 50 MB` (`./gradlew assembleRelease`).
- [ ] **100% International English UI:** All user-facing strings, TTS cues, and docs are in clean English.
- [ ] **Credits & License:** Any new open-source dependency or scientific reference is credited in [`CREDITS.md`](CREDITS.md) and compatible with GNU GPL v3.0.

## Verification Notes
<!-- Include device model, FPS/CPU µs notes, or screenshots if UI changed. -->
