//! Jumpvex v7.2 — 100% Pure Rust Asymmetric Push-to-Limit SIMD Neural VAR Trainer
//!
//! Zero external crates, zero Python, zero NumPy:
//!   - Tier 2 Bidirectional VAR Teacher (`var_bitemporal_v7.jvxmodel`):
//!       HID_CH = 48, 5 dilated blocks [1, 2, 4, 8, 16] (±1.03s receptive field, ~186 KB)
//!   - Tier 1 Causal 30FPS Live Student (`live_causal_tcn_v7.jvxmodel`):
//!       HID_CH = 32, 5 dilated blocks [1, 2, 4, 8, 16] (1.07s past context, ~84 KB, <160 us/frame)
//!       Distilled directly from the HID_CH=48 Teacher with strict zero data leakage!
//!   - Anti-Overfit & Precision-Balance Suite:
//!       * Const-generic 4xH Register-Blocked SIMD GEMM (ARM64 NEON / AVX2)
//!       * Sharp Peak Re-Labeling after Time-Stretching (0.78x - 1.30x)
//!       * Temporal CutMix + 8% Feature Channel Dropout + Landmark Occlusion
//!       * Inverted Residual Dropout (p = 0.12) inside every dilated TCN block
//!       * Precision-Balanced Focal Loss (pos_weight = [2.0, 1.3]) + Hard OutroFP Boost (1.9x)
//!       * AdamW (wd = 4e-4) + Final-35% EMA/SWA Weight Averaging (decay = 0.992)
//!   - 100% binary-compatible (`JVX7`) with Kotlin `NeuralVarEngine.kt`.

use std::env;
use std::fs::{self, File};
use std::io::{BufReader, BufWriter, Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

const IN_CH: usize = 16;
const HID_BI: usize = 48; // Tier 2 VAR Teacher (exact multiple of 16/8/4 SIMD lanes)
const HID_CA: usize = 32; // Tier 1 Live Causal Student (exact power-of-2 SIMD lanes)
const OUT_CH: usize = 2;
const NUM_BLOCKS: usize = 5;
const DILATIONS: [usize; NUM_BLOCKS] = [1, 2, 4, 8, 16];

const DROPOUT_P: f32 = 0.12;
const WEIGHT_DECAY: f32 = 4e-4;
const EMA_DECAY: f32 = 0.992;

static STOP_REQUESTED: AtomicBool = AtomicBool::new(false);
static SIGINT_COUNT: AtomicUsize = AtomicUsize::new(0);

#[cfg(unix)]
extern "C" {
    fn signal(sig: i32, handler: extern "C" fn(i32)) -> usize;
}

#[cfg(unix)]
extern "C" fn handle_sigint(_sig: i32) {
    let prev = SIGINT_COUNT.fetch_add(1, Ordering::SeqCst);
    STOP_REQUESTED.store(true, Ordering::SeqCst);
    if prev == 0 {
        let msg = b"\n[Ctrl+C] Safe stop requested - finishing current round & saving checkpoint...\n";
        unsafe {
            extern "C" {
                fn write(fd: i32, buf: *const std::ffi::c_void, count: usize) -> isize;
            }
            let _ = write(2, msg.as_ptr() as *const std::ffi::c_void, msg.len());
        }
    } else {
        std::process::exit(0);
    }
}

#[cfg(unix)]
fn install_signal_handlers() {
    unsafe {
        signal(2, handle_sigint);
        signal(15, handle_sigint);
    }
}

#[cfg(not(unix))]
fn install_signal_handlers() {}

/// Ultra-fast deterministic XorShift64* PRNG (zero external crates).
#[derive(Clone)]
struct Rng {
    state: u64,
}

impl Rng {
    fn new(seed: u64) -> Self {
        let mut s = seed.wrapping_mul(0x9E3779B97F4A7C15).wrapping_add(0xBF58476D1CE4E5B9);
        if s == 0 {
            s = 0xDEADBEEF12345678;
        }
        Self { state: s }
    }

    #[inline(always)]
    fn next_u64(&mut self) -> u64 {
        let mut x = self.state;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        self.state = x;
        x.wrapping_mul(0x2545F4914F6CDD1D)
    }

    #[inline(always)]
    fn f32(&mut self) -> f32 {
        ((self.next_u64() >> 40) as f32) * (1.0 / 16777216.0)
    }

    #[inline(always)]
    fn uniform(&mut self, lo: f32, hi: f32) -> f32 {
        lo + (hi - lo) * self.f32()
    }

    #[inline(always)]
    fn usize_range(&mut self, lo: usize, hi: usize) -> usize {
        if hi <= lo {
            return lo;
        }
        lo + (self.next_u64() as usize % (hi - lo))
    }

    #[inline(always)]
    fn normal(&mut self, std: f32) -> f32 {
        let u1 = self.f32().max(1e-7);
        let u2 = self.f32();
        let r = (-2.0 * u1.ln()).sqrt();
        r * (std::f32::consts::TAU * u2).cos() * std
    }

    fn shuffle(&mut self, slice: &mut [usize]) {
        for i in (1..slice.len()).rev() {
            let j = self.usize_range(0, i + 1);
            slice.swap(i, j);
        }
    }
}

#[derive(Clone)]
struct Sequence {
    len: usize,
    feats: Vec<f32>,   // [len * 16]
    targets: Vec<f32>, // [len * 2]
}

fn load_jvxbin(path: &Path) -> Result<Vec<Sequence>, String> {
    let f = File::open(path).map_err(|e| format!("Failed to open {}: {}", path.display(), e))?;
    let mut r = BufReader::new(f);
    let mut magic = [0u8; 4];
    r.read_exact(&mut magic).map_err(|e| format!("Read magic failed: {}", e))?;
    if &magic != b"JVXF" {
        return Err(format!("Invalid JVXF magic in {}", path.display()));
    }
    let mut hdr = [0u8; 8];
    r.read_exact(&mut hdr).map_err(|e| format!("Read header failed: {}", e))?;
    let _ver = i32::from_le_bytes(hdr[0..4].try_into().unwrap());
    let count = i32::from_le_bytes(hdr[4..8].try_into().unwrap()) as usize;

    let mut seqs = Vec::with_capacity(count);
    let mut len_buf = [0u8; 4];
    for _ in 0..count {
        r.read_exact(&mut len_buf).map_err(|e| format!("Read seq len failed: {}", e))?;
        let t = i32::from_le_bytes(len_buf) as usize;
        let mut f_bytes = vec![0u8; t * IN_CH * 4];
        let mut y_bytes = vec![0u8; t * OUT_CH * 4];
        r.read_exact(&mut f_bytes).map_err(|e| format!("Read feats failed: {}", e))?;
        r.read_exact(&mut y_bytes).map_err(|e| format!("Read targets failed: {}", e))?;

        let mut feats = Vec::with_capacity(t * IN_CH);
        for chunk in f_bytes.chunks_exact(4) {
            feats.push(f32::from_le_bytes(chunk.try_into().unwrap()));
        }
        let mut targets = Vec::with_capacity(t * OUT_CH);
        for chunk in y_bytes.chunks_exact(4) {
            targets.push(f32::from_le_bytes(chunk.try_into().unwrap()));
        }
        seqs.push(Sequence { len: t, feats, targets });
    }
    Ok(seqs)
}

fn make_hard_negative_sequences(sequences: &[Sequence], rng: &mut Rng) -> Vec<Sequence> {
    let mut negatives = Vec::with_capacity(32);
    for k in 0..32usize {
        let mut len = rng.usize_range(90, 220);
        let mut f = vec![0.0f32; len * IN_CH];
        let kind = k % 4;
        if kind == 0 {
            for t in 0..len {
                for c in 0..12 {
                    f[t * IN_CH + c] = rng.normal(0.03);
                }
                f[t * IN_CH + 12] = 0.85;
            }
        } else if kind == 1 {
            let freq = rng.uniform(0.03, 0.08);
            for t in 0..len {
                let s = (t as f32 * freq).sin();
                let c = (t as f32 * freq).cos();
                f[t * IN_CH + 0] = s * 0.25 + rng.normal(0.03);
                f[t * IN_CH + 1] = c * 0.15;
                f[t * IN_CH + 2] = s * 0.10;
                f[t * IN_CH + 12] = 0.70;
            }
        } else if kind == 2 {
            let freq = rng.uniform(0.20, 0.45);
            for t in 0..len {
                let s = (t as f32 * freq).sin();
                f[t * IN_CH + 6] = s * 0.70;
                f[t * IN_CH + 7] = (t as f32 * freq * 1.1).cos() * 0.65;
                f[t * IN_CH + 0] = rng.normal(0.04);
                f[t * IN_CH + 2] = rng.normal(0.04);
                f[t * IN_CH + 12] = 0.90;
            }
        } else if !sequences.is_empty() {
            let src = &sequences[k % sequences.len()];
            let mut zero_indices = Vec::new();
            for t in 0..src.len {
                if src.targets[t * OUT_CH + 1] < 0.1 {
                    zero_indices.push(t);
                }
            }
            if zero_indices.len() >= 35 {
                len = zero_indices.len();
                f = vec![0.0f32; len * IN_CH];
                for (dst_t, &src_t) in zero_indices.iter().enumerate() {
                    f[dst_t * IN_CH..(dst_t + 1) * IN_CH]
                        .copy_from_slice(&src.feats[src_t * IN_CH..(src_t + 1) * IN_CH]);
                }
            } else {
                for t in 0..len {
                    f[t * IN_CH + 12] = 0.75;
                }
            }
        }
        let targets = vec![0.0f32; len * OUT_CH];
        negatives.push(Sequence { len, feats: f, targets });
    }
    negatives
}

fn resample_linear(src: &[f32], old_len: usize, new_len: usize, channels: usize) -> Vec<f32> {
    if new_len == old_len || old_len < 2 {
        return src.to_vec();
    }
    let mut dst = vec![0.0f32; new_len * channels];
    let scale = (old_len - 1) as f32 / (new_len - 1).max(1) as f32;
    for t in 0..new_len {
        let pos = t as f32 * scale;
        let i0 = (pos.floor() as usize).min(old_len - 1);
        let i1 = (i0 + 1).min(old_len - 1);
        let frac = pos - i0 as f32;
        let inv = 1.0 - frac;
        let b0 = i0 * channels;
        let b1 = i1 * channels;
        let bd = t * channels;
        for c in 0..channels {
            dst[bd + c] = inv * src[b0 + c] + frac * src[b1 + c];
        }
    }
    dst
}

/// Sharp Peak Re-Labeling after Time-Stretching:
/// Preserves razor-sharp 1-frame jump peak centers (1.0 at peak, 0.40 at +-1 frame)
/// and crisp nearest-neighbor active/inactive skipping boundaries.
fn resample_targets_sharp(src: &[f32], old_len: usize, new_len: usize) -> Vec<f32> {
    if new_len == old_len || old_len < 3 {
        return src.to_vec();
    }
    let mut dst = vec![0.0f32; new_len * OUT_CH];
    let inv_scale = (old_len - 1) as f32 / (new_len - 1).max(1) as f32;
    let fwd_scale = (new_len - 1) as f32 / (old_len - 1).max(1) as f32;

    // 1. Crisp active-window resampling (nearest neighbor)
    for t in 0..new_len {
        let src_t = ((t as f32 * inv_scale).round() as usize).min(old_len - 1);
        dst[t * OUT_CH + 1] = src[src_t * OUT_CH + 1];
    }

    // 2. Detect true local jump peak maxima in original timeline and re-stamp sharp peaks
    for t in 0..old_len {
        let v = src[t * OUT_CH];
        if v < 0.55 {
            continue;
        }
        let prev = if t > 0 { src[(t - 1) * OUT_CH] } else { 0.0 };
        let next = if t + 1 < old_len { src[(t + 1) * OUT_CH] } else { 0.0 };
        if v >= prev && v >= next {
            let nt = ((t as f32 * fwd_scale).round() as usize).min(new_len - 1);
            dst[nt * OUT_CH] = 1.0;
            if nt > 0 {
                dst[(nt - 1) * OUT_CH] = dst[(nt - 1) * OUT_CH].max(0.40);
            }
            if nt + 1 < new_len {
                dst[(nt + 1) * OUT_CH] = dst[(nt + 1) * OUT_CH].max(0.40);
            }
        }
    }
    dst
}

fn augment_sequence(
    seq: &Sequence,
    rng: &mut Rng,
    neg_pool: Option<&[Sequence]>,
    teacher: Option<&[f32]>,
) -> (usize, Vec<f32>, Vec<f32>, Option<Vec<f32>>) {
    let mut len = seq.len;
    let mut f = seq.feats.clone();
    let mut y = seq.targets.clone();
    let mut tp = teacher.map(|t| t.to_vec());

    // 1. Time-Stretching / Speed Warping (0.78x - 1.30x, 45% chance) with SHARP peak re-labeling
    if len >= 48 && rng.f32() < 0.45 {
        let speed = rng.uniform(0.78, 1.30);
        let new_len = ((len as f32 / speed).round() as usize).max(32);
        f = resample_linear(&f, len, new_len, IN_CH);
        y = resample_targets_sharp(&y, len, new_len);
        if let Some(ref tpv) = tp {
            tp = Some(resample_linear(tpv, len, new_len, OUT_CH));
        }
        len = new_len;
        for t in 0..len {
            f[t * IN_CH + 1] *= speed;
            f[t * IN_CH + 4] *= speed;
        }
    }

    // 2. Temporal CutMix: splice idle / walk-away clip (35% chance on active sequences)
    if let Some(negs) = neg_pool {
        if !negs.is_empty() && len >= 40 && rng.f32() < 0.35 {
            let neg = &negs[rng.usize_range(0, negs.len())];
            let seg_len = neg.len.min(rng.usize_range(24, 80));
            if seg_len >= 16 {
                let st = rng.usize_range(0, neg.len - seg_len + 1);
                let seg_f = &neg.feats[st * IN_CH..(st + seg_len) * IN_CH];
                let seg_y = vec![0.0f32; seg_len * OUT_CH];
                let mode = rng.usize_range(0, 3);
                let new_len = len + seg_len;
                let mut nf = Vec::with_capacity(new_len * IN_CH);
                let mut ny = Vec::with_capacity(new_len * OUT_CH);
                let mut ntp = tp.as_ref().map(|_| Vec::with_capacity(new_len * OUT_CH));

                if mode == 0 {
                    nf.extend_from_slice(seg_f);
                    nf.extend_from_slice(&f);
                    ny.extend_from_slice(&seg_y);
                    ny.extend_from_slice(&y);
                    if let (Some(ref mut dst_tp), Some(ref src_tp)) = (&mut ntp, &tp) {
                        dst_tp.extend_from_slice(&seg_y);
                        dst_tp.extend_from_slice(src_tp);
                    }
                } else if mode == 1 {
                    nf.extend_from_slice(&f);
                    nf.extend_from_slice(seg_f);
                    ny.extend_from_slice(&y);
                    ny.extend_from_slice(&seg_y);
                    if let (Some(ref mut dst_tp), Some(ref src_tp)) = (&mut ntp, &tp) {
                        dst_tp.extend_from_slice(src_tp);
                        dst_tp.extend_from_slice(&seg_y);
                    }
                } else {
                    let mid = rng.usize_range(len / 4, ((3 * len) / 4).max(len / 4 + 1));
                    nf.extend_from_slice(&f[..mid * IN_CH]);
                    nf.extend_from_slice(seg_f);
                    nf.extend_from_slice(&f[mid * IN_CH..]);
                    ny.extend_from_slice(&y[..mid * OUT_CH]);
                    ny.extend_from_slice(&seg_y);
                    ny.extend_from_slice(&y[mid * OUT_CH..]);
                    if let (Some(ref mut dst_tp), Some(ref src_tp)) = (&mut ntp, &tp) {
                        dst_tp.extend_from_slice(&src_tp[..mid * OUT_CH]);
                        dst_tp.extend_from_slice(&seg_y);
                        dst_tp.extend_from_slice(&src_tp[mid * OUT_CH..]);
                    }
                }
                len = new_len;
                f = nf;
                y = ny;
                tp = ntp;
            }
        }
    }

    // 3. Amplitude jitter & mirror flip
    let amp = rng.uniform(0.80, 1.25);
    let flip = rng.f32() < 0.5;
    for t in 0..len {
        let base = t * IN_CH;
        for c in 0..5 {
            f[base + c] *= amp;
        }
        if flip {
            f[base + 3] = -f[base + 3];
        }
    }

    // 4. Random Landmark Occlusion (15%) + Mild 8% Feature Channel Dropout
    if len >= 30 && rng.f32() < 0.15 {
        let occ_len = rng.usize_range(6, (len / 3).min(22).max(7));
        let occ_st = rng.usize_range(0, len - occ_len);
        let ank = rng.f32() < 0.5;
        for t in occ_st..(occ_st + occ_len) {
            let base = t * IN_CH;
            if ank {
                f[base + 2] = 0.0;
                f[base + 3] = 0.0;
                f[base + 4] = 0.0;
            } else {
                f[base + 6] = 0.0;
                f[base + 7] = 0.0;
                f[base + 8] = 0.0;
            }
        }
    }
    if rng.f32() < 0.08 {
        let drop_ch = rng.usize_range(0, IN_CH);
        for t in 0..len {
            f[t * IN_CH + drop_ch] = 0.0;
        }
    }

    // 5. Pose noise + audio SNR jitter
    let r = rng.f32();
    let mute = r < 0.20;
    let edm = !mute && r < 0.50;
    let whip_scale = if edm { rng.uniform(0.7, 1.2) } else { 1.0 };
    for t in 0..len {
        let base = t * IN_CH;
        for c in 0..12 {
            f[base + c] += rng.normal(0.04);
        }
        if mute {
            f[base + 13] = 0.0;
            f[base + 14] = 0.0;
            f[base + 15] = 0.0;
        } else if edm {
            f[base + 15] = (f[base + 15] + rng.uniform(0.2, 0.7)).clamp(0.0, 1.5);
            f[base + 13] = (f[base + 13] * whip_scale).clamp(0.0, 1.5);
        }
    }

    (len, f, y, tp)
}

#[inline(always)]
fn gemm_nn_acc<const K: usize, const H: usize>(a: &[f32], b: &[f32], c: &mut [f32], m: usize) {
    let m4 = m & !3;
    let mut t = 0;
    while t < m4 {
        let mut c0 = [0.0f32; H];
        let mut c1 = [0.0f32; H];
        let mut c2 = [0.0f32; H];
        let mut c3 = [0.0f32; H];
        c0.copy_from_slice(&c[t * H..(t + 1) * H]);
        c1.copy_from_slice(&c[(t + 1) * H..(t + 2) * H]);
        c2.copy_from_slice(&c[(t + 2) * H..(t + 3) * H]);
        c3.copy_from_slice(&c[(t + 3) * H..(t + 4) * H]);

        let a0 = &a[t * K..(t + 1) * K];
        let a1 = &a[(t + 1) * K..(t + 2) * K];
        let a2 = &a[(t + 2) * K..(t + 3) * K];
        let a3 = &a[(t + 3) * K..(t + 4) * K];

        for i in 0..K {
            let v0 = a0[i];
            let v1 = a1[i];
            let v2 = a2[i];
            let v3 = a3[i];
            let br: &[f32; H] = b[i * H..(i + 1) * H].try_into().unwrap();
            for j in 0..H {
                let bv = br[j];
                c0[j] += v0 * bv;
                c1[j] += v1 * bv;
                c2[j] += v2 * bv;
                c3[j] += v3 * bv;
            }
        }
        c[t * H..(t + 1) * H].copy_from_slice(&c0);
        c[(t + 1) * H..(t + 2) * H].copy_from_slice(&c1);
        c[(t + 2) * H..(t + 3) * H].copy_from_slice(&c2);
        c[(t + 3) * H..(t + 4) * H].copy_from_slice(&c3);
        t += 4;
    }
    while t < m {
        let mut c0 = [0.0f32; H];
        c0.copy_from_slice(&c[t * H..(t + 1) * H]);
        let a0 = &a[t * K..(t + 1) * K];
        for i in 0..K {
            let v0 = a0[i];
            let br: &[f32; H] = b[i * H..(i + 1) * H].try_into().unwrap();
            for j in 0..H {
                c0[j] += v0 * br[j];
            }
        }
        c[t * H..(t + 1) * H].copy_from_slice(&c0);
        t += 1;
    }
}

#[inline(always)]
fn gemm_tn_acc<const K: usize, const H: usize>(a: &[f32], b: &[f32], c: &mut [f32], m: usize) {
    let mut acc = [[0.0f32; H]; K];
    for t in 0..m {
        let ar: &[f32; K] = a[t * K..(t + 1) * K].try_into().unwrap();
        let br: &[f32; H] = b[t * H..(t + 1) * H].try_into().unwrap();
        for i in 0..K {
            let av = ar[i];
            let row = &mut acc[i];
            for j in 0..H {
                row[j] += av * br[j];
            }
        }
    }
    for i in 0..K {
        let dst = &mut c[i * H..(i + 1) * H];
        for j in 0..H {
            dst[j] += acc[i][j];
        }
    }
}

#[inline(always)]
fn transpose_sq<const H: usize>(w: &[f32]) -> Vec<f32> {
    let mut wt = vec![0.0f32; H * H];
    for i in 0..H {
        for j in 0..H {
            wt[j * H + i] = w[i * H + j];
        }
    }
    wt
}

#[derive(Clone)]
struct DilatedTcnModel<const H: usize> {
    causal: bool,
    params: Vec<f32>,
    m: Vec<f32>,
    v: Vec<f32>,
    step_num: i64,
}

struct Workspace<const H: usize> {
    z_in: Vec<f32>,
    h_layers: [Vec<f32>; NUM_BLOCKS + 1],
    zc: [Vec<f32>; NUM_BLOCKS],
    ac: [Vec<f32>; NUM_BLOCKS],
    drop_mask: [Vec<f32>; NUM_BLOCKS],
    probs: Vec<f32>,
    dlogits: Vec<f32>,
    dh: Vec<f32>,
    dh_next: Vec<f32>,
    dac: Vec<f32>,
    dzc: Vec<f32>,
    grads: Vec<f32>,
}

impl<const H: usize> Workspace<H> {
    fn new() -> Self {
        Self {
            z_in: Vec::new(),
            h_layers: std::array::from_fn(|_| Vec::new()),
            zc: std::array::from_fn(|_| Vec::new()),
            ac: std::array::from_fn(|_| Vec::new()),
            drop_mask: std::array::from_fn(|_| Vec::new()),
            probs: Vec::new(),
            dlogits: Vec::new(),
            dh: Vec::new(),
            dh_next: Vec::new(),
            dac: Vec::new(),
            dzc: Vec::new(),
            grads: vec![0.0f32; DilatedTcnModel::<H>::total_params()],
        }
    }

    fn ensure(&mut self, len: usize) {
        let th = len * H;
        let to = len * OUT_CH;
        if self.z_in.len() < th {
            self.z_in.resize(th, 0.0);
            for h in &mut self.h_layers {
                h.resize(th, 0.0);
            }
            for z in &mut self.zc {
                z.resize(th, 0.0);
            }
            for a in &mut self.ac {
                a.resize(th, 0.0);
            }
            for dm in &mut self.drop_mask {
                dm.resize(th, 1.0);
            }
            self.dh.resize(th, 0.0);
            self.dh_next.resize(th, 0.0);
            self.dac.resize(th, 0.0);
            self.dzc.resize(th, 0.0);
        }
        if self.probs.len() < to {
            self.probs.resize(to, 0.0);
            self.dlogits.resize(to, 0.0);
        }
    }
}

impl<const H: usize> DilatedTcnModel<H> {
    const SZ_IN_W: usize = IN_CH * H;
    const SZ_IN_B: usize = H;
    const SZ_CW: usize = 3 * H * H;
    const SZ_CB: usize = H;
    const SZ_PW: usize = H * H;
    const SZ_PB: usize = H;
    const SZ_BLOCK: usize = Self::SZ_CW + Self::SZ_CB + Self::SZ_PW + Self::SZ_PB;
    const SZ_OUT_W: usize = H * OUT_CH;
    const SZ_OUT_B: usize = OUT_CH;

    #[inline(always)]
    const fn total_params() -> usize {
        Self::SZ_IN_W + Self::SZ_IN_B + NUM_BLOCKS * Self::SZ_BLOCK + Self::SZ_OUT_W + Self::SZ_OUT_B
    }
    #[inline(always)]
    const fn blk_cw_off(b: usize) -> usize {
        Self::SZ_IN_W + Self::SZ_IN_B + b * Self::SZ_BLOCK
    }
    #[inline(always)]
    const fn blk_cb_off(b: usize) -> usize {
        Self::blk_cw_off(b) + Self::SZ_CW
    }
    #[inline(always)]
    const fn blk_pw_off(b: usize) -> usize {
        Self::blk_cb_off(b) + Self::SZ_CB
    }
    #[inline(always)]
    const fn blk_pb_off(b: usize) -> usize {
        Self::blk_pw_off(b) + Self::SZ_PW
    }
    #[inline(always)]
    const fn out_w_off() -> usize {
        Self::SZ_IN_W + Self::SZ_IN_B + NUM_BLOCKS * Self::SZ_BLOCK
    }
    #[inline(always)]
    const fn out_b_off() -> usize {
        Self::out_w_off() + Self::SZ_OUT_W
    }

    fn new(causal: bool, seed: u64) -> Self {
        let mut rng = Rng::new(seed);
        let n_params = Self::total_params();
        let mut params = vec![0.0f32; n_params];

        let in_scale = (2.0f32 / IN_CH as f32).sqrt();
        for x in &mut params[0..Self::SZ_IN_W] {
            *x = rng.normal(in_scale);
        }
        let cw_scale = (2.0f32 / (3 * H) as f32).sqrt();
        let pw_scale = (1.0f32 / H as f32).sqrt();
        for b in 0..NUM_BLOCKS {
            let cw0 = Self::blk_cw_off(b);
            for x in &mut params[cw0..cw0 + Self::SZ_CW] {
                *x = rng.normal(cw_scale);
            }
            let pw0 = Self::blk_pw_off(b);
            for x in &mut params[pw0..pw0 + Self::SZ_PW] {
                *x = rng.normal(pw_scale);
            }
        }
        let ow0 = Self::out_w_off();
        for x in &mut params[ow0..ow0 + Self::SZ_OUT_W] {
            *x = rng.normal(pw_scale);
        }
        let ob0 = Self::out_b_off();
        params[ob0] = -2.2;
        params[ob0 + 1] = 0.0;

        Self {
            causal,
            params,
            m: vec![0.0f32; n_params],
            v: vec![0.0f32; n_params],
            step_num: 0,
        }
    }

    /// Exact analytical forward + backward pass with:
    /// - 4xH Register-Blocked SIMD GEMM
    /// - Inverted Residual Dropout (p = 0.12)
    /// - Precision-Balanced Focal Loss (pos_weight = [2.0, 1.3]) + Hard OutroFP Boost (1.9x)
    /// - Optional Teacher-Student Knowledge Distillation
    fn forward_and_backward(
        &self,
        len: usize,
        x: &[f32],
        y_true: &[f32],
        teacher_probs: Option<&[f32]>,
        distill_alpha: f32,
        rng: &mut Rng,
        ws: &mut Workspace<H>,
    ) {
        ws.ensure(len);
        ws.grads.fill(0.0);
        let p = &self.params;
        let keep_scale = 1.0f32 / (1.0 - DROPOUT_P);

        // 1. Input projection
        let in_w = &p[0..Self::SZ_IN_W];
        let in_b = &p[Self::SZ_IN_W..Self::SZ_IN_W + Self::SZ_IN_B];
        for t in 0..len {
            ws.z_in[t * H..(t + 1) * H].copy_from_slice(in_b);
        }
        gemm_nn_acc::<IN_CH, H>(x, in_w, &mut ws.z_in[..len * H], len);
        for idx in 0..(len * H) {
            ws.h_layers[0][idx] = ws.z_in[idx].max(0.0);
        }

        // 2. 5 Dilated Residual TCN blocks with Inverted Residual Dropout
        for b in 0..NUM_BLOCKS {
            let d = DILATIONS[b];
            let cw = &p[Self::blk_cw_off(b)..Self::blk_cw_off(b) + Self::SZ_CW];
            let cw0 = &cw[0..H * H];
            let cw1 = &cw[H * H..2 * H * H];
            let cw2 = &cw[2 * H * H..3 * H * H];
            let cb = &p[Self::blk_cb_off(b)..Self::blk_cb_off(b) + Self::SZ_CB];
            let pw = &p[Self::blk_pw_off(b)..Self::blk_pw_off(b) + Self::SZ_PW];
            let pb = &p[Self::blk_pb_off(b)..Self::blk_pb_off(b) + Self::SZ_PB];

            let (left_h, right_h) = ws.h_layers.split_at_mut(b + 1);
            let h_prev = &left_h[b][..len * H];
            let h_next = &mut right_h[0][..len * H];
            let zc_buf = &mut ws.zc[b][..len * H];
            let ac_buf = &mut ws.ac[b][..len * H];
            let dm_buf = &mut ws.drop_mask[b][..len * H];

            for t in 0..len {
                zc_buf[t * H..(t + 1) * H].copy_from_slice(cb);
            }
            if self.causal {
                if len > 2 * d {
                    let m = len - 2 * d;
                    gemm_nn_acc::<H, H>(&h_prev[..m * H], cw0, &mut zc_buf[2 * d * H..], m);
                }
                if len > d {
                    let m = len - d;
                    gemm_nn_acc::<H, H>(&h_prev[..m * H], cw1, &mut zc_buf[d * H..], m);
                }
                gemm_nn_acc::<H, H>(h_prev, cw2, zc_buf, len);
            } else {
                if len > d {
                    let m = len - d;
                    gemm_nn_acc::<H, H>(&h_prev[..m * H], cw0, &mut zc_buf[d * H..], m);
                    gemm_nn_acc::<H, H>(&h_prev[d * H..], cw2, &mut zc_buf[..m * H], m);
                }
                gemm_nn_acc::<H, H>(h_prev, cw1, zc_buf, len);
            }

            // Spatial/Channel-consistent Inverted Dropout per sequence for each block
            let mut ch_mask = [keep_scale; H];
            for j in 0..H {
                if rng.f32() < DROPOUT_P {
                    ch_mask[j] = 0.0;
                }
            }
            for t in 0..len {
                let base = t * H;
                for j in 0..H {
                    let m_val = ch_mask[j];
                    dm_buf[base + j] = m_val;
                    ac_buf[base + j] = zc_buf[base + j].max(0.0) * m_val;
                }
            }

            for t in 0..len {
                let hp = &h_prev[t * H..(t + 1) * H];
                let hn = &mut h_next[t * H..(t + 1) * H];
                for j in 0..H {
                    hn[j] = hp[j] + pb[j];
                }
            }
            gemm_nn_acc::<H, H>(ac_buf, pw, h_next, len);
            for idx in 0..(len * H) {
                h_next[idx] = h_next[idx].max(0.0);
            }
        }

        // 3. Output head + Precision-Balanced Focal Loss + Hard OutroFP Suppression
        let ow = &p[Self::out_w_off()..Self::out_w_off() + Self::SZ_OUT_W];
        let ob = &p[Self::out_b_off()..Self::out_b_off() + Self::SZ_OUT_B];
        let h_last = &ws.h_layers[NUM_BLOCKS];
        let inv_norm = 1.0f32 / (len * OUT_CH) as f32;

        for t in 0..len {
            let hr = &h_last[t * H..(t + 1) * H];
            let mut l0 = ob[0];
            let mut l1 = ob[1];
            for i in 0..H {
                let hv = hr[i];
                l0 += hv * ow[i * OUT_CH];
                l1 += hv * ow[i * OUT_CH + 1];
            }
            let p0 = 1.0 / (1.0 + (-l0.clamp(-15.0, 15.0)).exp());
            let p1 = 1.0 / (1.0 + (-l1.clamp(-15.0, 15.0)).exp());
            ws.probs[t * OUT_CH] = p0;
            ws.probs[t * OUT_CH + 1] = p1;

            let yt0 = y_true[t * OUT_CH];
            let yt1 = y_true[t * OUT_CH + 1];
            let (ye0, ye1) = if let Some(tp) = teacher_probs {
                (
                    (1.0 - distill_alpha) * yt0 + distill_alpha * tp[t * OUT_CH],
                    (1.0 - distill_alpha) * yt1 + distill_alpha * tp[t * OUT_CH + 1],
                )
            } else {
                (yt0, yt1)
            };

            // Precision-balanced pos_weight = [2.0, 1.3] + hard OutroFP boost (1.9x at p0 > 0.22)
            let inactive = 1.0 - yt1;
            let hard_boost = if inactive > 0.5 && p0 > 0.22 { 1.9 } else { 0.0 };
            let inact_pen = inactive * 1.45 + hard_boost;
            let bw0 = 1.0 + yt0 * 1.0 + inact_pen;
            let bw1 = 1.0 + yt1 * 0.3 + inact_pen * 0.5;

            let pt0 = if yt0 > 0.5 { p0 } else { 1.0 - p0 };
            let pt1 = if yt1 > 0.5 { p1 } else { 1.0 - p1 };
            let f0 = 0.45 + 0.95 * (1.0 - pt0).clamp(0.0, 1.0);
            let f1 = 0.45 + 0.95 * (1.0 - pt1).clamp(0.0, 1.0);

            ws.dlogits[t * OUT_CH] = (bw0 * f0 * (p0 - ye0)) * inv_norm;
            ws.dlogits[t * OUT_CH + 1] = (bw1 * f1 * (p1 - ye1)) * inv_norm;
        }

        // 4. Backward through output head
        let g_ow = &mut ws.grads[Self::out_w_off()..Self::out_w_off() + Self::SZ_OUT_W];
        let mut g_ob0 = 0.0f32;
        let mut g_ob1 = 0.0f32;
        for t in 0..len {
            let d0 = ws.dlogits[t * OUT_CH];
            let d1 = ws.dlogits[t * OUT_CH + 1];
            g_ob0 += d0;
            g_ob1 += d1;
            let hr = &h_last[t * H..(t + 1) * H];
            let dhr = &mut ws.dh[t * H..(t + 1) * H];
            for i in 0..H {
                let hv = hr[i];
                g_ow[i * OUT_CH] += hv * d0;
                g_ow[i * OUT_CH + 1] += hv * d1;
                dhr[i] = d0 * ow[i * OUT_CH] + d1 * ow[i * OUT_CH + 1];
            }
        }
        ws.grads[Self::out_b_off()] = g_ob0;
        ws.grads[Self::out_b_off() + 1] = g_ob1;

        // 5. Backward through 5 dilated residual blocks
        for b in (0..NUM_BLOCKS).rev() {
            let d = DILATIONS[b];
            let cw = &p[Self::blk_cw_off(b)..Self::blk_cw_off(b) + Self::SZ_CW];
            let cw0 = &cw[0..H * H];
            let cw1 = &cw[H * H..2 * H * H];
            let cw2 = &cw[2 * H * H..3 * H * H];
            let pw = &p[Self::blk_pw_off(b)..Self::blk_pw_off(b) + Self::SZ_PW];

            let h_prev = &ws.h_layers[b][..len * H];
            let h_out = &ws.h_layers[b + 1][..len * H];
            let zc_buf = &ws.zc[b][..len * H];
            let ac_buf = &ws.ac[b][..len * H];
            let dm_buf = &ws.drop_mask[b][..len * H];

            let (g_cw_slice, rest) =
                ws.grads[Self::blk_cw_off(b)..Self::blk_cw_off(b) + Self::SZ_BLOCK].split_at_mut(Self::SZ_CW);
            let (g_cb, rest2) = rest.split_at_mut(Self::SZ_CB);
            let (g_pw, g_pb) = rest2.split_at_mut(Self::SZ_PW);
            let (g_cw0, g_cw12) = g_cw_slice.split_at_mut(H * H);
            let (g_cw1, g_cw2) = g_cw12.split_at_mut(H * H);

            for t in 0..len {
                let base = t * H;
                for j in 0..H {
                    let v = if h_out[base + j] > 0.0 { ws.dh[base + j] } else { 0.0 };
                    ws.dh[base + j] = v;
                    ws.dh_next[base + j] = v;
                    g_pb[j] += v;
                }
            }

            gemm_tn_acc::<H, H>(ac_buf, &ws.dh[..len * H], g_pw, len);

            ws.dac[..len * H].fill(0.0);
            let pw_t = transpose_sq::<H>(pw);
            gemm_nn_acc::<H, H>(&ws.dh[..len * H], &pw_t, &mut ws.dac[..len * H], len);

            for t in 0..len {
                let base = t * H;
                for j in 0..H {
                    let v = if zc_buf[base + j] > 0.0 {
                        ws.dac[base + j] * dm_buf[base + j]
                    } else {
                        0.0
                    };
                    ws.dzc[base + j] = v;
                    g_cb[j] += v;
                }
            }

            let dzc_slice = &ws.dzc[..len * H];
            let dh_next = &mut ws.dh_next[..len * H];

            if self.causal {
                if len > 2 * d {
                    let m = len - 2 * d;
                    gemm_tn_acc::<H, H>(&h_prev[..m * H], &dzc_slice[2 * d * H..], g_cw0, m);
                    let cw0_t = transpose_sq::<H>(cw0);
                    gemm_nn_acc::<H, H>(&dzc_slice[2 * d * H..], &cw0_t, &mut dh_next[..m * H], m);
                }
                if len > d {
                    let m = len - d;
                    gemm_tn_acc::<H, H>(&h_prev[..m * H], &dzc_slice[d * H..], g_cw1, m);
                    let cw1_t = transpose_sq::<H>(cw1);
                    gemm_nn_acc::<H, H>(&dzc_slice[d * H..], &cw1_t, &mut dh_next[..m * H], m);
                }
                gemm_tn_acc::<H, H>(h_prev, dzc_slice, g_cw2, len);
                let cw2_t = transpose_sq::<H>(cw2);
                gemm_nn_acc::<H, H>(dzc_slice, &cw2_t, dh_next, len);
            } else {
                if len > d {
                    let m = len - d;
                    gemm_tn_acc::<H, H>(&h_prev[..m * H], &dzc_slice[d * H..], g_cw0, m);
                    let cw0_t = transpose_sq::<H>(cw0);
                    gemm_nn_acc::<H, H>(&dzc_slice[d * H..], &cw0_t, &mut dh_next[..m * H], m);

                    gemm_tn_acc::<H, H>(&h_prev[d * H..], &dzc_slice[..m * H], g_cw2, m);
                    let cw2_t = transpose_sq::<H>(cw2);
                    gemm_nn_acc::<H, H>(&dzc_slice[..m * H], &cw2_t, &mut dh_next[d * H..], m);
                }
                gemm_tn_acc::<H, H>(h_prev, dzc_slice, g_cw1, len);
                let cw1_t = transpose_sq::<H>(cw1);
                gemm_nn_acc::<H, H>(dzc_slice, &cw1_t, dh_next, len);
            }

            std::mem::swap(&mut ws.dh, &mut ws.dh_next);
        }

        // 6. Backward through input projection
        let (g_in_w, g_in_b) = ws.grads[0..Self::SZ_IN_W + Self::SZ_IN_B].split_at_mut(Self::SZ_IN_W);
        for t in 0..len {
            let base = t * H;
            for j in 0..H {
                let v = if ws.z_in[base + j] > 0.0 { ws.dh[base + j] } else { 0.0 };
                ws.dzc[base + j] = v;
                g_in_b[j] += v;
            }
        }
        gemm_tn_acc::<IN_CH, H>(x, &ws.dzc[..len * H], g_in_w, len);
    }

    fn step_adamw(&mut self, grads: &[f32], lr: f32, wd: f32) {
        self.step_num += 1;
        let b1 = 0.9f32;
        let b2 = 0.999f32;
        let eps = 1e-8f32;
        let c1 = 1.0 - b1.powi(self.step_num as i32);
        let c2 = 1.0 - b2.powi(self.step_num as i32);
        let inv_c1 = 1.0 / c1;
        let inv_c2 = 1.0 / c2;
        let n_params = Self::total_params();

        for idx in 0..n_params {
            let g = grads[idx].clamp(-2.0, 2.0);
            let m = b1 * self.m[idx] + (1.0 - b1) * g;
            let v = b2 * self.v[idx] + (1.0 - b2) * g * g;
            self.m[idx] = m;
            self.v[idx] = v;

            let is_bias = (idx >= Self::SZ_IN_W && idx < Self::SZ_IN_W + Self::SZ_IN_B)
                || idx >= Self::out_b_off()
                || (idx >= Self::SZ_IN_W + Self::SZ_IN_B && idx < Self::out_w_off() && {
                    let rem = (idx - (Self::SZ_IN_W + Self::SZ_IN_B)) % Self::SZ_BLOCK;
                    (rem >= Self::SZ_CW && rem < Self::SZ_CW + Self::SZ_CB)
                        || rem >= Self::SZ_CW + Self::SZ_CB + Self::SZ_PW
                });
            if !is_bias {
                self.params[idx] -= lr * wd * self.params[idx];
            }
            let m_hat = m * inv_c1;
            let v_hat = v * inv_c2;
            self.params[idx] -= lr * m_hat / (v_hat.sqrt() + eps);
        }
    }

    fn predict(&self, len: usize, x: &[f32]) -> Vec<f32> {
        let p = &self.params;
        let in_w = &p[0..Self::SZ_IN_W];
        let in_b = &p[Self::SZ_IN_W..Self::SZ_IN_W + Self::SZ_IN_B];
        let mut h = vec![0.0f32; len * H];
        let mut zc = vec![0.0f32; len * H];
        let mut h_next = vec![0.0f32; len * H];

        for t in 0..len {
            h[t * H..(t + 1) * H].copy_from_slice(in_b);
        }
        gemm_nn_acc::<IN_CH, H>(x, in_w, &mut h, len);
        for v in &mut h {
            *v = v.max(0.0);
        }

        for b in 0..NUM_BLOCKS {
            let d = DILATIONS[b];
            let cw = &p[Self::blk_cw_off(b)..Self::blk_cw_off(b) + Self::SZ_CW];
            let cw0 = &cw[0..H * H];
            let cw1 = &cw[H * H..2 * H * H];
            let cw2 = &cw[2 * H * H..3 * H * H];
            let cb = &p[Self::blk_cb_off(b)..Self::blk_cb_off(b) + Self::SZ_CB];
            let pw = &p[Self::blk_pw_off(b)..Self::blk_pw_off(b) + Self::SZ_PW];
            let pb = &p[Self::blk_pb_off(b)..Self::blk_pb_off(b) + Self::SZ_PB];

            for t in 0..len {
                zc[t * H..(t + 1) * H].copy_from_slice(cb);
            }
            if self.causal {
                if len > 2 * d {
                    let m = len - 2 * d;
                    gemm_nn_acc::<H, H>(&h[..m * H], cw0, &mut zc[2 * d * H..], m);
                }
                if len > d {
                    let m = len - d;
                    gemm_nn_acc::<H, H>(&h[..m * H], cw1, &mut zc[d * H..], m);
                }
                gemm_nn_acc::<H, H>(&h, cw2, &mut zc, len);
            } else {
                if len > d {
                    let m = len - d;
                    gemm_nn_acc::<H, H>(&h[..m * H], cw0, &mut zc[d * H..], m);
                    gemm_nn_acc::<H, H>(&h[d * H..], cw2, &mut zc[..m * H], m);
                }
                gemm_nn_acc::<H, H>(&h, cw1, &mut zc, len);
            }
            for v in &mut zc {
                *v = v.max(0.0);
            }
            for t in 0..len {
                let hp = &h[t * H..(t + 1) * H];
                let hn = &mut h_next[t * H..(t + 1) * H];
                for j in 0..H {
                    hn[j] = hp[j] + pb[j];
                }
            }
            gemm_nn_acc::<H, H>(&zc, pw, &mut h_next, len);
            for v in &mut h_next {
                *v = v.max(0.0);
            }
            std::mem::swap(&mut h, &mut h_next);
        }

        let ow = &p[Self::out_w_off()..Self::out_w_off() + Self::SZ_OUT_W];
        let ob = &p[Self::out_b_off()..Self::out_b_off() + Self::SZ_OUT_B];
        let mut probs = vec![0.0f32; len * OUT_CH];
        for t in 0..len {
            let hr = &h[t * H..(t + 1) * H];
            let mut l0 = ob[0];
            let mut l1 = ob[1];
            for i in 0..H {
                let hv = hr[i];
                l0 += hv * ow[i * OUT_CH];
                l1 += hv * ow[i * OUT_CH + 1];
            }
            probs[t * OUT_CH] = 1.0 / (1.0 + (-l0.clamp(-15.0, 15.0)).exp());
            probs[t * OUT_CH + 1] = 1.0 / (1.0 + (-l1.clamp(-15.0, 15.0)).exp());
        }
        probs
    }

    fn export_jvxmodel(&self, path: &Path) -> Result<u64, String> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).map_err(|e| format!("mkdir failed: {}", e))?;
        }
        let tmp = path.with_extension(format!("jvxmodel.tmp.{}", std::process::id()));
        {
            let f = File::create(&tmp).map_err(|e| format!("create tmp failed: {}", e))?;
            let mut w = BufWriter::new(f);
            w.write_all(b"JVX7").map_err(|e| e.to_string())?;
            let hdr: [i32; 6] = [
                1,
                if self.causal { 1 } else { 0 },
                IN_CH as i32,
                H as i32,
                OUT_CH as i32,
                NUM_BLOCKS as i32,
            ];
            for v in hdr {
                w.write_all(&v.to_le_bytes()).map_err(|e| e.to_string())?;
            }
            for &d in &DILATIONS {
                w.write_all(&(d as i32).to_le_bytes()).map_err(|e| e.to_string())?;
            }
            for &v in &self.params {
                w.write_all(&v.to_le_bytes()).map_err(|e| e.to_string())?;
            }
            w.flush().map_err(|e| e.to_string())?;
            w.get_ref().sync_all().map_err(|e| e.to_string())?;
        }
        fs::rename(&tmp, path).map_err(|e| format!("rename failed: {}", e))?;
        Ok(fs::metadata(path).map(|m| m.len()).unwrap_or(0))
    }

    fn write_ckpt<W: Write>(&self, w: &mut W) -> std::io::Result<()> {
        w.write_all(&[if self.causal { 1u8 } else { 0u8 }])?;
        w.write_all(&self.step_num.to_le_bytes())?;
        for &v in &self.params {
            w.write_all(&v.to_le_bytes())?;
        }
        for &v in &self.m {
            w.write_all(&v.to_le_bytes())?;
        }
        for &v in &self.v {
            w.write_all(&v.to_le_bytes())?;
        }
        Ok(())
    }

    fn read_ckpt<R: Read>(r: &mut R) -> std::io::Result<Self> {
        let mut cb = [0u8; 1];
        r.read_exact(&mut cb)?;
        let mut sb = [0u8; 8];
        r.read_exact(&mut sb)?;
        let step_num = i64::from_le_bytes(sb);
        let n_params = Self::total_params();

        let read_vec = |r: &mut R| -> std::io::Result<Vec<f32>> {
            let mut raw = vec![0u8; n_params * 4];
            r.read_exact(&mut raw)?;
            let mut out = Vec::with_capacity(n_params);
            for c in raw.chunks_exact(4) {
                out.push(f32::from_le_bytes(c.try_into().unwrap()));
            }
            Ok(out)
        };
        let params = read_vec(r)?;
        let m = read_vec(r)?;
        let v = read_vec(r)?;
        Ok(Self {
            causal: cb[0] != 0,
            params,
            m,
            v,
            step_num,
        })
    }
}

fn train_model_on_split<const H: usize, const HT: usize>(
    train_seqs: &[&Sequence],
    neg_seqs: &[Sequence],
    causal: bool,
    epochs: usize,
    seed: u64,
    init_model: Option<DilatedTcnModel<H>>,
    lr_max: f32,
    lr_min: f32,
    teacher: Option<&DilatedTcnModel<HT>>,
) -> DilatedTcnModel<H> {
    let mut rng = Rng::new(seed);
    let mut model = init_model.unwrap_or_else(|| DilatedTcnModel::<H>::new(causal, seed));
    let mut ws = Workspace::<H>::new();

    let n_pos = train_seqs.len();
    let total = n_pos + neg_seqs.len();

    // Precompute Teacher soft targets on TRAINING split ONLY (strict zero validation leakage!)
    let teacher_cache: Option<Vec<Vec<f32>>> = if causal {
        teacher.map(|tm| {
            let mut cache = Vec::with_capacity(total);
            for s in train_seqs {
                cache.push(tm.predict(s.len, &s.feats));
            }
            for s in neg_seqs {
                cache.push(tm.predict(s.len, &s.feats));
            }
            cache
        })
    } else {
        None
    };

    let ema_start_ep = (epochs * 65) / 100;
    let mut ema_params: Option<Vec<f32>> = None;

    let mut order: Vec<usize> = (0..total).collect();
    let denom = epochs.max(1) as f32;
    for ep in 0..epochs {
        let lr = lr_max * (0.5 * (1.0 + (std::f32::consts::PI * ep as f32 / denom).cos())) + lr_min;
        rng.shuffle(&mut order);
        for &idx in &order {
            let seq: &Sequence = if idx < n_pos { train_seqs[idx] } else { &neg_seqs[idx - n_pos] };
            let tp_ref = teacher_cache.as_ref().map(|c| c[idx].as_slice());
            let cutmix_pool = if idx < n_pos { Some(neg_seqs) } else { None };
            if rng.f32() < 0.78 {
                let (alen, af, ay, atp) = augment_sequence(seq, &mut rng, cutmix_pool, tp_ref);
                model.forward_and_backward(alen, &af, &ay, atp.as_deref(), 0.30, &mut rng, &mut ws);
            } else {
                model.forward_and_backward(seq.len, &seq.feats, &seq.targets, tp_ref, 0.30, &mut rng, &mut ws);
            }
            model.step_adamw(&ws.grads, lr, WEIGHT_DECAY);

            // Final-35% EMA / SWA weight smoothing
            if ep >= ema_start_ep {
                match &mut ema_params {
                    None => ema_params = Some(model.params.clone()),
                    Some(ema) => {
                        let one_minus = 1.0 - EMA_DECAY;
                        for (e, &p) in ema.iter_mut().zip(model.params.iter()) {
                            *e = EMA_DECAY * (*e) + one_minus * p;
                        }
                    }
                }
            }
        }
    }
    if let Some(ema) = ema_params {
        model.params = ema;
    }
    model
}

fn evaluate_oof(sequences: &[Sequence], oof_preds: &[Vec<f32>]) -> (f32, f32, f32, usize) {
    let mut tp: usize = 0;
    let mut fp: usize = 0;
    let mut fn_cnt: usize = 0;
    let mut neg_fp_peaks: usize = 0;

    for (s, pred) in sequences.iter().zip(oof_preds.iter()) {
        let n = s.len;
        let mut p_jump = vec![0.0f32; n];
        let mut y_true = vec![false; n];
        let mut y_hat = vec![false; n];
        for i in 0..n {
            let pj = pred[i * OUT_CH] * (pred[i * OUT_CH + 1] + 0.25).clamp(0.0, 1.0);
            p_jump[i] = pj;
            y_true[i] = s.targets[i * OUT_CH] > 0.45;
            y_hat[i] = pj > 0.38;
        }
        for i in 0..n {
            let lo = i.saturating_sub(1);
            let hi = (i + 1).min(n - 1);
            let mut yt_dil = false;
            let mut yh_dil = false;
            for k in lo..=hi {
                if y_true[k] {
                    yt_dil = true;
                }
                if y_hat[k] {
                    yh_dil = true;
                }
            }
            if y_hat[i] && yt_dil {
                tp += 1;
            }
            if y_hat[i] && !yt_dil {
                fp += 1;
            }
            if y_true[i] && !yh_dil {
                fn_cnt += 1;
            }
            if i >= 1 && i + 1 < n {
                if s.targets[i * OUT_CH + 1] < 0.05
                    && p_jump[i] > 0.52
                    && p_jump[i] >= p_jump[i - 1]
                    && p_jump[i] >= p_jump[i + 1]
                {
                    neg_fp_peaks += 1;
                }
            }
        }
    }
    let prec = tp as f32 / (tp + fp).max(1) as f32;
    let rec = tp as f32 / (tp + fn_cnt).max(1) as f32;
    let f1 = 2.0 * prec * rec / (prec + rec).max(1e-6);
    (prec, rec, f1, neg_fp_peaks)
}

#[derive(Clone)]
struct TrainMeta {
    round_num: u64,
    best_bi_f1: f32,
    best_bi_prec: f32,
    best_bi_rec: f32,
    best_bi_outro_fp: usize,
    best_ca_f1: f32,
    best_ca_prec: f32,
    best_ca_rec: f32,
    best_ca_outro_fp: usize,
}

impl Default for TrainMeta {
    fn default() -> Self {
        Self {
            round_num: 0,
            best_bi_f1: -1.0,
            best_bi_prec: 0.0,
            best_bi_rec: 0.0,
            best_bi_outro_fp: 999_999,
            best_ca_f1: -1.0,
            best_ca_prec: 0.0,
            best_ca_rec: 0.0,
            best_ca_outro_fp: 999_999,
        }
    }
}

struct Checkpoint {
    meta: TrainMeta,
    fold_bi: Vec<Option<DilatedTcnModel<HID_BI>>>,
    fold_ca: Vec<Option<DilatedTcnModel<HID_CA>>>,
    prod_bi: Option<DilatedTcnModel<HID_BI>>,
    prod_ca: Option<DilatedTcnModel<HID_CA>>,
}

impl Checkpoint {
    fn fresh(n_folds: usize) -> Self {
        Self {
            meta: TrainMeta::default(),
            fold_bi: vec![None; n_folds],
            fold_ca: vec![None; n_folds],
            prod_bi: None,
            prod_ca: None,
        }
    }

    fn save(&self, path: &Path) -> Result<(), String> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        let tmp = path.with_extension(format!("jvxckpt.tmp.{}", std::process::id()));
        {
            let f = File::create(&tmp).map_err(|e| e.to_string())?;
            let mut w = BufWriter::new(f);
            w.write_all(b"JVXC").map_err(|e| e.to_string())?;
            // Version 3 header includes HID_BI, HID_CA, NUM_BLOCKS for safe architecture upgrades
            w.write_all(&3u32.to_le_bytes()).map_err(|e| e.to_string())?;
            w.write_all(&(HID_BI as u32).to_le_bytes()).map_err(|e| e.to_string())?;
            w.write_all(&(HID_CA as u32).to_le_bytes()).map_err(|e| e.to_string())?;
            w.write_all(&(NUM_BLOCKS as u32).to_le_bytes()).map_err(|e| e.to_string())?;

            w.write_all(&self.meta.round_num.to_le_bytes()).map_err(|e| e.to_string())?;
            w.write_all(&self.meta.best_bi_f1.to_le_bytes()).map_err(|e| e.to_string())?;
            w.write_all(&self.meta.best_bi_prec.to_le_bytes()).map_err(|e| e.to_string())?;
            w.write_all(&self.meta.best_bi_rec.to_le_bytes()).map_err(|e| e.to_string())?;
            w.write_all(&(self.meta.best_bi_outro_fp as u64).to_le_bytes()).map_err(|e| e.to_string())?;
            w.write_all(&self.meta.best_ca_f1.to_le_bytes()).map_err(|e| e.to_string())?;
            w.write_all(&self.meta.best_ca_prec.to_le_bytes()).map_err(|e| e.to_string())?;
            w.write_all(&self.meta.best_ca_rec.to_le_bytes()).map_err(|e| e.to_string())?;
            w.write_all(&(self.meta.best_ca_outro_fp as u64).to_le_bytes()).map_err(|e| e.to_string())?;

            w.write_all(&(self.fold_bi.len() as u32).to_le_bytes()).map_err(|e| e.to_string())?;
            for m in &self.fold_bi {
                if let Some(bm) = m {
                    w.write_all(&[1u8]).map_err(|e| e.to_string())?;
                    bm.write_ckpt(&mut w).map_err(|e| e.to_string())?;
                } else {
                    w.write_all(&[0u8]).map_err(|e| e.to_string())?;
                }
            }
            for m in &self.fold_ca {
                if let Some(cm) = m {
                    w.write_all(&[1u8]).map_err(|e| e.to_string())?;
                    cm.write_ckpt(&mut w).map_err(|e| e.to_string())?;
                } else {
                    w.write_all(&[0u8]).map_err(|e| e.to_string())?;
                }
            }
            if let Some(bm) = &self.prod_bi {
                w.write_all(&[1u8]).map_err(|e| e.to_string())?;
                bm.write_ckpt(&mut w).map_err(|e| e.to_string())?;
            } else {
                w.write_all(&[0u8]).map_err(|e| e.to_string())?;
            }
            if let Some(cm) = &self.prod_ca {
                w.write_all(&[1u8]).map_err(|e| e.to_string())?;
                cm.write_ckpt(&mut w).map_err(|e| e.to_string())?;
            } else {
                w.write_all(&[0u8]).map_err(|e| e.to_string())?;
            }

            w.flush().map_err(|e| e.to_string())?;
            w.get_ref().sync_all().map_err(|e| e.to_string())?;
        }
        fs::rename(&tmp, path).map_err(|e| e.to_string())?;
        Ok(())
    }

    fn load(path: &Path, n_folds: usize) -> Self {
        let f = match File::open(path) {
            Ok(file) => file,
            Err(_) => return Self::fresh(n_folds),
        };
        let mut r = BufReader::new(f);
        let mut read_inner = || -> std::io::Result<Self> {
            let mut magic = [0u8; 4];
            r.read_exact(&mut magic)?;
            if &magic != b"JVXC" {
                return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "bad magic"));
            }
            let mut u32b = [0u8; 4];
            let mut u64b = [0u8; 8];
            let mut f32b = [0u8; 4];
            r.read_exact(&mut u32b)?;
            let ver = u32::from_le_bytes(u32b);
            if ver != 3 {
                return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "old ckpt version"));
            }
            r.read_exact(&mut u32b)?;
            let hbi = u32::from_le_bytes(u32b) as usize;
            r.read_exact(&mut u32b)?;
            let hca = u32::from_le_bytes(u32b) as usize;
            r.read_exact(&mut u32b)?;
            let nblk = u32::from_le_bytes(u32b) as usize;
            if hbi != HID_BI || hca != HID_CA || nblk != NUM_BLOCKS {
                return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "arch mismatch"));
            }

            let mut meta = TrainMeta::default();
            r.read_exact(&mut u64b)?;
            meta.round_num = u64::from_le_bytes(u64b);
            r.read_exact(&mut f32b)?;
            meta.best_bi_f1 = f32::from_le_bytes(f32b);
            r.read_exact(&mut f32b)?;
            meta.best_bi_prec = f32::from_le_bytes(f32b);
            r.read_exact(&mut f32b)?;
            meta.best_bi_rec = f32::from_le_bytes(f32b);
            r.read_exact(&mut u64b)?;
            meta.best_bi_outro_fp = u64::from_le_bytes(u64b) as usize;
            r.read_exact(&mut f32b)?;
            meta.best_ca_f1 = f32::from_le_bytes(f32b);
            r.read_exact(&mut f32b)?;
            meta.best_ca_prec = f32::from_le_bytes(f32b);
            r.read_exact(&mut f32b)?;
            meta.best_ca_rec = f32::from_le_bytes(f32b);
            r.read_exact(&mut u64b)?;
            meta.best_ca_outro_fp = u64::from_le_bytes(u64b) as usize;

            r.read_exact(&mut u32b)?;
            let nf = u32::from_le_bytes(u32b) as usize;
            let mut flag = [0u8; 1];
            let mut fold_bi = Vec::with_capacity(nf);
            for _ in 0..nf {
                r.read_exact(&mut flag)?;
                fold_bi.push(if flag[0] == 1 {
                    Some(DilatedTcnModel::<HID_BI>::read_ckpt(&mut r)?)
                } else {
                    None
                });
            }
            let mut fold_ca = Vec::with_capacity(nf);
            for _ in 0..nf {
                r.read_exact(&mut flag)?;
                fold_ca.push(if flag[0] == 1 {
                    Some(DilatedTcnModel::<HID_CA>::read_ckpt(&mut r)?)
                } else {
                    None
                });
            }
            r.read_exact(&mut flag)?;
            let prod_bi = if flag[0] == 1 {
                Some(DilatedTcnModel::<HID_BI>::read_ckpt(&mut r)?)
            } else {
                None
            };
            r.read_exact(&mut flag)?;
            let prod_ca = if flag[0] == 1 {
                Some(DilatedTcnModel::<HID_CA>::read_ckpt(&mut r)?)
            } else {
                None
            };
            Ok(Self {
                meta,
                fold_bi,
                fold_ca,
                prod_bi,
                prod_ca,
            })
        };
        read_inner().unwrap_or_else(|_| Self::fresh(n_folds))
    }
}

fn is_new_record(f1: f32, outro_fp: usize, best_f1: f32, best_outro_fp: usize) -> bool {
    if f1 > best_f1 + 1e-6 {
        return true;
    }
    if (f1 - best_f1).abs() <= 1e-6 && outro_fp < best_outro_fp {
        return true;
    }
    false
}

fn write_best_metrics(export_dir: &Path, meta: &TrainMeta, bi_path: &Path, ca_path: &Path) {
    let unix_ts = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let bi_sz = fs::metadata(bi_path).map(|m| m.len()).unwrap_or(0);
    let ca_sz = fs::metadata(ca_path).map(|m| m.len()).unwrap_or(0);
    let body = format!(
        "Jumpvex v7.2 (Pure Rust Asymmetric Push-to-Limit SIMD Trainer)\n\
         ==============================================================\n\
         Updated Unix TS      : {}\n\
         Completed Rounds     : {}\n\
         Architecture         : Tier2=HID{}x{}blk [1,2,4,8,16] -> Tier1=HID{}x{}blk [1,2,4,8,16]\n\
         Regularization       : Dropout=0.12 | WD=4e-4 | EMA=0.992 | SharpPeak+CutMix+Focal\n\n\
         Tier 2 Bidirectional VAR (var_bitemporal_v7.jvxmodel):\n\
           Best OOF F1        : {:.2}%\n\
           OOF Precision      : {:.2}%\n\
           OOF Recall         : {:.2}%\n\
           Outro False Peaks  : {}\n\n\
         Tier 1 Causal 30FPS Live (live_causal_tcn_v7.jvxmodel):\n\
           Best OOF F1        : {:.2}%\n\
           OOF Precision      : {:.2}%\n\
           OOF Recall         : {:.2}%\n\
           Outro False Peaks  : {}\n\n\
         Exported Files:\n\
           - {} ({} bytes)\n\
           - {} ({} bytes)\n",
        unix_ts,
        meta.round_num,
        HID_BI,
        NUM_BLOCKS,
        HID_CA,
        NUM_BLOCKS,
        meta.best_bi_f1 * 100.0,
        meta.best_bi_prec * 100.0,
        meta.best_bi_rec * 100.0,
        meta.best_bi_outro_fp,
        meta.best_ca_f1 * 100.0,
        meta.best_ca_prec * 100.0,
        meta.best_ca_rec * 100.0,
        meta.best_ca_outro_fp,
        bi_path.display(),
        bi_sz,
        ca_path.display(),
        ca_sz,
    );
    let out = export_dir.join("best_metrics.txt");
    let tmp = export_dir.join(format!("best_metrics.txt.tmp.{}", std::process::id()));
    if let Ok(mut f) = File::create(&tmp) {
        let _ = f.write_all(body.as_bytes());
        let _ = f.sync_all();
        let _ = fs::rename(&tmp, &out);
    }
}

fn main() {
    install_signal_handlers();

    let mut jvxbin = PathBuf::from("training/dataset/features64.jvxbin");
    let mut export_dir = PathBuf::from("app/src/main/assets/models");
    let mut ckpt_dir = PathBuf::from("training/checkpoints");
    let mut target_rounds: u64 = 0;
    let mut epochs_cv: usize = 24;
    let mut epochs_prod: usize = 32;
    let mut cool_sec: u64 = 0;
    let mut reset = false;

    let args: Vec<String> = env::args().collect();
    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--jvxbin" if i + 1 < args.len() => {
                jvxbin = PathBuf::from(&args[i + 1]);
                i += 2;
            }
            "--export-dir" if i + 1 < args.len() => {
                export_dir = PathBuf::from(&args[i + 1]);
                i += 2;
            }
            "--checkpoint-dir" if i + 1 < args.len() => {
                ckpt_dir = PathBuf::from(&args[i + 1]);
                i += 2;
            }
            "--rounds" if i + 1 < args.len() => {
                target_rounds = args[i + 1].parse().unwrap_or(0);
                i += 2;
            }
            "--epochs-cv" if i + 1 < args.len() => {
                epochs_cv = args[i + 1].parse().unwrap_or(24);
                i += 2;
            }
            "--epochs-prod" if i + 1 < args.len() => {
                epochs_prod = args[i + 1].parse().unwrap_or(32);
                i += 2;
            }
            "--cool" if i + 1 < args.len() => {
                cool_sec = args[i + 1].parse().unwrap_or(0);
                i += 2;
            }
            "--reset" => {
                reset = true;
                i += 1;
            }
            "--workers" if i + 1 < args.len() => {
                i += 2;
            }
            _ => {
                i += 1;
            }
        }
    }

    let cores = thread::available_parallelism().map(|n| n.get()).unwrap_or(4);
    let ckpt_path = ckpt_dir.join("checkpoint_v7.jvxckpt");

    println!("================================================================================");
    println!("  Jumpvex v7.2 — 100% Pure Rust Asymmetric Push-to-Limit SIMD Trainer");
    println!("================================================================================");
    println!(
        "  Tier 2 Teacher  : HID_CH={} x {} blocks [1,2,4,8,16] (±1.03s context)",
        HID_BI, NUM_BLOCKS
    );
    println!(
        "  Tier 1 Student  : HID_CH={} x {} blocks [1,2,4,8,16] (distilled from Tier 2)",
        HID_CA, NUM_BLOCKS
    );
    println!("  Regularization  : Dropout=0.12 | WD=4e-4 | EMA=0.992 | SharpPeak + CutMix + Focal");
    println!("  CPU Cores       : {} detected (5 parallel fold threads)", cores);
    println!(
        "  Mode            : {}",
        if target_rounds == 0 {
            "Continuous (infinite until Ctrl+C)".to_string()
        } else {
            format!("{} round(s)", target_rounds)
        }
    );
    println!("  Thermal Cool    : {}s pause between rounds", cool_sec);
    println!("  Primary Export  : {}", export_dir.display());
    println!("  Checkpoint File : {}", ckpt_path.display());
    println!("================================================================================");

    let t_load = Instant::now();
    let sequences = match load_jvxbin(&jvxbin) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("ERROR: {}", e);
            std::process::exit(1);
        }
    };
    let total_frames: usize = sequences.iter().map(|s| s.len).sum();
    println!(
        "[Rust Load] Loaded {} real video sequences ({} frames) in {:.1}ms.",
        sequences.len(),
        total_frames,
        t_load.elapsed().as_secs_f64() * 1000.0
    );

    let n_folds = 5usize;
    if reset && ckpt_path.exists() {
        let _ = fs::remove_file(&ckpt_path);
        println!("[Reset] Cleared previous checkpoint; starting fresh from Round 1.");
    }
    let mut ckpt = Checkpoint::load(&ckpt_path, n_folds);
    if ckpt.meta.round_num > 0 {
        println!(
            "[Resume] Resuming from Round {} | Best VAR F1={:.2}% (OutroFP={}) | Best Live F1={:.2}% (OutroFP={})",
            ckpt.meta.round_num,
            ckpt.meta.best_bi_f1 * 100.0,
            ckpt.meta.best_bi_outro_fp,
            ckpt.meta.best_ca_f1 * 100.0,
            ckpt.meta.best_ca_outro_fp
        );
    }

    let bi_export_path = export_dir.join("var_bitemporal_v7.jvxmodel");
    let ca_export_path = export_dir.join("live_causal_tcn_v7.jvxmodel");
    let session_t0 = Instant::now();
    let mut rounds_done: u64 = 0;

    while !STOP_REQUESTED.load(Ordering::SeqCst) {
        if target_rounds > 0 && rounds_done >= target_rounds {
            break;
        }

        let round_num = ckpt.meta.round_num + 1;
        let r_t0 = Instant::now();
        let lr_max = (3.0e-3f32 * 0.94f32.powi((round_num - 1) as i32)).max(1.0e-3);
        let lr_min = 1.2e-4f32;

        println!(
            "\n--- [Round {}] 5-Fold Zero-Leakage CV + Distillation (HID48->HID32 | 5-Block | lr_max={:.5}) ---",
            round_num, lr_max
        );

        let mut prev_fold_bi: Vec<Option<DilatedTcnModel<HID_BI>>> = ckpt.fold_bi.drain(..).collect();
        let mut prev_fold_ca: Vec<Option<DilatedTcnModel<HID_CA>>> = ckpt.fold_ca.drain(..).collect();

        let fold_results: Vec<(
            usize,
            DilatedTcnModel<HID_BI>,
            DilatedTcnModel<HID_CA>,
            Vec<(usize, Vec<f32>)>,
            Vec<(usize, Vec<f32>)>,
            usize,
        )> = thread::scope(|scope| {
            let mut handles = Vec::with_capacity(n_folds);
            for fold in 0..n_folds {
                let seq_ref = &sequences;
                let bi_init = prev_fold_bi[fold].take();
                let ca_init = prev_fold_ca[fold].take();
                let h = scope.spawn(move || {
                    let mut val_idx = Vec::new();
                    let mut trn_seqs: Vec<&Sequence> = Vec::new();
                    for (idx, s) in seq_ref.iter().enumerate() {
                        if idx % n_folds == fold {
                            val_idx.push(idx);
                        } else {
                            trn_seqs.push(s);
                        }
                    }
                    let seed_bi = 100 + round_num * 1000 + fold as u64;
                    let seed_ca = 200 + round_num * 1000 + fold as u64;
                    let mut neg_rng = Rng::new(seed_bi + 7919);
                    let trn_owned: Vec<Sequence> = trn_seqs.iter().map(|&s| s.clone()).collect();
                    let neg_seqs = make_hard_negative_sequences(&trn_owned, &mut neg_rng);

                    let bi_model = train_model_on_split::<HID_BI, HID_BI>(
                        &trn_seqs,
                        &neg_seqs,
                        false,
                        epochs_cv,
                        seed_bi,
                        bi_init,
                        lr_max,
                        lr_min,
                        None,
                    );
                    let ca_model = train_model_on_split::<HID_CA, HID_BI>(
                        &trn_seqs,
                        &neg_seqs,
                        true,
                        epochs_cv,
                        seed_ca,
                        ca_init,
                        lr_max,
                        lr_min,
                        Some(&bi_model),
                    );

                    let bi_preds: Vec<(usize, Vec<f32>)> = val_idx
                        .iter()
                        .map(|&vi| (vi, bi_model.predict(seq_ref[vi].len, &seq_ref[vi].feats)))
                        .collect();
                    let ca_preds: Vec<(usize, Vec<f32>)> = val_idx
                        .iter()
                        .map(|&vi| (vi, ca_model.predict(seq_ref[vi].len, &seq_ref[vi].feats)))
                        .collect();
                    let n_seq_ep = 2 * (trn_seqs.len() + neg_seqs.len()) * epochs_cv;
                    (fold, bi_model, ca_model, bi_preds, ca_preds, n_seq_ep)
                });
                handles.push(h);
            }
            handles.into_iter().map(|h| h.join().expect("fold thread panicked")).collect()
        });

        ckpt.fold_bi = vec![None; n_folds];
        ckpt.fold_ca = vec![None; n_folds];
        let mut oof_bi: Vec<Vec<f32>> = vec![Vec::new(); sequences.len()];
        let mut oof_ca: Vec<Vec<f32>> = vec![Vec::new(); sequences.len()];
        let mut total_seq_epochs: usize = 0;

        for (fold, bm, cm, bp, cp, nse) in fold_results {
            ckpt.fold_bi[fold] = Some(bm);
            ckpt.fold_ca[fold] = Some(cm);
            total_seq_epochs += nse;
            for (vi, p) in bp {
                oof_bi[vi] = p;
            }
            for (vi, p) in cp {
                oof_ca[vi] = p;
            }
        }

        let (bi_p, bi_r, bi_f1, bi_fp) = evaluate_oof(&sequences, &oof_bi);
        let (ca_p, ca_r, ca_f1, ca_fp) = evaluate_oof(&sequences, &oof_ca);

        let prev_bi_f1 = ckpt.meta.best_bi_f1;
        let prev_ca_f1 = ckpt.meta.best_ca_f1;
        let bi_rec = is_new_record(bi_f1, bi_fp, prev_bi_f1, ckpt.meta.best_bi_outro_fp);
        let ca_rec = is_new_record(ca_f1, ca_fp, prev_ca_f1, ckpt.meta.best_ca_outro_fp);

        let need_export_bi = bi_rec || !bi_export_path.exists();
        let need_export_ca = ca_rec || !ca_export_path.exists();

        if bi_rec {
            ckpt.meta.best_bi_f1 = bi_f1;
            ckpt.meta.best_bi_prec = bi_p;
            ckpt.meta.best_bi_rec = bi_r;
            ckpt.meta.best_bi_outro_fp = bi_fp;
        }
        if ca_rec {
            ckpt.meta.best_ca_f1 = ca_f1;
            ckpt.meta.best_ca_prec = ca_p;
            ckpt.meta.best_ca_rec = ca_r;
            ckpt.meta.best_ca_outro_fp = ca_fp;
        }

        let bi_tag = if bi_rec && prev_bi_f1 >= 0.0 {
            format!(" (+{:.2}% NEW RECORD!)", (bi_f1 - prev_bi_f1) * 100.0)
        } else if bi_rec {
            " (INITIAL RECORD)".to_string()
        } else {
            String::new()
        };
        let ca_tag = if ca_rec && prev_ca_f1 >= 0.0 {
            format!(" (+{:.2}% NEW RECORD!)", (ca_f1 - prev_ca_f1) * 100.0)
        } else if ca_rec {
            " (INITIAL RECORD)".to_string()
        } else {
            String::new()
        };

        let cv_sec = r_t0.elapsed().as_secs_f64().max(0.001);
        let sps = total_seq_epochs as f64 / cv_sec;
        println!("================ 5-FOLD OUT-OF-FOLD REPORT (ZERO LEAKAGE) ================");
        println!(
            "  Tier 2 VAR  (HID48x5) : P={:.2}% | R={:.2}% | F1={:.2}% | OutroFP={}{}",
            bi_p * 100.0,
            bi_r * 100.0,
            bi_f1 * 100.0,
            bi_fp,
            bi_tag
        );
        println!(
            "  Tier 1 Live (HID32x5) : P={:.2}% | R={:.2}% | F1={:.2}% | OutroFP={}{}",
            ca_p * 100.0,
            ca_r * 100.0,
            ca_f1 * 100.0,
            ca_fp,
            ca_tag
        );
        println!("  Rust SIMD Speed       : {:.2}s ({:.1} seq-epochs/s)", cv_sec, sps);
        println!("==========================================================================");

        let all_refs: Vec<&Sequence> = sequences.iter().collect();
        let mut exported_any = false;

        if need_export_bi {
            println!("[Best-Only Export] Updating Tier 2 VAR Bi-TCN (HID48x5) on all 64 videos...");
            let mut neg_rng = Rng::new(777 + round_num * 1000 + 9973);
            let all_neg = make_hard_negative_sequences(&sequences, &mut neg_rng);
            let prod_bi = train_model_on_split::<HID_BI, HID_BI>(
                &all_refs,
                &all_neg,
                false,
                epochs_prod,
                777 + round_num * 1000,
                ckpt.prod_bi.take(),
                lr_max,
                lr_min,
                None,
            );
            match prod_bi.export_jvxmodel(&bi_export_path) {
                Ok(sz) => {
                    println!("  -> Saved {} ({} bytes)", bi_export_path.display(), sz);
                    exported_any = true;
                }
                Err(e) => eprintln!("  [ERROR] Export failed: {}", e),
            }
            ckpt.prod_bi = Some(prod_bi);
        }

        if need_export_ca {
            let distill_msg = if ckpt.prod_bi.is_some() {
                " (distilled from Tier 2 HID48 Teacher)"
            } else {
                ""
            };
            println!(
                "[Best-Only Export] Updating Tier 1 Live Causal (HID32x5){}...",
                distill_msg
            );
            let mut neg_rng = Rng::new(888 + round_num * 1000 + 9973);
            let all_neg = make_hard_negative_sequences(&sequences, &mut neg_rng);
            let prod_ca = train_model_on_split::<HID_CA, HID_BI>(
                &all_refs,
                &all_neg,
                true,
                epochs_prod,
                888 + round_num * 1000,
                ckpt.prod_ca.take(),
                lr_max,
                lr_min,
                ckpt.prod_bi.as_ref(),
            );
            match prod_ca.export_jvxmodel(&ca_export_path) {
                Ok(sz) => {
                    println!("  -> Saved {} ({} bytes)", ca_export_path.display(), sz);
                    exported_any = true;
                }
                Err(e) => eprintln!("  [ERROR] Export failed: {}", e),
            }
            ckpt.prod_ca = Some(prod_ca);
        }

        if !need_export_bi && !need_export_ca {
            println!("[Best-Only Export] No new OOF F1 record this round — keeping existing best .jvxmodel weights.");
        }

        ckpt.meta.round_num = round_num;
        if let Err(e) = ckpt.save(&ckpt_path) {
            eprintln!("[Warn] Could not save checkpoint: {}", e);
        }
        if exported_any {
            write_best_metrics(&export_dir, &ckpt.meta, &bi_export_path, &ca_export_path);
        }

        rounds_done += 1;
        println!(
            "[Checkpoint Saved] Round {} complete in {:.2}s | Best VAR={:.2}% | Best Live={:.2}%",
            round_num,
            r_t0.elapsed().as_secs_f64(),
            ckpt.meta.best_bi_f1 * 100.0,
            ckpt.meta.best_ca_f1 * 100.0
        );

        if cool_sec > 0 && !STOP_REQUESTED.load(Ordering::SeqCst) {
            if target_rounds == 0 || rounds_done < target_rounds {
                println!("[Thermal Cool] Pausing {}s to keep phone cool...", cool_sec);
                for _ in 0..cool_sec {
                    if STOP_REQUESTED.load(Ordering::SeqCst) {
                        break;
                    }
                    thread::sleep(Duration::from_secs(1));
                }
            }
        }
    }

    println!("\n================================================================================");
    println!(
        "  Rust Session Summary      : {} round(s) in {:.2}s",
        rounds_done,
        session_t0.elapsed().as_secs_f64()
    );
    println!(
        "  All-Time Best VAR  OOF F1 : {:.2}% (OutroFP={})",
        ckpt.meta.best_bi_f1 * 100.0,
        ckpt.meta.best_bi_outro_fp
    );
    println!(
        "  All-Time Best Live OOF F1 : {:.2}% (OutroFP={})",
        ckpt.meta.best_ca_f1 * 100.0,
        ckpt.meta.best_ca_outro_fp
    );
    println!("  Exported Models Directory : {}", export_dir.display());
    for fname in ["var_bitemporal_v7.jvxmodel", "live_causal_tcn_v7.jvxmodel", "best_metrics.txt"] {
        let p = export_dir.join(fname);
        if let Ok(m) = fs::metadata(&p) {
            println!("    - {} ({} bytes)", p.display(), m.len());
        }
    }
    println!("================================================================================");
}
