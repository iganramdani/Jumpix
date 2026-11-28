<div align="center">

# 🦀 Jumpvex Training Hub (`training/`)

[![100% Pure Rust](https://img.shields.io/badge/100%25_Pure_Rust-Zero_Python-F97316?style=for-the-badge&logo=rust&logoColor=white)](rust_trainer/README.md)
[![Non-Root Termux](https://img.shields.io/badge/Android_Termux-Non--Root_Ready-2EE59D?style=for-the-badge&logo=android&logoColor=0D1321)](termux_train.sh)
[![Dataset: 64 Videos](https://img.shields.io/badge/Dataset-64_Real_Videos_(3.7_MB)-38BDF8?style=for-the-badge)](dataset/README.md)

**[← Back to Root Repo](../README.md)** • **[🦀 Rust SIMD Engine](rust_trainer/README.md)** • **[📦 64-Video Dataset](dataset/README.md)** • **[📱 Android App](../app/README.md)**

</div>

---

## 🚀 1-Command Non-Root Android Termux Training

Train Jumpvex's Dual-Tier Neural VAR models **100% offline directly on your Android smartphone** — **zero root, zero Python, zero NumPy, zero Google Drive, zero PC required**:

```bash
# Inside Termux on Android (auto-installs Rust, compiles native ARM64 NEON binary, runs continuous training)
cd ~/Jumpvex && git pull && bash training/termux_train.sh
```

### Interactive CLI Options

| Command | Behavior |
| :--- | :--- |
| `bash training/termux_train.sh` | **Continuous Full-Speed** all-core training until you press `Ctrl+C` (safe atomic stop). |
| `bash training/termux_train.sh --cool` | Adds a **20-second thermal cooldown** between rounds so your phone stays cool. |
| `bash training/termux_train.sh --cool 30` | Custom **30-second** thermal cooldown between rounds. |
| `bash training/termux_train.sh --rounds 5` | Runs **exactly 5 continuous rounds**, saves checkpoint, and exits cleanly. |
| `bash training/termux_train.sh --reset` | Clears `training/checkpoints/checkpoint_v7.jvxckpt` and starts fresh from Round 1. |

---

## 🧠 Asymmetric Push-to-Limit Distillation Pipeline (`v7.2`)

```mermaid
flowchart LR
    BIN["📦 dataset/features64.jvxbin<br/>64 Real Videos • 53,785 Frames<br/>(Loads in 8 ms)"] --> CV["🔀 5-Fold Zero-Leakage CV<br/>(5 Parallel OS Threads)"]
    CV --> T2["🧠 Tier 2 VAR Teacher<br/>HID_CH=48 x 5 Blocks [1,2,4,8,16]<br/>±1.03s Context (~186 KB)"]
    T2 -- "Soft Targets<br/>(Train Split Only!)" --> T1["⚡ Tier 1 Live Student<br/>HID_CH=32 x 5 Blocks [1,2,4,8,16]<br/>Distilled Causal (~84 KB)"]
    T2 --> EXP["💾 Best-Only Atomic Export<br/>Documents/Jumpvex/Models/<br/>var_bitemporal_v7.jvxmodel<br/>live_causal_tcn_v7.jvxmodel<br/>best_metrics.txt"]
    T1 --> EXP
```

---

## 📂 Sub-Directories

| Folder / File | Purpose |
| :--- | :--- |
| **[`rust_trainer/`](rust_trainer/README.md)** | Zero-dependency Pure Rust SIMD Trainer (`4×H` register-blocked GEMM, Sharp Peak Time-Stretching, Temporal CutMix, Inverted Residual Dropout, EMA/SWA). |
| **[`dataset/`](dataset/README.md)** | Bundled 64-real-video dataset (`features64.jvxbin` `3.7 MB`, `manifest64.tsv`, 64 pose TSVs & 63 WAVs). |
| **[`termux_train.sh`](termux_train.sh)** | Non-root Android Termux launcher with automatic `termux-setup-storage` verification, `termux-wake-lock` (screen-off safe), and zero git-conflict local export to `Documents/Jumpvex/Models/`. |
