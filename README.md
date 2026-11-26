<div align="center">

<a href="https://iganramdani.github.io/Jumpvex/">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/hero_banner_dark.svg" />
    <source media="(prefers-color-scheme: light)" srcset="docs/assets/hero_banner_light.svg" />
    <img alt="Jumpvex — 99% Accuracy &amp; 99% Lightweight Phone-Only AI Jump Rope &amp; Shuffle Engine" src="docs/assets/hero_banner_dark.svg" width="100%" />
  </picture>
</a>

<br/><br/>

[![Launch Web Telemetry Lab](https://img.shields.io/badge/🔬_Launch_Web_Telemetry_Lab-2EE59D?style=for-the-badge&logoColor=0D1321)](https://iganramdani.github.io/Jumpvex/)
[![Download Release APK](https://img.shields.io/badge/⬇️_Download_v6.1.0_APK_(46_MB)-38BDF8?style=for-the-badge&logo=android&logoColor=0D1321)](https://github.com/iganramdani/Jumpvex/releases/download/v6.1.0/Jumpvex-v6.1.0-release.apk)
[![GitHub Downloads](https://img.shields.io/github/downloads/iganramdani/Jumpvex/total?style=for-the-badge&color=2EE59D&labelColor=151D2E&logo=github)](https://github.com/iganramdani/Jumpvex/releases)
[![GitHub Stars](https://img.shields.io/github/stars/iganramdani/Jumpvex?style=for-the-badge&color=2EE59D&labelColor=151D2E&logo=github)](https://github.com/iganramdani/Jumpvex/stargazers)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-2EE59D?style=for-the-badge&logo=gnu&logoColor=0D1321)](LICENSE)

<br/>

[🔬 Web Telemetry Lab](https://iganramdani.github.io/Jumpvex/) •
[✨ Philosophy](#-core-philosophy-99-accuracy--99-lightweight) •
[🆚 Phone-Only vs \$400 Gear](#-how-jumpvex-compares-100-phone-only) •
[📱 UI Gallery](#-modern-two-tone-ui-gallery) •
[📊 64-Video Benchmark](#-official-64-real-video-benchmark) •
[🧠 Architecture](#-ai-engine-v16-ijrc--telemetry-architecture) •
[🗺️ Brutal Roadmap](#️-phone-only-pro-roadmap-v41--v61) •
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

## ✨ Core Philosophy: "99% Accuracy & 99% Lightweight"

**Jumpvex** is built on one relentless engineering principle: **beat \$120–\$500 smartwatches and smart ropes using only the Android smartphone already in your pocket** — zero watch, zero smart rope, zero cloud, zero subscriptions.

| 🎯 99% Accuracy (Competition-Grade Precision) | ⚡ 99% Lightweight (Blazing Fast on Any Phone) |
| :--- | :--- |
| 🧠 **AI Engine v16 + 200 Hz 5-Band STFT Hybrid** — fuses 2D/3D **Heel-to-Toe Foot-Triangle** kinematics, **AC-Centered IJRC Leg-Drive Oscillator**, and `200 Hz` (`5 ms` hop) bare-steel-wire (`2.5–6.5 kHz`) + IJRU start/finish horn detection (`99.42%` across 64 real videos). | 🪶 **100% On-Device, Zero Cloud, Zero Watch** — no servers, no wearables, no accounts, full privacy ([`SECURITY.md`](SECURITY.md)), and near-zero latency. |
| 📈 **Interactive Velocity Charts & Synced `0.2x` Slow-Mo** — inspect **Step Speed (SPS/SPM) vs Rope Rotation Speed (RPS/RPM)**, **Left/Right Leg-Drive & 200 Hz Whip Oscilloscope**, **5s Competition Splits**, and **Official IJRU Right-Foot Judge Score** bidirectionally synced to frame-accurate slow-motion video. | ⚡ **True Zero-GC `O(log N)` Hot Path (`18.5 µs/frame` Engine)** — pre-allocated FFT/kinematic circular buffers, `O(1)` streaming video audio STFT, native SQLite WAL history, and `64 µs` median on real budget **Xiaomi Redmi Note 9**. |
| ✋ **Omni-Sensor Phone-Only Pro Suite** — **Touchless Hand-Raise (`1.2s`)**, **5m+ Double-Clap Start/Pause**, **Pocket 100 Hz IMU Mode**, **Rear-Camera + Torch rPPG Heart Rate & 60s HRR**, **100–420 SPM Sample-Accurate Pacemaker**, **Ghost Rival**, & **2-Player Split-Screen Battle**. | 🎬 **Full 30/60 FPS Video Studio + 46 MB Release APK** — analyzes single or batch videos at native frame rate without throttling, exports Reels/TikTok/Shorts with live animated HUD via Media3 OpenGL texture reuse, and stays `~46 MB`. |

---

## 🆚 How Jumpvex Compares (100% Phone-Only)

| Capability | **Jumpvex (`v6.1.0` Pro)** | YaoYao | Crossrope AMP | JRC AI | JustJump |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **100% Phone-Only (Zero Watch / Zero Smart Rope)** | ✅ **99.42% (64 Videos)** | ❌ Watch / AirPods | ❌ \$119+ Handles | ✅ Camera only | ❌ Manual |
| **IJRC 200 Hz Steel-Wire + Side-Profile Speed Engine** | ✅ **Right-Foot & Total** | ❌ IMU only | ❌ Hall sensor | ⚠️ Front only | ❌ |
| **Step Speed vs Rope Speed Charts + `0.2x` Slow-Mo** | ✅ **Synced Scrubbing** | ❌ | ❌ | ❌ | ❌ |
| **Trick & Shuffle Dance Classifier** | ✅ **20 Moves + Combos** | ⚠️ Double Under | ❌ | ❌ | ❌ Catalog only |
| **Hands-Free Control (Hand-Raise + 5m Double-Clap)** | ✅ **Dual Touchless** | ✅ Watch/Voice | ❌ | ✅ Raise Hand | ❌ |
| **Rear-Lens + Torch rPPG Heart Rate & HRR-60** | ✅ **Live PPG Chart** | ❌ Requires Watch | ❌ | ❌ | ❌ |
| **Live Animated Counter Video Export** | ✅ **Media3 GPU HUD** | ❌ | ❌ | ❌ | ❌ |
| **Browser CSV Telemetry Oscilloscope** | ✅ **[Web Lab](https://iganramdani.github.io/Jumpvex/)** | ❌ | ❌ | ❌ | ❌ |
| **Price, Privacy & Open-Source** | ✅ **100% Free GPLv3** | 💰 Paid | 💰 \$119/yr | 💰 Freemium | 💰 Freemium |

---

## 📱 Modern Two-Tone UI Gallery

Crafted with a clean futuristic two-tone palette — **Deep Midnight (`#0D1321`)** + **Ice Mint (`#2EE59D`)** — and a clutter-free **3-Tab Navigation** (*Workout • Video • History*):

<div align="center">
<table>
  <tr>
    <td align="center" width="33%">
      <img src="docs/assets/ui_live_ring_hud.jpg" width="240" alt="Jumpvex Dynamic Ring HUD"/><br/>
      <b>🟢 Tab 1: Dynamic Ring HUD</b><br/>
      <sub>Animated Ice Mint ring, Hand-Raise &amp; Double-Clap control, Ghost Rival, Plyometrics (Height/GCT/RSI) &amp; Pocket IMU</sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/assets/ui_video_studio.jpg" width="240" alt="Jumpvex Video Studio"/><br/>
      <b>🎬 Tab 2: IJRC &amp; Telemetry Video Studio</b><br/>
      <sub>4-Mode AI Selector, Synced 0.2x Slow-Mo Scrubber, Step vs Rope Speed Charts, IJRC Judge Score &amp; Live HUD Export</sub>
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

Continuously verified by [`RealVideosBenchmarkTest.kt`](app/src/test/java/com/jumpropeai/benchmark/RealVideosBenchmarkTest.kt) directly against **64 real-world videos** (`JumpRope Test` dataset + official IJRC 30s Stage Speed Sprint in [`ground_truth_all_videos.csv`](ground_truth_all_videos.csv), **`3,767` human-verified jumps/steps**, zero synthetic smartphone-tier simulations) and stress-tested on real budget hardware (**Xiaomi Redmi Note 9, Android 12**):

<div align="center">
  <img src="docs/assets/real_64_video_benchmark.svg" alt="Jumpvex 64-Real-Video Benchmark and IJRC Speed Telemetry Chart" width="100%" />
</div>

<p align="center">
  🔬 <b>Want to inspect a real Redmi Note 9 session frame-by-frame?</b>
  <a href="https://iganramdani.github.io/Jumpvex/#telemetry-lab">Open it live in the Jumpvex Web Telemetry Lab →</a>
</p>

---

## 🧠 AI Engine v16 IJRC & Telemetry Architecture

```mermaid
flowchart LR
    CAM["📷 CameraX / Full 30-60 FPS Video"] --> ML["🦴 ML Kit 2D/3D Pose + Foot Triangle"]
    MIC["🎙️ AudioRecord / 200 Hz Streaming PCM"] --> STFT["🎵 Zero-GC 5-Band STFT (100/200 Hz)\n• 2.5–6.5kHz Wire Whip + IJRU Horn"]
    ML --> JD["⚡ JumpDetector v16 + FusionEngine\n• Symmetric 265ms Lookahead\n• Single-Beat Dance/Trick Audio PLL"]
    STFT --> JD
    ML --> IJRC["🏆 IjrcSpeedSprintEngine v16\n• AC-Centered Leg Oscillator\n• Official Right-Foot & Total Steps"]
    STFT --> IJRC
    ML --> RV["📈 RopeAngularVelocityEngine\n• Step SPS vs Rope RPS Curves\n• 5s Splits & Fatigue Decay %"]
    STFT --> RV
    JD --> UI["🟢 Live HUD, Synced 0.2x Slow-Mo Charts,\nCamera rPPG HR & Animated Video Export"]
    IJRC --> UI
    RV --> UI
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

# Run all unit tests + 64-Real-Video Benchmark Suite (AI Engine v16)
./gradlew testDebugUnitTest

# Build R8-minified, ARM-optimized Release APK (~46 MB)
./gradlew assembleRelease

# Build Debug APK (with debug symbols for development & profiling)
./gradlew assembleDebug
```

</details>

---

## 🗺️ Phone-Only Pro Roadmap (`v4.1` → `v6.1`)

**100% Smartphone-Native** — every milestone runs on a budget Android phone (`< 50 MB` APK, Zero-GC) with **zero smartwatch and zero smart rope required**. Want to build with us? [Open an Issue or PR](CONTRIBUTING.md)!

- [x] **v3.0–v3.3:** Zero-GC `O(log N)` Vision + 100 Hz STFT Fusion, Heel-to-Toe Foot-Triangle, Shuffle Dance & Batch Video Studio
- [x] **v4.0 (`v4.0.1`):** AI Engine v14 Pro Studio — Touchless Hand-Raise Start/Pause, Smart Camera Framing, Peak-Aligned Trick Window (`±110 ms`), Triple Under, Dynamic Live Animated Video HUD, Official IJRU 30s/3m Modes, Athlete Profile & Rope Physics
- [x] **Interactive Repo & Web Lab:** Animated Dark/Light SVG Hero, Vector Benchmark Charts, YAML Issue Forms & **[Browser CSV Telemetry Oscilloscope](https://iganramdani.github.io/Jumpvex/)**
- [x] **v4.2–v5.0 — Phone-Only Pro Suite & Arena:** **Pocket 100 Hz IMU Mode**, **Rear-Camera + Torch rPPG Heart Rate & 60s HRR**, **5m+ Double-Clap Trigger**, **Virtual Force-Plate (`Height cm`, `GCT ms`, `RSI`)**, **Trip Root-Cause Autopsy**, **Ghost Rival Pacer**, **2-Player Split-Screen Battle**, **AI Skill Tree** & **SQLite WAL Heatmap**
- [x] **v6.0:** ART Baseline Profile Rules (`baseline-prof.txt`) & Pre-Throttling Thermal Governor (`ThermalGovernor`)
- [x] **v6.1 (`v6.1.0` Current) — AI Engine v16 IJRC Speed & Telemetry Studio:** Full 30/60 FPS Video Studio, `IjrcSpeedSprintEngine` (`200 Hz` steel-wire STFT +IJRU horn + Right-Foot Judge Score), `RopeAngularVelocityEngine` (**Step Speed vs Rope Speed** charts + `0.2x` synced slow-mo scrubber), sample-accurate `100–420 SPM` Pacemaker, live PPG waveform rPPG, and **64-Real-Video Benchmark (`99.42%` VolAcc @ `18.5 µs/frame`)**!

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
<b>Never.</b> 100% of pose detection, STFT audio DSP, trick classification, video rendering, and even our <a href="https://iganramdani.github.io/Jumpvex/">Web Telemetry Lab</a> runs strictly on your own device. Internet permission is used <i>only</i> for the optional GitHub Releases update check.
</details>

<details>
<summary><b>Will Jumpvex run smoothly on older / budget Android phones?</b></summary>
<br/>
Yes! Jumpvex is calibrated on a <b>Xiaomi Redmi Note 9 (Android 12)</b> (`64 µs/frame` on-device median, `18.5 µs/frame` desktop benchmark, `26 MB` heap) and verified across <b>64 real-world videos (99.42% volume accuracy)</b>.
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
| **[JUnit Team](https://github.com/junit-team/junit4)** | Unit & 64-video regression benchmark testing framework | EPL-1.0 |
| **[Gradle & Android Gradle Plugin Teams](https://github.com/gradle/gradle)** | Build automation, R8 code shrinking & resource optimization | Apache-2.0 |
| **DSP & Sports Science Standards** | Cooley–Tukey Radix-2 FFT, ACSM Compendium of Physical Activities (MET), & [IJRU](https://ijru.sport) official competition rules | Public Standards |
| **Jump Rope & Footwork Community** | Athletes & creators including `@D.LEE___1` (IJRC 30s stage sprint), `@cenxiaolin1` (competition speed-sprint), `@alwaysghadi` (boxing footwork), and the `r/jumprope` & Melbourne Shuffle communities whose public demonstrations enabled real-world calibration | Community Credit |

---

## 📦 Download APK (Release & Debug)

<div align="center">

[![Download Release APK](https://img.shields.io/badge/⬇️_Download_v6.1.0_Release_APK_(46_MB)-2EE59D?style=for-the-badge&logo=android&logoColor=0D1321)](https://github.com/iganramdani/Jumpvex/releases/download/v6.1.0/Jumpvex-v6.1.0-release.apk)
&nbsp;
[![Download Debug APK](https://img.shields.io/badge/🛠️_Download_v6.1.0_Debug_APK-151D2E?style=for-the-badge&logo=android&logoColor=2EE59D)](https://github.com/iganramdani/Jumpvex/releases/download/v6.1.0/Jumpvex-v6.1.0-debug.apk)

<sub>Android 7.0+ (API 24+) • 100% On-Device • Free &amp; Open-Source • <a href="https://github.com/iganramdani/Jumpvex/releases/tag/v6.1.0">View Full Release Notes &amp; SHA-256 Checksums</a></sub>

</div>

---

## ⚖️ Open-Source License, Copyright & Anti-Plagiarism

**Jumpvex** is released as **Free and Open-Source Software** under the strict **[GNU General Public License v3.0 (GPL-3.0)](LICENSE)** so everyone worldwide can benefit, learn, and contribute — **while firmly protecting original authorship against closed-source plagiarism**:

> **Copyright © 2026 Igan Ramdani ([`@iganramdani`](https://github.com/iganramdani)). All rights reserved under GNU GPL v3.0.**

- ✅ **Allowed:** Free use, study, modification, and community Pull Requests.
- 📜 **Attribution Required:** Any copy or fork **must preserve the original copyright notice** ([`NOTICE`](NOTICE)) and [`CREDITS.md`](CREDITS.md), and clearly document all modifications.
- 🔒 **Anti-Plagiarism (*Strong Copyleft*):** Repackaging or monetizing **Jumpvex** (including AI Engine v14, STFT DSP, or UI) as a closed-source / proprietary app is strictly prohibited. All derivative works **must remain open-source under the same GPL-3.0 license**.
- 🛡️ **Brand Protection:** The **Jumpvex** name and official visual identity belong to the original author; unofficial forks must use a distinct app name and package ID.
