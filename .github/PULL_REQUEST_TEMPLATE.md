## Summary
<!-- Briefly describe what this Pull Request changes and why. -->

## Jumpvex Engineering Checklist ("99.9% VAR-Grade Precision & Real-Time 30 FPS Dual-Engine")
- [ ] **100% Phone-Only & Offline:** No cloud endpoints, no smartwatch/wearable dependencies, no tracking SDKs.
- [ ] **Tier 1 Zero-GC Hot Path Preserved:** No per-frame allocations inside `JumpDetector`, `FusionEngine`, `StftAudioEngine`, `CausalJumpTcnLite`, or `KineticTrickCoach` (`< 120 µs/frame`).
- [ ] **Unit Tests & 64-Video Benchmark Pass:** `./gradlew testDebugUnitTest` passes with 0 failures (`>= 99.0%` VolAcc).
- [ ] **High-Usability Ergonomics:** Interactive buttons/chips use `>= 48.dp` sweaty-hand touch targets and `>= 12.sp` typography.
- [ ] **100% International English UI:** All user-facing strings, TTS cues, and docs are in clean English.
- [ ] **Credits & License:** Any new open-source dependency or scientific reference is credited in [`CREDITS.md`](CREDITS.md) and compatible with GNU GPL v3.0.

## Verification Notes
<!-- Include device model, FPS/CPU µs notes, or screenshots if UI changed. -->
