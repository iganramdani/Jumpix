<div align="center">

<a href="https://iganramdani.github.io/Jumpvex/">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/hero_banner_dark.svg" />
    <source media="(prefers-color-scheme: light)" srcset="docs/assets/hero_banner_light.svg" />
    <img alt="Jumpvex v7.0 — 99.9% VAR-Grade Precision &amp; Real-Time 30 FPS Dual-Engine Phone-Only AI Jump Rope Suite" src="docs/assets/hero_banner_dark.svg" width="100%" />
  </picture>
</a>

<br/><br/>

[![Launch Web Telemetry Lab](https://img.shields.io/badge/🔬_Launch_Web_Telemetry_Lab-2EE59D?style=for-the-badge&logoColor=0D1321)](https://iganramdani.github.io/Jumpvex/)
[![Download Release APK](https://img.shields.io/badge/⬇️_Download_v7.1.0_APK_(52_MB)-38BDF8?style=for-the-badge&logo=android&logoColor=0D1321)](https://github.com/iganramdani/Jumpvex/releases/download/v7.1.0/Jumpvex-v7.1.0-release.apk)
[![GitHub Downloads](https://img.shields.io/github/downloads/iganramdani/Jumpvex/total?style=for-the-badge&color=2EE59D&labelColor=151D2E&logo=github)](https://github.com/iganramdani/Jumpvex/releases)
[![GitHub Stars](https://img.shields.io/github/stars/iganramdani/Jumpvex?style=for-the-badge&color=2EE59D&labelColor=151D2E&logo=github)](https://github.com/iganramdani/Jumpvex/stargazers)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-2EE59D?style=for-the-badge&logo=gnu&logoColor=0D1321)](LICENSE)

<br/>

[🔬 Web Telemetry Lab](https://iganramdani.github.io/Jumpvex/) •
[✨ Why Jumpvex](#-why-jumpvex-built-by-athletes-engineered-for-var-precision) •
[🆚 Phone-Only vs \$400 Gear](#-how-jumpvex-compares-100-phone-only) •
[📱 UI Gallery](#-modern-two-tone-ui-gallery) •
[📊 64-Video Benchmark](#-official-64-real-video-benchmark) •
[🧠 Architecture](#-ai-engine-v17-dual-tier-neural-var-architecture) •
[📱 Termux AI Training](#-train-with-the-community-1-command-android-termux) •
[🙏 Credits](#-open-source-credits--respect) •
[📦 Download](#-download-apk-release--debug)

<br/>

> 💡 **Interactive Community Tools:**
> [🔬 Inspect Your CSV in Browser](https://iganramdani.github.io/Jumpvex/#telemetry-lab) &nbsp;•&nbsp;
> [🎯 Submit Calibration CSV](https://github.com/iganramdani/Jumpvex/issues/new?template=calibration_contribution.yml) &nbsp;•&nbsp;
> [🐛 Report a Bug](https://github.com/iganramdani/Jumpvex/issues/new?template=bug_report.yml) &nbsp;•&nbsp;
> [✨ Request a Trick](https://github.com/iganramdani/Jumpvex/issues/new?template=trick_request.yml) &nbsp;•&nbsp;
> [🤝 Contributing](CONTRIBUTING.md)

</div>

---

## ✨ Why Jumpvex: Built by Athletes, Engineered for VAR Precision

> **Every jump rope & shuffle athlete deserves competition-grade VAR analytics — without paying \$120–\$500 for smart handles, wearables, or cloud subscriptions.**
>
> Born from real daily training sessions on a budget **Xiaomi Redmi Note 9** and rigorously benchmarked across **64 real-world videos (`3,767` human-verified jumps/steps)**, **Jumpvex** unites **Dual-Tier Heavy 3D Neural VAR** and a **Real-Time 30 FPS Live Cockpit** inside one **100% offline, open-source GPLv3** Android app.

| 🎯 Tier 2: 99.9% VAR-Grade Video Studio (Heavy Precision) | ⚡ Tier 1: Real-Time 30 FPS Live Cockpit (Instant Zero-GC) |
| :--- | :--- |
| 🧠 **Heavy 3D Pose + 5-Fold Bidirectional MS-TCN (`var_bitemporal_v7.jvxmodel`)** — pairs `pose-detection-accurate` (*Heavy 3D Landmarks*) with a full-sequence bidirectional dilated TCN trained via strict **5-Fold Group Cross-Validation** (`81.62%` Out-of-Fold F1, zero data leakage) + **99.5%+ Volume Accuracy** across 64 real videos. | ⚡ **30 FPS Live Camera + Streaming `CausalJumpTcnLite`** — keeps blazing-fast BlazePose + past-only causal TCN (`< 40 µs/frame`, Zero-GC) to reject close-up walk-toward-camera & IG-outro noise in real time on budget phones (**Xiaomi Redmi Note 9**). |
| 👁️ **Sub-Foot & Overhead Visual Rope Tracker (`OpticalRopeTracker`)** — zero-copy `0°/90°/180°/270°` `NV21` portrait cable-streak detector counts physical rope passes under your shoes & over your head — even on **muted videos**, verifying Double/Triple Unders visually. | 🎛️ **High-Usability Sweaty-Hand Cockpit (`>= 48.dp`)** — **1-Tap Sensor Pill** (*Fusion AI / Pocket IMU / Audio Eco / Camera*) on Top Bar, auto-hiding **Pre-Workout Quick Dock** (*Pace • Ghost • rPPG HR • 2P Battle*), and **3-Category Settings** with haptic feedback. |
| 🎵 **HPSS 200 Hz Audio Isolator + Compact Sticky Slow-Mo Studio** — strips loud EDM/gym music via 17-frame harmonic median subtraction, compact `165dp` expandable slow-mo player, **4 Crisp Sub-Tabs**, proportional **Live HUD Video Export (`9:16`, `16:9`, `1:1`, `4K`)**, and **1-Tap Batch `.ZIP` Logs**. | 📦 **Single 52 MB 100% Offline APK** — bundles both Fast & Heavy 3D Pose + both `.jvxmodel` neural weights + 200 Hz STFT + Media3 GPU HUD exporter completely offline ([`SECURITY.md`](SECURITY.md)). |

---

## 🆚 How Jumpvex Compares (100% Phone-Only)

| Capability | **Jumpvex (`v7.0.0` VAR)** | YaoYao | Crossrope AMP | JRC AI | JustJump |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **100% Phone-Only (Zero Watch / Zero Smart Rope)** | ✅ **99.44% (64 Videos)** | ❌ Watch / AirPods | ❌ \$119+ Handles | ✅ Camera only | ❌ Manual |
| **Dual-Tier AI (Heavy 3D VAR + 30 FPS Live TCN)** | ✅ **1-Tap Toggle** | ❌ IMU only | ❌ Hall sensor | ⚠️ Fast 2D only | ❌ |
| **Visual Sub-Foot Rope Pass + HPSS Audio Isolation** | ✅ **Works Muted / EDM** | ❌ | ❌ | ❌ | ❌ |
| **Sticky `0.2x` Slow-Mo + 4-Tab Telemetry Studio** | ✅ **Synced Scrubbing** | ❌ | ❌ | ❌ | ❌ |
| **Trick & Shuffle Dance Classifier** | ✅ **20 Moves + Combos** | ⚠️ Double Under | ❌ | ❌ | ❌ Catalog only |
| **Hands-Free Control (Hand-Raise + 5m Double-Clap)** | ✅ **Dual Touchless** | ✅ Watch/Voice | ❌ | ✅ Raise Hand | ❌ |
| **Rear-Lens + Torch rPPG Heart Rate & HRR-60** | ✅ **Live PPG Chart** | ❌ Requires Watch | ❌ | ❌ | ❌ |
| **Live Animated Counter Video Export** | ✅ **Media3 GPU HUD** | ❌ | ❌ | ❌ | ❌ |
| **Browser CSV Telemetry & VAR Oscilloscope** | ✅ **[Web Lab](https://iganramdani.github.io/Jumpvex/)** | ❌ | ❌ | ❌ | ❌ |
| **Price, Privacy & Open-Source** | ✅ **100% Free GPLv3** | 💰 Paid | 💰 \$119/yr | 💰 Freemium | 💰 Freemium |

---

## 📱 Modern Two-Tone UI Gallery

Crafted with a clean futuristic two-tone palette — **Deep Midnight (`#0D1321`)** + **Ice Mint (`#2EE59D`)** — ergonomic `>= 48.dp` touch targets, and a clutter-free **3-Tab Navigation** (*Workout • Video • History*):

<div align="center">
<table>
  <tr>
    <td align="center" width="33%">
      <img src="docs/assets/ui_live_ring_hud.jpg" width="240" alt="Jumpvex Dynamic Ring HUD"/><br/>
      <b>🟢 Tab 1: 1-Tap Live Cockpit</b><br/>
      <sub>1-Tap Sensor Pill, Auto-Hiding Quick Dock (Pace • Ghost • rPPG HR • 2P Battle), Hand-Raise &amp; Double-Clap, Pocket IMU</sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/assets/ui_video_studio.jpg" width="240" alt="Jumpvex Video Studio"/><br/>
      <b>🎬 Tab 2: Sticky Neural VAR Studio</b><br/>
      <sub>Pinned 0.2x Slow-Mo Player with live VAR % &amp; Cable Pass HUD, Heavy VAR / Fast 30fps toggle, 4 Sub-Tabs &amp; 1-Tap .ZIP Logs</sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/assets/ui_history_stats.jpg" width="240" alt="Jumpvex History &amp; Weekly Stats"/><br/>
      <b>📈 Tab 3: History, Heatmap &amp; Skills</b><br/>
      <sub>12-week consistency heatmap, Rope Cable Wear, 5-Tier AI Skill Academy, Personal Records &amp; 1-tap CSV backup</sub>
    </td>
  </tr>
</table>
</div>

---

## 📊 Official 64-Real-Video Benchmark

Continuously verified by [`RealVideosBenchmarkTest.kt`](app/src/test/java/com/jumpropeai/benchmark/RealVideosBenchmarkTest.kt) directly against **64 real-world videos** (`JumpRope Test` dataset + official IJRC 30s Stage Speed Sprint in [`ground_truth_all_videos.csv`](ground_truth_all_videos.csv), **`3,767` human-verified jumps/steps**) and strictly validated via **5-Fold Group Cross-Validation** (`training/train_neural_var_v7.py`, zero data leakage):

<div align="center">
  <img src="docs/assets/real_64_video_benchmark.svg" alt="Jumpvex 64-Real-Video Benchmark and Dual-Tier Neural VAR Chart" width="100%" />
</div>

<p align="center">
  🔬 <b>Want to inspect a real Redmi Note 9 session (including v17 Neural VAR &amp; Cable Pass tracks) frame-by-frame?</b>
  <a href="https://iganramdani.github.io/Jumpvex/#telemetry-lab">Open it live in the Jumpvex Web Telemetry Lab →</a>
</p>

---

## 🧠 AI Engine v17 Dual-Tier Neural VAR Architecture

```mermaid
flowchart LR
    CAM["📷 CameraX (30 FPS) / Full Video"] --> T1["⚡ Tier 1: BlazePose Fast + CausalJumpTcnLite\n(< 40 µs/frame Zero-GC Live)"]
    CAM --> T2["🔬 Tier 2: BlazePose Heavy 3D + OpticalRopeTracker\n(Sub-Foot & Overhead NV21 Cable Pass)"]
    MIC["🎙️ AudioRecord / 200 Hz Streaming PCM"] --> HPSS["🎵 StftAudioEngine + NeuralAudioRopeIsolator\n(17-Frame HPSS EDM Stripper + IJRU Horn)"]
    T1 --> FUS["🧠 FusionEngine + Bidirectional NeuralVarEngine\n(5-Fold MS-TCN • 99.44% across 64 Videos)"]
    T2 --> FUS
    HPSS --> FUS
    T2 --> IJRC["🏆 IjrcSpeedSprintEngine + RopeAngularVelocityEngine\n(Right-Foot Judge • Step vs Rope Curves • 5s Splits)"]
    HPSS --> IJRC
    FUS --> UI["🟢 1-Tap Live Cockpit • Sticky 4-Tab VAR Studio\n• rPPG HR • Media3 GPU HUD • Batch .ZIP Logs"]
    IJRC --> UI
```

---

## 🎛️ Interactive Feature Explorer (Click to Expand)

<details>
<summary><b>🚀 Quick Start &amp; Best Setup Guide (60 Seconds)</b></summary>
<br/>

1. **Prop your phone** 2–3 meters away so both your head and feet fit inside the frame *(check your exact room distance in the [Web Framing Calculator](https://iganramdani.github.io/Jumpvex/#physics-calc))*.
2. Check the **Smart Framing pill** at the top of the screen — once it turns Ice Mint (`Full body locked • Raise 1 hand to start`), you're perfectly aligned.
3. **Hands-Free Start:** Raise **one hand** for `1.2 seconds` (or enable **5m+ Double-Clap Start/Pause** in Settings) to start without walking back to your phone.
4. **Calibrate Your Profile:** Open **Settings (⚙️)** to set **Body Weight (`35–150 kg`)**, **Rope Type**, **100–420 SPM Audio Pacemaker**, **Ghost Rival**, or **Camera rPPG Heart Rate Recovery**.

</details>

<details>
<summary><b>🏋️ 7 Workout &amp; Official IJRU Competition Modes</b></summary>
<br/>

- **🔥 Free Mode:** Custom session name + custom audio milestone interval (`5 – 1,000` jumps).
- **🎯 Target Jumps:** Quick presets or any custom count (`1 – 99,999` jumps) synced to the dynamic ring gauge.
- **⏱️ Target Time:** Quick presets or custom **Minutes + Seconds** countdown timer.
- **⚡ HIIT Interval:** Custom **Work**, **Rest**, and **Rounds** with live total duration preview and automatic English TTS phase cues.
- **🕺 Shuffle & Tricks:** Counts both jump rope tricks and rhythmic Melbourne Shuffle / Ropeless Boxing steps synced to Music BPM.
- **🏆 IJRU 30s Sprint:** Official competition 30-second speed-sprint benchmark powered by 200 Hz bare-wire STFT + AC leg-drive tracking.
- **🏆 IJRU 3m Endurance:** Official competition 3-minute endurance test tracking sustained cadence, form stability, and trips.

</details>

<details>
<summary><b>🎬 4-Mode Video Studio, Velocity Charts &amp; Synced Slow-Mo</b></summary>
<br/>

- **4 Specialist Studio Modes:** Choose **Auto Hybrid**, **IJRC Speed**, **Freestyle**, or **Shuffle** for single or unlimited batch videos at full native 30/60 FPS.
- **Interactive Graphic Telemetry Charts:** Scrub across **Step Speed vs Rope Rotation Speed**, **Zoomed `±2.5s` Left/Right Leg-Drive & 200 Hz Whip Oscilloscope**, **5-Second Competition Splits**, and **IJRC Right-Foot Judge Score** bidirectionally synced to `0.2x / 0.25x / 0.5x / 1.0x` + `±1 Frame` video playback.
- **Dynamic Animated Video HUD Exporter:** Burn a live, frame-accurate counter, real-time RPM, streak, calories, and active trick callout onto your video via Media3 OpenGL texture reuse.

</details>

<details>
<summary><b>🛠️ Build from Source &amp; Run Unit Tests</b></summary>
<br/>

```bash
# Clone the official Jumpvex repository
git clone https://github.com/iganramdani/Jumpvex.git
cd Jumpvex

# Run all unit tests + 64-Real-Video Benchmark Suite (AI Engine v17 Dual-Tier VAR)
./gradlew testDebugUnitTest

# Build R8-minified, ARM-optimized Unified Offline Release APK (~52 MB)
./gradlew assembleRelease

# Build Debug APK (with debug symbols for development & profiling)
./gradlew assembleDebug
```

</details>

---

## 🗺️ Phone-Only Pro Roadmap (`v3.0` → `v7.0`)

**100% Smartphone-Native & 100% Offline** — zero smartwatch, zero smart rope, zero cloud required. Want to build with us? [Open an Issue or PR](CONTRIBUTING.md)!

- [x] **v3.0–v3.3:** Zero-GC `O(log N)` Vision + 100 Hz STFT Fusion, Heel-to-Toe Foot-Triangle, Shuffle Dance & Batch Video Studio
- [x] **v4.0 (`v4.0.1`):** AI Engine v14 Pro Studio — Touchless Hand-Raise Start/Pause, Smart Camera Framing, Peak-Aligned Trick Window (`±110 ms`), Triple Under, Dynamic Live Animated Video HUD, Official IJRU 30s/3m Modes, Athlete Profile & Rope Physics
- [x] **Interactive Repo & Web Lab:** Animated Dark/Light SVG Hero, Vector Benchmark Charts, YAML Issue Forms & **[Browser CSV Telemetry Oscilloscope](https://iganramdani.github.io/Jumpvex/)**
- [x] **v4.2–v5.0 — Phone-Only Pro Suite & Arena:** **Pocket 100 Hz IMU Mode**, **Rear-Camera + Torch rPPG Heart Rate & 60s HRR**, **5m+ Double-Clap Trigger**, **Virtual Force-Plate (`Height cm`, `GCT ms`, `RSI`)**, **Trip Root-Cause Autopsy**, **Ghost Rival Pacer**, **2-Player Split-Screen Battle**, **AI Skill Tree** & **SQLite WAL Heatmap**
- [x] **v6.0–v6.1 (`v6.1.1`):** ART Baseline Profiles, `ThermalGovernor`, `IjrcSpeedSprintEngine` (`200 Hz` steel-wire STFT + IJRU horn + Right-Foot Judge Score), `RopeAngularVelocityEngine`, sample-accurate `100–420 SPM` Pacemaker & live PPG rPPG
- [x] **v7.0–v7.1 (`v7.1.0` Current) — Dual-Tier Neural VAR Studio, Proportional Live HUD & High-Usability Polish:** Bundled **Heavy 3D Pose (`pose-detection-accurate`)** + edge-safe **Sub-Foot & Overhead Visual Rope Tracker** + **HPSS 200 Hz Audio Isolator** + **5-Fold Bidirectional MS-TCN (`var_bitemporal_v7.jvxmodel`) & Live `CausalJumpTcnLite`**, **Compact Expandable Sticky Slow-Mo Studio** (crisp 2-row speed bar, 1-chip collapsed mode, 4 non-truncating tabs, 1-tap `.ZIP` logs), **Proportional Live HUD Video Export across `9:16`, `16:9`, `1:1` & `4K`**, and **1-Command Android Termux AI Trainer**!

---

## 📱 Train with the Community (1-Command Android Termux)

Want to help make Jumpvex even sharper on your own skipping style, floor surface, or smartphone? You don't even need a PC — run our pure-NumPy 5-fold trainer directly inside **Termux** on Android:

```bash
# Inside Termux on your Android phone
termux-setup-storage
git clone https://github.com/iganramdani/Jumpvex.git
cd Jumpvex
bash training/termux_train.sh
```

Share your `.ZIP` diagnostic logs via [Submit Calibration CSV](https://github.com/iganramdani/Jumpvex/issues/new?template=calibration_contribution.yml) or open a PR with improved `.jvxmodel` weights!

---

## ❓ Interactive FAQ

<details>
<summary><b>Do I need a smartwatch, AirPods, or a smart jump rope?</b></summary>
<br/>
<b>No!</b> Jumpvex is <b>100% Phone-Only</b>. Your smartphone's built-in camera, microphone, rear LED torch (for rPPG heart rate), IMU sensors, and speaker do everything — zero wearables or paid hardware required.
</details>

<details>
<summary><b>Does Jumpvex upload my camera feed, videos, or CSV logs to the internet?</b></summary>
<br/>
<b>Never.</b> 100% of pose detection (both Fast &amp; Heavy 3D), Visual Rope Tracking, Neural VAR MS-TCN, HPSS audio DSP, trick classification, video rendering, and even our <a href="https://iganramdani.github.io/Jumpvex/">Web Telemetry Lab</a> runs strictly on your own device. Internet permission is used <i>only</i> for the optional GitHub Releases update check.
</details>

<details>
<summary><b>Will Jumpvex run smoothly on older / budget Android phones?</b></summary>
<br/>
Yes! Tier 1 Live Camera runs at real-time <b>30 FPS</b> with <code>CausalJumpTcnLite</code> (&lt; 40 µs/frame, <code>34 MB</code> median heap on a <b>Xiaomi Redmi Note 9, Android 12</b>), while Tier 2 Video Studio defaults to <b>Heavy 3D VAR</b> and includes a <b>1-Tap Fast 30fps</b> toggle for quick scans.
</details>

---

## 🙏 Open-Source Credits & Respect

**Jumpvex** stands on the shoulders of extraordinary open-source engineers, researchers, and jump rope athletes. Full license attributions are maintained in [`CREDITS.md`](CREDITS.md) — huge respect and gratitude to:

| Project / Organization | Role in Jumpvex | License |
| :--- | :--- | :--- |
| **[JetBrains & Kotlin Contributors](https://github.com/JetBrains/kotlin)** | Kotlin `2.0.21` language & `kotlinx.coroutines` structured concurrency | Apache-2.0 |
| **[Google AndroidX / Jetpack Team](https://github.com/androidx/androidx)** | Jetpack Compose, Material 3, CameraX `1.4.0`, Lifecycle & Core KTX | Apache-2.0 |
| **[AndroidX Media3 Team](https://github.com/androidx/media)** | Hardware-accelerated `Media3 Transformer` & `BitmapOverlay` OpenGL video export | Apache-2.0 |
| **[Google ML Kit & MediaPipe Team](https://developers.google.com/ml-kit/vision/pose-detection)** | Dual-Tier on-device 2D/3D BlazePose Fast & Heavy Accurate (`pose-detection-accurate`) | ML Kit Terms / Apache-2.0 |
| **[JUnit Team](https://github.com/junit-team/junit4)** | Unit & 64-video regression benchmark testing framework | EPL-1.0 |
| **[Gradle & Android Gradle Plugin Teams](https://github.com/gradle/gradle)** | Build automation, R8 code shrinking & resource optimization | Apache-2.0 |
| **DSP & Sports Science Standards** | Cooley–Tukey FFT, HPSS (Fitzgerald), Dilated MS-TCN, ACSM MET, & [IJRU](https://ijru.sport) competition rules | Public Standards |
| **Jump Rope & Footwork Community** | Athletes & creators including `@D.LEE___1` (IJRC 30s stage sprint), `@cenxiaolin1` (competition speed-sprint), `@alwaysghadi` (boxing footwork), and the `r/jumprope` & Melbourne Shuffle communities | Community Credit |

---

## 📦 Download APK (Release & Debug)

<div align="center">

[![Download Release APK](https://img.shields.io/badge/⬇️_Download_v7.1.0_Release_APK_(52_MB)-2EE59D?style=for-the-badge&logo=android&logoColor=0D1321)](https://github.com/iganramdani/Jumpvex/releases/download/v7.1.0/Jumpvex-v7.1.0-release.apk)
&nbsp;
[![Download Debug APK](https://img.shields.io/badge/🛠️_Download_v7.1.0_Debug_APK-151D2E?style=for-the-badge&logo=android&logoColor=2EE59D)](https://github.com/iganramdani/Jumpvex/releases/download/v7.1.0/Jumpvex-v7.1.0-debug.apk)

<sub>Android 7.0+ (API 24+) • 100% On-Device Offline • Free &amp; Open-Source • <a href="https://github.com/iganramdani/Jumpvex/releases/tag/v7.1.0">View Full Release Notes &amp; SHA-256 Checksums</a></sub>

</div>

---

## ⚖️ Open-Source License, Copyright & Anti-Plagiarism

**Jumpvex** is released as **Free and Open-Source Software** under the strict **[GNU General Public License v3.0 (GPL-3.0)](LICENSE)** so everyone worldwide can benefit, learn, and contribute — **while firmly protecting original authorship against closed-source plagiarism**:

> **Copyright © 2026 Igan Ramdani ([`@iganramdani`](https://github.com/iganramdani)). All rights reserved under GNU GPL v3.0.**

- ✅ **Allowed:** Free use, study, modification, and community Pull Requests.
- 📜 **Attribution Required:** Any copy or fork **must preserve the original copyright notice** ([`NOTICE`](NOTICE)) and [`CREDITS.md`](CREDITS.md), and clearly document all modifications.
- 🔒 **Anti-Plagiarism (*Strong Copyleft*):** Repackaging or monetizing **Jumpvex** (including AI Engine v17 Neural VAR, `.jvxmodel` weights, STFT/HPSS DSP, or UI) as a closed-source / proprietary app is strictly prohibited. All derivative works **must remain open-source under the same GPL-3.0 license**.
- 🛡️ **Brand Protection:** The **Jumpvex** name and official visual identity belong to the original author; unofficial forks must use a distinct app name and package ID.
