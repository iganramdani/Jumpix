<div align="center">

<img src="docs/assets/jumpvex_logo.png" alt="Jumpvex Logo" width="128" height="128" />

# Jumpvex

### 🎯 **"99% Accuracy & ⚡ 99% Lightweight"**

**Open-Source On-Device AI Jump Rope, Trick & Shuffle Footwork Counter for Android — Zero Cloud, Zero Subscriptions, Built-In Live Animated Video HUD & Touchless Gym Control!**

[![Release](https://img.shields.io/badge/Release-v4.0.0_(46_MB)-2EE59D?style=for-the-badge&logo=android&logoColor=0D1321)](https://github.com/iganramdani/Jumpvex/releases/tag/v4.0.0)
[![GitHub Downloads](https://img.shields.io/github/downloads/iganramdani/Jumpvex/total?style=for-the-badge&color=2EE59D&labelColor=151D2E&logo=github)](https://github.com/iganramdani/Jumpvex/releases)
[![GitHub Stars](https://img.shields.io/github/stars/iganramdani/Jumpvex?style=for-the-badge&color=2EE59D&labelColor=151D2E&logo=github)](https://github.com/iganramdani/Jumpvex/stargazers)
[![Issues](https://img.shields.io/github/issues/iganramdani/Jumpvex?style=for-the-badge&color=38BDF8&labelColor=151D2E)](https://github.com/iganramdani/Jumpvex/issues)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-2EE59D?style=for-the-badge&logo=gnu&logoColor=0D1321)](LICENSE)

<br/>

[✨ Philosophy](#-core-philosophy-99-accuracy--99-lightweight) •
[🆚 Comparison](#-how-jumpvex-compares) •
[📱 UI Gallery](#-modern-two-tone-ui-gallery) •
[📊 Benchmarks](#-real-device--4-smartphone-benchmarks) •
[🧠 AI Engine v14](#-ai-engine-v14-pro-studio-architecture) •
[🗺️ Roadmap](#️-interactive-roadmap) •
[❓ FAQ](#-interactive-faq) •
[🙏 Credits](#-open-source-credits--respect) •
[📦 Download](#-download-apk-release--debug)

<br/>

> 💡 **Found a bug, want a new trick, or tested Jumpvex on your phone?**
> [🐛 Report a Bug](https://github.com/iganramdani/Jumpvex/issues/new?template=bug_report.md) &nbsp;•&nbsp;
> [✨ Request a Feature / Trick](https://github.com/iganramdani/Jumpvex/issues/new?template=feature_request.md) &nbsp;•&nbsp;
> [🤝 Contributing Guide](CONTRIBUTING.md)

</div>

---

## ✨ Core Philosophy: "99% Accuracy & 99% Lightweight"

Every line of code, UI component, and DSP algorithm in **Jumpvex** is engineered around one strict principle:

| 🎯 99% Accuracy (Competition-Grade Precision) | ⚡ 99% Lightweight (Blazing Fast on Any Phone) |
| :--- | :--- |
| 🧠 **AI Engine v14.0 + 100 Hz 5-Band STFT Hybrid** — fuses full-body 2D/3D + **Heel-to-Toe Foot-Triangle** kinematics with anti-aliased streaming 100 Hz acoustic rope-slap & `60–250 Hz` kick-drum beat analysis. | 🪶 **100% On-Device, Zero Cloud** — no servers, no accounts, no subscriptions, full privacy, and near-zero latency. |
| 🕺 **Peak-Aligned Trick, Triple Under & Shuffle Coach** — detects **Regular, Boxer Step, Heel-Toe, Criss-Cross, Double Under, Triple Under, High Knees, Side Swing**, plus camera-angle-aware **Melbourne Shuffle** (*Running Man, T-Step, Charleston*), **Combo Chains**, & **3D Posture Form Coach**. | ⚡ **True Zero-GC `O(log N)` Hot Path (`64 µs` on Redmi Note 9)** — pre-allocated FFT/kinematic buffers, `O(1)` streaming video audio STFT, atomic crash-safe history, and isolated `PoseOverlay` recomposition. |
| ✋ **Touchless Gym UX & Official IJRU Modes** — raise 1 hand for 1.2s from 2–4m to Start/Pause hands-free, guided by **Smart Foot-in-Frame Assistant**, plus **IJRU 30s Speed Sprint** & **3m Endurance**. | 🎬 **Live Animated Video HUD Exporter + 46 MB Release APK** — exports videos with real-time incrementing jump counter, live RPM & trick callouts via Media3 GPU textures while staying ~46 MB. |

---

## 🆚 How Jumpvex Compares

| Capability | **Jumpvex v4.0** | YaoYao | Crossrope AMP | JRC AI | JustJump |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Phone-Only (Zero Extra Hardware)** | ✅ **98.9% Avg** | ❌ Watch / AirPods | ❌ \$100+ Handles | ✅ Camera only | ❌ Manual |
| **Hybrid Vision + 100 Hz Audio PLL** | ✅ **Unique** | ❌ IMU only | ❌ Hall sensor | ❌ Vision only | ❌ |
| **Budget Phone Accuracy (10–15 FPS)** | ✅ **98.3%+** | N/A | N/A | ❌ Drops fast | N/A |
| **Trick & Shuffle Dance Classifier** | ✅ **11 Moves + Combos** | ⚠️ Double Under | ❌ | ❌ | ❌ Catalog only |
| **Touchless Hand-Raise Start/Pause** | ✅ **1.2s Single Hand** | ✅ Watch/Voice | ❌ | ✅ Raise Hand | ❌ |
| **Live Animated Counter Video Export** | ✅ **Media3 GPU HUD** | ❌ | ❌ | ❌ | ❌ |
| **Official IJRU 30s / 3m Modes** | ✅ **Built-In** | ⚠️ Basic | ✅ Guided | ✅ Sprint | ✅ Drills |
| **Price, Privacy & Open-Source** | ✅ **100% Free GPLv3** | 💰 Paid | 💰 Subscription | 💰 Freemium | 💰 Freemium |

---

## 📱 Modern Two-Tone UI Gallery

Crafted with a clean futuristic two-tone palette — **Deep Midnight (`#0D1321`)** + **Ice Mint (`#2EE59D`)** — and a clutter-free **3-Tab Navigation** (*Workout • Video • History*):

<div align="center">
<table>
  <tr>
    <td align="center" width="33%">
      <img src="docs/assets/ui_live_ring_hud.jpg" width="240" alt="Jumpvex Dynamic Ring HUD"/><br/>
      <b>🟢 Tab 1: Dynamic Ring HUD</b><br/>
      <sub>Animated Ice Mint ring, Touchless Hand-Raise Start/Pause, Smart Framing Assistant, Live Trick & Form Coach pill</sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/assets/ui_video_studio.jpg" width="240" alt="Jumpvex Video Studio"/><br/>
      <b>🎬 Tab 2: Multi-Video Studio</b><br/>
      <sub>Jump Rope & Shuffle toggle, unlimited batch queue, Live Animated Counter Video HUD export & auto-saved CSV logs</sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/assets/ui_history_stats.jpg" width="240" alt="Jumpvex History & Weekly Stats"/><br/>
      <b>📈 Tab 3: History, PRs & Daily Goal</b><br/>
      <sub>Daily goal bar, active day streak, Personal Records trophy strip, 7-day chart, trick breakdown & 1-tap CSV backup</sub>
    </td>
  </tr>
</table>
</div>

---

## 📊 Real-Device & 4-Smartphone Benchmarks

### 1. Ground-Truth Jump Counter Benchmark (46 Videos • 2,212 Verified Jumps)
Continuously verified using the production Kotlin engine ([`RealVideosBenchmarkTest.kt`](app/src/test/java/com/jumpropeai/benchmark/RealVideosBenchmarkTest.kt)) across **46 real-world jump rope videos** ([`ground_truth_all_videos.csv`](ground_truth_all_videos.csv)) simulated across 4 hardware tiers:

| 📱 Smartphone Tier | 🎯 Detected / Ground Truth | 📏 Volume Accuracy | Visual Bar | Status |
| :--- | :---: | :---: | :--- | :---: |
| **1. Flagship** `(30 FPS, 0px, 0% drop)` | `2,179 / 2,212` | **98.51%** | `██████████` | ✅ Pass |
| **2. Mid-Range** `(20 FPS, ±2px, 2% drop)` | `2,197 / 2,212` | **99.32%** | `██████████` | ✅ Pass |
| **3. Budget** `(15 FPS, ±4px, 5% drop)` | `2,227 / 2,212` | **99.32%** | `██████████` | ✅ Pass |
| **4. Low-End** `(10 FPS, ±6px, 10% drop)` | `2,174 / 2,212` | **98.28%** | `██████████` | ✅ Pass |

### 2. Real Budget-Phone Stress Test (`Xiaomi Redmi Note 9`, Android 12)
Measured directly from 21 consecutive batch videos (`11,042 frames`) using Jumpvex's built-in **Unlimited CSV Diagnostic Logger**:
- **AI Engine CPU Time:** `64 µs` median (`0.064 ms/frame`), `195 µs` p95
- **JVM Heap Memory:** `26 MB` median (`60 MB` peak across 21 consecutive videos, zero OOM)
- **Release APK Size:** `46 MB` (R8 minified + ARM-only native libraries)

---

## 🧠 AI Engine v14 Pro Studio Architecture

```mermaid
flowchart LR
    CAM["📷 CameraX (360×480 Fast Pose)"] --> ML["🦴 ML Kit 2D/3D Pose + Foot Triangle"]
    MIC["🎙️ AudioRecord / O(1) Streaming PCM"] --> STFT["🎵 Zero-GC 5-Band STFT (100 Hz)\n• 60–250Hz Bass Kick + 2.5–7kHz Whip"]
    ML --> TG["✋ Touchless Gesture & Smart Framing\n• 1.15s Single-Hand Start/Pause\n• Feet-in-Frame & Centering Guard"]
    ML --> JD["⚡ JumpDetector v14 (Zero-GC)\n• 420ms Time-Based Airborne\n• Coherence, Symmetry & Sprint ZC"]
    STFT --> FE["🧠 FusionEngine v14 (O(log N))\n• Self-Learning 3-Band Acoustic\n• Nyquist-Aware Low-FPS PLL"]
    JD --> FE
    ML --> KT["🕺 KineticTrickCoach v14\n• Peak-Aligned ±110ms Trick Window\n• Triple/Double Under & Combos\n• 3D Posture Form Coach (0–100%)"]
    STFT --> BS["🎧 Unbiased BeatSyncEngine v14\n• Kick-Drum BPM (80–176 BPM)\n• Lookahead-Compensated Sync %"]
    FE --> UI["🟢 Ring HUD, Live Animated Video HUD\n& Atomic Rich History + PRs"]
    KT --> UI
    BS --> UI
    TG --> UI
```

---

## 🎛️ Interactive Feature Explorer (Click to Expand)

<details>
<summary><b>🚀 Quick Start & Best Setup Guide (60 Seconds)</b></summary>
<br/>

1. **Prop your phone** 2–3 meters away so both your head and feet fit inside the frame.
2. Check the **Smart Framing pill** at the top of the screen — once it turns Ice Mint (`Full body locked • Raise 1 hand to start`), you're perfectly aligned.
3. **Hands-Free Start:** Raise **one hand** clearly above your head for `1.2 seconds` to start the countdown without walking back to your phone.
4. **Calibrate Your Profile:** Open **Settings (⚙️)** to slide your exact **Body Weight (`35–150 kg`)**, pick your **Rope Type** (*Speed Cable, PVC, Beaded, Weighted 0.5/1.0 lb, Cordless*), and set your **Daily Jump Goal**.

</details>

<details>
<summary><b>🏋️ 7 Workout & Official IJRU Competition Modes</b></summary>
<br/>

- **🔥 Free Mode:** Custom session name + custom audio milestone interval (`5 – 1,000` jumps).
- **🎯 Target Jumps:** Quick presets or any custom count (`1 – 99,999` jumps) synced to the dynamic ring gauge.
- **⏱️ Target Time:** Quick presets or custom **Minutes + Seconds** countdown timer.
- **⚡ HIIT Interval:** Custom **Work**, **Rest**, and **Rounds** with live total duration preview and automatic English TTS phase cues.
- **🕺 Shuffle & Tricks:** Counts both jump rope tricks and rhythmic Melbourne Shuffle steps (*Running Man, T-Step, Charleston*) synced to Music BPM.
- **🏆 IJRU 30s Sprint:** Official competition 30-second speed-sprint benchmark powered by 100 Hz acoustic + ankle tracking.
- **🏆 IJRU 3m Endurance:** Official competition 3-minute endurance test tracking sustained cadence, form stability, and trips.

</details>

<details>
<summary><b>🎬 Multi-Video Batch Studio & Live Animated Counter Video HUD</b></summary>
<br/>

- **Dynamic Animated Video HUD Exporter:** Burn a live, frame-accurate jump counter, real-time RPM, streak, calories, and active trick callout onto your workout video via Media3 OpenGL texture reuse — ready for Reels, Shorts & TikTok!
- **Jump Rope & Shuffle Modes + Multi-Video Queue:** Select 1 or unlimited videos at once (`OpenMultipleDocuments`) with O(1)-RAM streaming anti-aliased audio STFT so even multi-hour videos never OOM.
- **Unlimited Auto-Save Debug Telemetry:** Streams frame-by-frame CSV telemetry in background IO directly to **`Documents/Jumpvex/DebugLogs/`**.

</details>

<details>
<summary><b>🛠️ Build from Source & Run Unit Tests</b></summary>
<br/>

```bash
# Clone the official Jumpvex repository
git clone https://github.com/iganramdani/Jumpvex.git
cd Jumpvex

# Run all 27 unit tests (AI Engine v14, Touchless Gestures, Streaming STFT, Fusion, BeatSync, IJRU Modes)
./gradlew testDebugUnitTest

# Build R8-minified, ARM-optimized Release APK (~46 MB)
./gradlew assembleRelease

# Build Debug APK (with debug symbols for development & profiling)
./gradlew assembleDebug
```

</details>

---

## 🗺️ Interactive Roadmap

Want to help build one of the upcoming items? [Open an Issue or PR](CONTRIBUTING.md)!

- [x] **v3.0–v3.3:** Zero-GC `O(log N)` Vision + 100 Hz STFT Fusion, Heel-to-Toe Foot-Triangle, Shuffle Dance & Batch Video Studio
- [x] **v4.0 (Current):** AI Engine v14 Pro Studio — Touchless Hand-Raise Start/Pause, Smart Camera Framing, Peak-Aligned Trick Window (`±110 ms`), Triple Under, Dynamic Live Animated Video HUD, Official IJRU 30s/3m Modes, Athlete Profile & Rope Physics, Atomic Rich History + PRs + CSV Backup
- [ ] **v4.1:** Direct Live Workout Video Recording (`CameraX VideoCapture` + Live HUD Overlay)
- [ ] **v4.2:** Built-In Target RPM Audio Pacemaker Metronome (`120–220 BPM`) & Expanded Tricks (*EB Cross, Toad, Backward Skipping*)
- [ ] **v5.0:** Android Health Connect Sync, Direct BLE Heart Rate Strap (`GATT 0x180D`) & AI-Verified Interactive Skill Academy

---

## ❓ Interactive FAQ

<details>
<summary><b>Does Jumpvex upload my camera feed or videos to the internet?</b></summary>
<br/>
<b>Never.</b> 100% of pose detection, STFT audio DSP, trick classification, and video rendering runs strictly on your Android device. Internet permission is used <i>only</i> for the optional GitHub Releases update check.
</details>

<details>
<summary><b>Will Jumpvex run smoothly on older / budget Android phones?</b></summary>
<br/>
Yes! Jumpvex is specifically calibrated and stress-tested on a <b>Xiaomi Redmi Note 9 (Android 12)</b>, averaging <code>64 µs/frame</code> CPU time and <code>26 MB</code> heap memory. Even at 10–15 FPS, our Nyquist-aware Audio PLL maintains <b>98.3%+</b> accuracy.
</details>

<details>
<summary><b>How do I help improve Trick or Shuffle accuracy on my device?</b></summary>
<br/>
Enable <b>AI Performance & Full CSV Log</b> in Settings (or analyze a clip in Video Studio). Jumpvex automatically saves a lightweight telemetry CSV to <code>Documents/Jumpvex/DebugLogs/</code> — attach it to a <a href="https://github.com/iganramdani/Jumpvex/issues/new?template=bug_report.md">GitHub Bug Report</a> and we can calibrate the exact kinematics!
</details>

---

## 🙏 Open-Source Credits & Respect

**Jumpvex** stands on the shoulders of extraordinary open-source engineers, researchers, and jump rope athletes. Full license attributions are maintained in [`CREDITS.md`](CREDITS.md) — huge respect and gratitude to:

| Project / Organization | Role in Jumpvex | License |
| :--- | :--- | :--- |
| **[JetBrains & Kotlin Contributors](https://github.com/JetBrains/kotlin)** | Kotlin `2.0.21` language & `kotlinx.coroutines` structured concurrency | Apache-2.0 |
| **[Google AndroidX / Jetpack Team](https://github.com/androidx/androidx)** | Jetpack Compose, Material 3, CameraX `1.4.0`, Lifecycle & Core KTX | Apache-2.0 |
| **[AndroidX Media3 Team](https://github.com/androidx/media)** | Hardware-accelerated `Media3 Transformer` & `BitmapOverlay` OpenGL video export | Apache-2.0 |
| **[Google ML Kit & MediaPipe Team](https://developers.google.com/ml-kit/vision/pose-detection)** | Real-time on-device 2D/3D BlazePose landmark inference (`pose-detection`) | ML Kit Terms / Apache-2.0 |
| **[JUnit Team](https://github.com/junit-team/junit4)** | Unit & regression benchmark testing framework | EPL-1.0 |
| **[Gradle & Android Gradle Plugin Teams](https://github.com/gradle/gradle)** | Build automation, R8 code shrinking & resource optimization | Apache-2.0 |
| **DSP & Sports Science Standards** | Cooley–Tukey Radix-2 FFT, ACSM Compendium of Physical Activities (MET), & [IJRU](https://ijru.sport) official competition rules | Public Standards |
| **Jump Rope & Footwork Community** | Athletes & creators including `@cenxiaolin1` (competition speed-sprint), `@alwaysghadi` (boxing footwork), and the `r/jumprope` & Melbourne Shuffle communities whose public demonstrations enabled real-world calibration | Community Credit |

---

## 📦 Download APK (Release & Debug)

<div align="center">

[![Download Release APK](https://img.shields.io/badge/⬇️_Download_v4.0.0_Release_APK_(46_MB)-2EE59D?style=for-the-badge&logo=android&logoColor=0D1321)](https://github.com/iganramdani/Jumpvex/releases/download/v4.0.0/Jumpvex-v4.0.0-release.apk)
&nbsp;
[![Download Debug APK](https://img.shields.io/badge/🛠️_Download_v4.0.0_Debug_APK-151D2E?style=for-the-badge&logo=android&logoColor=2EE59D)](https://github.com/iganramdani/Jumpvex/releases/download/v4.0.0/Jumpvex-v4.0.0-debug.apk)

<sub>Android 7.0+ (API 24+) • 100% On-Device • Free & Open-Source • <a href="https://github.com/iganramdani/Jumpvex/releases/tag/v4.0.0">View Full Release Notes</a></sub>

</div>

---

## ⚖️ Open-Source License, Copyright & Anti-Plagiarism

**Jumpvex** is released as **Free and Open-Source Software** under the strict **[GNU General Public License v3.0 (GPL-3.0)](LICENSE)** so everyone worldwide can benefit, learn, and contribute — **while firmly protecting original authorship against closed-source plagiarism**:

> **Copyright © 2026 Igan Ramdani ([`@iganramdani`](https://github.com/iganramdani)). All rights reserved under GNU GPL v3.0.**

- ✅ **Allowed:** Free use, study, modification, and community Pull Requests.
- 📜 **Attribution Required:** Any copy or fork **must preserve the original copyright notice** ([`NOTICE`](NOTICE)) and [`CREDITS.md`](CREDITS.md), and clearly document all modifications.
- 🔒 **Anti-Plagiarism (*Strong Copyleft*):** Repackaging or monetizing **Jumpvex** (including AI Engine v14, STFT DSP, or UI) as a closed-source / proprietary app is strictly prohibited. All derivative works **must remain open-source under the same GPL-3.0 license**.
- 🛡️ **Brand Protection:** The **Jumpvex** name and official visual identity belong to the original author; unofficial forks must use a distinct app name and package ID.
