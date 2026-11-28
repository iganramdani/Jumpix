<div align="center">

# 📦 Jumpvex 64-Real-Video Dataset (`training/dataset/`)

[![Videos: 64](https://img.shields.io/badge/Real_Videos-64_Verified-2EE59D?style=for-the-badge)](../../ground_truth_all_videos.csv)
[![Frames: 53,785](https://img.shields.io/badge/Frames-53%2C785-38BDF8?style=for-the-badge)](manifest64.tsv)
[![Binary Load: 8ms](https://img.shields.io/badge/JVXF_Load-8_ms_(3.7_MB)-F97316?style=for-the-badge)](features64.jvxbin)

**[← Training Hub](../README.md)** • **[🦀 Rust Trainer](../rust_trainer/README.md)** • **[🏠 Root Repo](../../README.md)**

</div>

---

## 📋 Dataset Inventory

| Artifact | Size | Description |
| :--- | :---: | :--- |
| **`features64.jvxbin`** | `3.7 MB` | Pre-extracted little-endian `JVXF` v1 binary feature dataset (all 64 videos, `53,785` frames × `16` biomechanical+HPSS features + `2` ground-truth targets). Loaded by the Pure Rust trainer in **`~8 ms`**. |
| **`manifest64.tsv`** | `7 KB` | Tab-separated index mapping all 64 videos to dimensions, FPS, pose `.tsv`, and `.wav` paths. |
| **`poses/*.tsv`** | `35 MB` | 64 per-frame 33-landmark pose coordinate streams extracted from real jump rope & IJRC videos. |
| **`wavs/*.wav`** | `55 MB` | 63 mono 16 kHz PCM audio tracks used for 200 Hz Harmonic-Percussive Spectral Separation (HPSS). |
| **[`../../ground_truth_all_videos.csv`](../../ground_truth_all_videos.csv)** | `5 KB` | Human-verified ground-truth jump/step counts (`3,767` total jumps across 64 videos, including official IJRC 30s Speed Sprint). |

---

## 🧬 16-Channel Feature Vector Schema (`IN_CH = 16`)

| Channel(s) | Signal Domain | Description |
| :---: | :--- | :--- |
| **`0 – 4`** | Vertical Biomechanics | Normalized hip lift, hip velocity, ankle lift, left/right ankle asymmetry, ankle velocity. |
| **`5 – 9`** | Upper-Body & Arms | Shoulder-width ratio, left/right wrist vertical & lateral swing velocities, elbow flexion. |
| **`10 – 12`** | Framing & Stability | Knee asymmetry, lateral hip sway, full-body framing confidence / torso scale. |
| **`13 – 15`** | 200 Hz HPSS Audio | Percussive-isolated rope whip energy (`500–7500 Hz`), whip delta, raw mid-band floor (for EDM/mute robustness). |

---

## 🔒 Zero-Leakage 5-Fold Cross-Validation Protocol

- All 64 real videos are partitioned into **5 disjoint video folds** (`idx % 5 == fold`).
- Every fold's Teacher (`HID48×5`) and Student (`HID32×5`) are trained **strictly on the other 4 folds** + synthetic/tail hard negatives; validation videos are **never** seen during training or distillation.
- Want to help expand this dataset? Share your diagnostic `.ZIP` logs or real-world videos via [Submit Calibration CSV](https://github.com/iganramdani/Jumpvex/issues/new?template=calibration_contribution.yml)!
