<div align="center">

# 🔬 Jumpvex Web Telemetry Lab (`docs/`)

[![Live GitHub Pages](https://img.shields.io/badge/🌐_Open_Live_Web_Lab-2EE59D?style=for-the-badge)](https://iganramdani.github.io/Jumpvex/)
[![100% Client-Side](https://img.shields.io/badge/Privacy-100%25_Browser_Local-38BDF8?style=for-the-badge)](index.html)

**[← Back to Root Repo](../README.md)** • **[📱 Android App](../app/README.md)** • **[🦀 Rust Trainer](../training/README.md)**

</div>

---

## ✨ Interactive Modules Powered by GitHub Pages

1. **🔬 Browser CSV Telemetry & Neural VAR Oscilloscope (`#telemetry-lab`)** — Drag-and-drop any CSV from `Documents/Jumpvex/DebugLogs/` (or load the bundled `sample_redmi_note9.csv`) to zoom & scrub Step/Rope Speed, Neural VAR probability, Sub-Foot Cable Passes, CPU latency (`µs`), and JVM Heap (`MB`) — 100% client-side.
2. **⚡ Interactive v7.1.3 Performance & Architecture Explorer (`#engine-explorer`)** — Compare `v7.1.1` vs `v7.1.3` real Redmi Note 9 GC/latency telemetry and explore the Asymmetric `HID48×5 → HID32×5` Pure Rust SIMD architecture interactively.
3. **🦴 Trick Kinematics Sandbox (`#kinematics-sandbox`)** — Interactive visual simulator for Jumpvex's `±110 ms` peak-aligned trick classifier (Crisscross, Double Under, Boxer Step, Heel-to-Toe, Shuffle).
4. **📐 Rope Physics & Camera Framing Calculator (`#physics-calc`)** — Computes optimal jump rope length by athlete height and exact smartphone tripod distance for full-body framing.

---

## 🎨 Visual Assets Catalog (`docs/assets/`)

| File | Usage |
| :--- | :--- |
| `hero_banner_dark.svg` / `hero_banner_light.svg` | Animated dark/light adaptive GitHub README hero banners (`v7.1.3`). |
| `real_64_video_benchmark.svg` | Vector 64-real-video benchmark & dual-tier neural VAR comparison chart. |
| `jumpvex_logo.svg` / `jumpvex_logo.png` | Official Golden Ratio (`φ = 1.618`) `JV` monogram icon. |
| `ui_live_ring_hud.jpg`, `ui_video_studio.jpg`, `ui_history_stats.jpg` | Real smartphone UI gallery screenshots. |

```bash
# Preview Web Telemetry Lab locally
python3 -m http.server 8080 --directory docs
```
