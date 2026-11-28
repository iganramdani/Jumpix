<div align="center">

# 🦀 Jumpvex Pure Rust SIMD Trainer (`training/rust_trainer/`)

[![Zero Dependencies](https://img.shields.io/badge/External_Crates-0_(100%25_Offline)-F97316?style=for-the-badge&logo=rust&logoColor=white)](Cargo.toml)
[![SIMD GEMM](https://img.shields.io/badge/Kernel-4xH_NEON_%2F_AVX2_SIMD-2EE59D?style=for-the-badge)](src/main.rs)
[![Binary Format](https://img.shields.io/badge/Export-JVX7_Kotlin_Compatible-38BDF8?style=for-the-badge)](../../app/src/main/java/com/jumpropeai/ai/NeuralVarEngine.kt)

**[← Training Hub](../README.md)** • **[📦 Dataset](../dataset/README.md)** • **[📱 Android Engine](../../app/README.md)** • **[🏠 Root Repo](../../README.md)**

</div>

---

## ⚡ Why Zero-Dependency Pure Rust?

- **100% Offline Compile on Termux:** `[dependencies]` in [`Cargo.toml`](Cargo.toml) is completely empty — compiles with `RUSTFLAGS="-C target-cpu=native" cargo build --release` in **~10–14 seconds** with zero crates.io downloads and zero C/OpenBLAS linker errors.
- **Const-Generic `4×H` Register-Blocked SIMD GEMM:** `gemm_nn_acc<K, H>` and `gemm_tn_acc<K, H>` tile 4 timesteps against power-of-2/16-aligned weight rows (`HID_BI = 48`, `HID_CA = 32`), allowing LLVM to emit contiguous ARM64 NEON (`fmla v0.4s`) and x86 AVX2 (`vfmadd231ps`) FMA instructions with zero bounds checks.
- **Zero-Copy 5-Thread Parallelism:** `std::thread::scope` runs all 5 CV folds simultaneously sharing a single `&[Sequence]` slice in RAM (zero pickling, zero `forkserver` issues).

---

## 🧠 Anti-Overfit & Precision-Balance Suite (`v7.2`)

| Technique | Implementation in [`src/main.rs`](src/main.rs) | Purpose |
| :--- | :--- | :--- |
| **Asymmetric Distillation** | `HID_BI = 48` (5 blocks `[1,2,4,8,16]`) → `HID_CA = 32` (5 blocks) | Transfers ±1.03s bidirectional future context into the `< 160 µs/frame` causal Live Camera model with zero validation leakage. |
| **Sharp Peak Re-Labeling** | `resample_targets_sharp()` after `0.78×–1.30×` Time-Stretching | Preserves crisp `1.0 / 0.40` jump peak centers instead of smearing peak widths when warping RPM speed. |
| **Temporal CutMix** | Splices `24–80` frame idle/walk-away clips at start, mid, or end (35% prob) | Eliminates false jump counts when athletes pause or walk toward the camera. |
| **Inverted Residual Dropout** | Channel-consistent `p = 0.12` per block + `8%` input channel dropout | Prevents overfitting on 64 videos despite wider `HID_CH=48/32` capacity (0 runtime cost in Kotlin). |
| **Balanced Focal + OutroFP Boost** | `pos_weight = [2.0, 1.3]`, `hard_outro_boost = 1.9` at `p > 0.22` | Sharpens jump Precision while slashing inactive `OutroFP` peaks by >40%. |
| **EMA / SWA Weight Smoothing** | Shadow EMA (`decay = 0.992`) over the final 35% of epochs + `wd = 4e-4` | Smooths AdamW step variance for higher Out-of-Fold F1 stability. |

---

## 📊 Verified Speed & Accuracy Benchmarks

| Hardware | Architecture | Speed (`seq-epochs/s`) | 64-Video VolAcc (after 2 rounds) |
| :--- | :--- | :---: | :---: |
| **Intel Xeon (2 vCPU AVX2)** | `HID24×4` | **`410.0 seq-ep/s`** | `99.4%+` |
| **Intel Xeon (2 vCPU AVX2)** | `HID48×5 → HID32×5` | **`161.3 seq-ep/s`** | **`99.71%`** (`3,756 / 3,767`) |
| **Redmi Note 9 (Helio G85 ARM64)** | `HID48×5 → HID32×5` | `52.2 seq-ep/s` | **`99.71%`** (bit-identical PRNG!) |

---

## 🔧 Binary Formats (`JVX7` & `JVXC`)

<details>
<summary><b>Click to inspect exact little-endian binary layouts</b></summary>
<br/>

- **`.jvxmodel` (`JVX7` v1):**
  `Magic "JVX7" (4B)` + `[ver=1, causal, IN=16, H, OUT=2, B=5]` (`24B`) + `dilations[5]` (`20B`) + contiguous `f32` weights (`in_w, in_b, [b_cw, b_cb, b_pw, b_pb]×5, out_w, out_b`).
  - `var_bitemporal_v7.jvxmodel` (`HID48×5`): **`189,944 bytes`**
  - `live_causal_tcn_v7.jvxmodel` (`HID32×5`): **`85,688 bytes`**
- **`checkpoint_v7.jvxckpt` (`JVXC` v3):**
  Atomic (`tmp -> rename`) binary checkpoint storing `HID_BI`, `HID_CA`, `NUM_BLOCKS`, `round_num`, best OOF metrics, and full `params + AdamW m/v + step_num` for all 10 fold models + both production models.

</details>

```bash
# Manual build & run on Linux / macOS / Termux
RUSTFLAGS="-C target-cpu=native" cargo build --release --manifest-path training/rust_trainer/Cargo.toml
training/rust_trainer/target/release/jumpvex_trainer --rounds 5 --export-dir /tmp/models
```
