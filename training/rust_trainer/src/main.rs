//! Jumpvex v7.1 — Zero-Dependency All-Core SIMD Rust Neural VAR Trainer
//!
//! Trains both Tier 2 Bidirectional VAR (`var_bitemporal_v7.jvxmodel`) and Tier 1 Causal Live
//! (`live_causal_tcn_v7.jvxmodel`) using pure Rust + `std::thread::scope` + LLVM NEON/AVX SIMD:
//!   - 5-Fold Zero-Leakage Cross-Validation + Teacher-Student Distillation (Tier 2 -> Tier 1)
//!   - Time-Stretching (0.78x - 1.30x), Temporal CutMix, Landmark Occlusion, Audio SNR Jitter
//!   - Focal Hard-Example Mining + Elevated Inactive OutroFP Suppression
//!   - Continuous Warm-Start Resume (`checkpoint_v7.jvxckpt`) + Best-Only Atomic `.jvxmodel` Export
//!   - 100% binary-compatible (`JVX7`, 39,508 bytes) with Kotlin `NeuralVarEngine.kt`

use std::env;
use std::fs::{self, File};
use std::io::{BufReader, BufWriter, Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

const IN_CH: usize = 16;
const HID_CH: usize = 24;
const OUT_CH: usize = 2;
const NUM_BLOCKS: usize = 4;
const DILATIONS: [usize; NUM_BLOCKS] = [1, 2, 4, 8];

// Parameter offsets in flat contiguous vector (total = 9,866 floats = 39,464 bytes + 44 header = 39,508 bytes)
const SZ_IN_W: usize = IN_CH * HID_CH; // 384
const SZ_IN_B: usize = HID_CH; // 24
const SZ_CW: usize = 3 * HID_CH * HID_CH; // 1728
const SZ_CB: usize = HID_CH; // 24
const SZ_PW: usize = HID_CH * HID_CH; // 576
const SZ_PB: usize = HID_CH; // 24
const SZ_BLOCK: usize = SZ_CW + SZ_CB + SZ_PW + SZ_PB; // 2352
const SZ_OUT_W: usize = HID_CH * OUT_CH; // 48
const SZ_OUT_B: usize = OUT_CH; // 2
const TOTAL_PARAMS: usize = SZ_IN_W + SZ_IN_B + NUM_BLOCKS * SZ_BLOCK + SZ_OUT_W + SZ_OUT_B; // 9866

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
        // Async-signal-safe minimal stderr notice
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
        signal(2, handle_sigint); // SIGINT
        signal(15, handle_sigint); // SIGTERM
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
            // Static standing / idle camera
            for t in 0..len {
                for c in 0..12 {
                    f[t * IN_CH + c] = rng.normal(0.03);
                }
                f[t * IN_CH + 12] = 0.85;
            }
        } else if kind == 1 {
            // Slow camera sway / walking toward camera
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
            // Arm waving / rope adjusting (wrists move, ankles stay on ground)
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
            // Extract real IG-outro / walk-away tails where target[:, 1] < 0.1
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

    // 1. Time-Stretching / Speed Warping (0.78x - 1.30x, 45% chance)
    if len >= 48 && rng.f32() < 0.45 {
        let speed = rng.uniform(0.78, 1.30);
        let new_len = ((len as f32 / speed).round() as usize).max(32);
        f = resample_linear(&f, len, new_len, IN_CH);
        y = resample_linear(&y, len, new_len, OUT_CH);
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

    // 4. Random Landmark Occlusion / Dropout (15% chance)
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

#[derive(Clone)]
struct DilatedTcnModel {
    causal: bool,
    params: Vec<f32>,
    m: Vec<f32>,
    v: Vec<f32>,
    step_num: i64,
}

/// Reusable scratch buffers to eliminate heap allocations inside the inner training loop.
struct Workspace {
    z_in: Vec<f32>,
    h_layers: [Vec<f32>; NUM_BLOCKS + 1],
    zc: [Vec<f32>; NUM_BLOCKS],
    ac: [Vec<f32>; NUM_BLOCKS],
    probs: Vec<f32>,
    dlogits: Vec<f32>,
    dh: Vec<f32>,
    dh_next: Vec<f32>,
    dac: Vec<f32>,
    dzc: Vec<f32>,
    grads: Vec<f32>,
}

impl Workspace {
    fn new() -> Self {
        Self {
            z_in: Vec::new(),
            h_layers: std::array::from_fn(|_| Vec::new()),
            zc: std::array::from_fn(|_| Vec::new()),
            ac: std::array::from_fn(|_| Vec::new()),
            probs: Vec::new(),
            dlogits: Vec::new(),
            dh: Vec::new(),
            dh_next: Vec::new(),
            dac: Vec::new(),
            dzc: Vec::new(),
            grads: vec![0.0f32; TOTAL_PARAMS],
        }
    }

    fn ensure(&mut self, len: usize) {
        let th = len * HID_CH;
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

#[inline(always)]
fn blk_cw_off(b: usize) -> usize {
    SZ_IN_W + SZ_IN_B + b * SZ_BLOCK
}
#[inline(always)]
fn blk_cb_off(b: usize) -> usize {
    blk_cw_off(b) + SZ_CW
}
#[inline(always)]
fn blk_pw_off(b: usize) -> usize {
    blk_cb_off(b) + SZ_CB
}
#[inline(always)]
fn blk_pb_off(b: usize) -> usize {
    blk_pw_off(b) + SZ_PW
}
#[inline(always)]
fn out_w_off() -> usize {
    SZ_IN_W + SZ_IN_B + NUM_BLOCKS * SZ_BLOCK
}
#[inline(always)]
fn out_b_off() -> usize {
    out_w_off() + SZ_OUT_W
}

impl DilatedTcnModel {
    fn new(causal: bool, seed: u64) -> Self {
        let mut rng = Rng::new(seed);
        let mut params = vec![0.0f32; TOTAL_PARAMS];

        let in_scale = (2.0f32 / IN_CH as f32).sqrt();
        for x in &mut params[0..SZ_IN_W] {
            *x = rng.normal(in_scale);
        }
        let cw_scale = (2.0f32 / (3 * HID_CH) as f32).sqrt();
        let pw_scale = (1.0f32 / HID_CH as f32).sqrt();
        for b in 0..NUM_BLOCKS {
            let cw0 = blk_cw_off(b);
            for x in &mut params[cw0..cw0 + SZ_CW] {
                *x = rng.normal(cw_scale);
            }
            let pw0 = blk_pw_off(b);
            for x in &mut params[pw0..pw0 + SZ_PW] {
                *x = rng.normal(pw_scale);
            }
        }
        let ow0 = out_w_off();
        for x in &mut params[ow0..ow0 + SZ_OUT_W] {
            *x = rng.normal(pw_scale);
        }
        let ob0 = out_b_off();
        params[ob0] = -2.2;
        params[ob0 + 1] = 0.0;

        Self {
            causal,
            params,
            m: vec![0.0f32; TOTAL_PARAMS],
            v: vec![0.0f32; TOTAL_PARAMS],
            step_num: 0,
        }
    }

    /// Exact analytical forward + backward pass with Focal Hard-Example Mining & Teacher Distillation.
    fn forward_and_backward(
        &self,
        len: usize,
        x: &[f32],
        y_true: &[f32],
        teacher_probs: Option<&[f32]>,
        distill_alpha: f32,
        ws: &mut Workspace,
    ) {
        #[inline(always)]
        fn gemm_nn_acc_24<const K: usize>(a: &[f32], b: &[f32], c: &mut [f32], m: usize) {
            let m4 = m & !3;
            let mut t = 0;
            while t < m4 {
                let mut c0 = [0.0f32; HID_CH];
                let mut c1 = [0.0f32; HID_CH];
                let mut c2 = [0.0f32; HID_CH];
                let mut c3 = [0.0f32; HID_CH];
                c0.copy_from_slice(&c[t * HID_CH..(t + 1) * HID_CH]);
                c1.copy_from_slice(&c[(t + 1) * HID_CH..(t + 2) * HID_CH]);
                c2.copy_from_slice(&c[(t + 2) * HID_CH..(t + 3) * HID_CH]);
                c3.copy_from_slice(&c[(t + 3) * HID_CH..(t + 4) * HID_CH]);

                let a0 = &a[t * K..(t + 1) * K];
                let a1 = &a[(t + 1) * K..(t + 2) * K];
                let a2 = &a[(t + 2) * K..(t + 3) * K];
                let a3 = &a[(t + 3) * K..(t + 4) * K];

                for i in 0..K {
                    let v0 = a0[i];
                    let v1 = a1[i];
                    let v2 = a2[i];
                    let v3 = a3[i];
                    let br: &[f32; HID_CH] = b[i * HID_CH..(i + 1) * HID_CH].try_into().unwrap();
                    for j in 0..HID_CH {
                        let bv = br[j];
                        c0[j] += v0 * bv;
                        c1[j] += v1 * bv;
                        c2[j] += v2 * bv;
                        c3[j] += v3 * bv;
                    }
                }
                c[t * HID_CH..(t + 1) * HID_CH].copy_from_slice(&c0);
                c[(t + 1) * HID_CH..(t + 2) * HID_CH].copy_from_slice(&c1);
                c[(t + 2) * HID_CH..(t + 3) * HID_CH].copy_from_slice(&c2);
                c[(t + 3) * HID_CH..(t + 4) * HID_CH].copy_from_slice(&c3);
                t += 4;
            }
            while t < m {
                let mut c0 = [0.0f32; HID_CH];
                c0.copy_from_slice(&c[t * HID_CH..(t + 1) * HID_CH]);
                let a0 = &a[t * K..(t + 1) * K];
                for i in 0..K {
                    let v0 = a0[i];
                    let br: &[f32; HID_CH] = b[i * HID_CH..(i + 1) * HID_CH].try_into().unwrap();
                    for j in 0..HID_CH {
                        c0[j] += v0 * br[j];
                    }
                }
                c[t * HID_CH..(t + 1) * HID_CH].copy_from_slice(&c0);
                t += 1;
            }
        }

        #[inline(always)]
        fn gemm_tn_acc_24<const K: usize>(a: &[f32], b: &[f32], c: &mut [f32], m: usize) {
            let mut acc = [[0.0f32; HID_CH]; K];
            for t in 0..m {
                let ar: &[f32; K] = a[t * K..(t + 1) * K].try_into().unwrap();
                let br: &[f32; HID_CH] = b[t * HID_CH..(t + 1) * HID_CH].try_into().unwrap();
                for i in 0..K {
                    let av = ar[i];
                    let row = &mut acc[i];
                    for j in 0..HID_CH {
                        row[j] += av * br[j];
                    }
                }
            }
            for i in 0..K {
                let dst = &mut c[i * HID_CH..(i + 1) * HID_CH];
                for j in 0..HID_CH {
                    dst[j] += acc[i][j];
                }
            }
        }

        #[inline(always)]
        fn transpose_24(w: &[f32]) -> [f32; HID_CH * HID_CH] {
            let mut wt = [0.0f32; HID_CH * HID_CH];
            for i in 0..HID_CH {
                for j in 0..HID_CH {
                    wt[j * HID_CH + i] = w[i * HID_CH + j];
                }
            }
            wt
        }

        ws.ensure(len);
        ws.grads.fill(0.0);
        let p = &self.params;

        // 1. Input projection: z_in = x @ in_w + in_b, h0 = relu(z_in)
        let in_w = &p[0..SZ_IN_W];
        let in_b = &p[SZ_IN_W..SZ_IN_W + SZ_IN_B];
        for t in 0..len {
            ws.z_in[t * HID_CH..(t + 1) * HID_CH].copy_from_slice(in_b);
        }
        gemm_nn_acc_24::<IN_CH>(x, in_w, &mut ws.z_in[..len * HID_CH], len);
        for idx in 0..(len * HID_CH) {
            ws.h_layers[0][idx] = ws.z_in[idx].max(0.0);
        }

        // 2. Dilated residual TCN blocks (contiguous shifted GEMM per tap!)
        for b in 0..NUM_BLOCKS {
            let d = DILATIONS[b];
            let cw = &p[blk_cw_off(b)..blk_cw_off(b) + SZ_CW];
            let cw0 = &cw[0..HID_CH * HID_CH];
            let cw1 = &cw[HID_CH * HID_CH..2 * HID_CH * HID_CH];
            let cw2 = &cw[2 * HID_CH * HID_CH..3 * HID_CH * HID_CH];
            let cb = &p[blk_cb_off(b)..blk_cb_off(b) + SZ_CB];
            let pw = &p[blk_pw_off(b)..blk_pw_off(b) + SZ_PW];
            let pb = &p[blk_pb_off(b)..blk_pb_off(b) + SZ_PB];

            let (left_h, right_h) = ws.h_layers.split_at_mut(b + 1);
            let h_prev = &left_h[b][..len * HID_CH];
            let h_next = &mut right_h[0][..len * HID_CH];
            let zc_buf = &mut ws.zc[b][..len * HID_CH];
            let ac_buf = &mut ws.ac[b][..len * HID_CH];

            for t in 0..len {
                zc_buf[t * HID_CH..(t + 1) * HID_CH].copy_from_slice(cb);
            }
            if self.causal {
                if len > 2 * d {
                    let m = len - 2 * d;
                    gemm_nn_acc_24::<HID_CH>(&h_prev[..m * HID_CH], cw0, &mut zc_buf[2 * d * HID_CH..], m);
                }
                if len > d {
                    let m = len - d;
                    gemm_nn_acc_24::<HID_CH>(&h_prev[..m * HID_CH], cw1, &mut zc_buf[d * HID_CH..], m);
                }
                gemm_nn_acc_24::<HID_CH>(h_prev, cw2, zc_buf, len);
            } else {
                if len > d {
                    let m = len - d;
                    gemm_nn_acc_24::<HID_CH>(&h_prev[..m * HID_CH], cw0, &mut zc_buf[d * HID_CH..], m);
                    gemm_nn_acc_24::<HID_CH>(&h_prev[d * HID_CH..], cw2, &mut zc_buf[..m * HID_CH], m);
                }
                gemm_nn_acc_24::<HID_CH>(h_prev, cw1, zc_buf, len);
            }

            for idx in 0..(len * HID_CH) {
                ac_buf[idx] = zc_buf[idx].max(0.0);
            }
            for t in 0..len {
                let hp = &h_prev[t * HID_CH..(t + 1) * HID_CH];
                let hn = &mut h_next[t * HID_CH..(t + 1) * HID_CH];
                for j in 0..HID_CH {
                    hn[j] = hp[j] + pb[j];
                }
            }
            gemm_nn_acc_24::<HID_CH>(ac_buf, pw, h_next, len);
            for idx in 0..(len * HID_CH) {
                h_next[idx] = h_next[idx].max(0.0);
            }
        }

        // 3. Output head + Focal Hard-Example Mining + Distillation gradient
        let ow = &p[out_w_off()..out_w_off() + SZ_OUT_W];
        let ob = &p[out_b_off()..out_b_off() + SZ_OUT_B];
        let h_last = &ws.h_layers[NUM_BLOCKS];
        let inv_norm = 1.0f32 / (len * OUT_CH) as f32;

        for t in 0..len {
            let hr = &h_last[t * HID_CH..(t + 1) * HID_CH];
            let mut l0 = ob[0];
            let mut l1 = ob[1];
            for i in 0..HID_CH {
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

            let inactive = 1.0 - yt1;
            let hard_boost = if inactive > 0.5 && p0 > 0.25 { 1.8 } else { 0.0 };
            let inact_pen = inactive * 1.4 + hard_boost;
            let bw0 = 1.0 + yt0 * 1.8 + inact_pen;
            let bw1 = 1.0 + yt1 * 0.4 + inact_pen * 0.5;

            let pt0 = if yt0 > 0.5 { p0 } else { 1.0 - p0 };
            let pt1 = if yt1 > 0.5 { p1 } else { 1.0 - p1 };
            let f0 = 0.45 + 0.95 * (1.0 - pt0).clamp(0.0, 1.0);
            let f1 = 0.45 + 0.95 * (1.0 - pt1).clamp(0.0, 1.0);

            ws.dlogits[t * OUT_CH] = (bw0 * f0 * (p0 - ye0)) * inv_norm;
            ws.dlogits[t * OUT_CH + 1] = (bw1 * f1 * (p1 - ye1)) * inv_norm;
        }

        // 4. Backward through output head
        let g_ow = &mut ws.grads[out_w_off()..out_w_off() + SZ_OUT_W];
        let mut g_ob0 = 0.0f32;
        let mut g_ob1 = 0.0f32;
        for t in 0..len {
            let d0 = ws.dlogits[t * OUT_CH];
            let d1 = ws.dlogits[t * OUT_CH + 1];
            g_ob0 += d0;
            g_ob1 += d1;
            let hr = &h_last[t * HID_CH..(t + 1) * HID_CH];
            let dhr = &mut ws.dh[t * HID_CH..(t + 1) * HID_CH];
            for i in 0..HID_CH {
                let hv = hr[i];
                g_ow[i * OUT_CH] += hv * d0;
                g_ow[i * OUT_CH + 1] += hv * d1;
                dhr[i] = d0 * ow[i * OUT_CH] + d1 * ow[i * OUT_CH + 1];
            }
        }
        ws.grads[out_b_off()] = g_ob0;
        ws.grads[out_b_off() + 1] = g_ob1;

        // 5. Backward through dilated residual blocks (register-blocked SIMD GEMM!)
        for b in (0..NUM_BLOCKS).rev() {
            let d = DILATIONS[b];
            let cw = &p[blk_cw_off(b)..blk_cw_off(b) + SZ_CW];
            let cw0 = &cw[0..HID_CH * HID_CH];
            let cw1 = &cw[HID_CH * HID_CH..2 * HID_CH * HID_CH];
            let cw2 = &cw[2 * HID_CH * HID_CH..3 * HID_CH * HID_CH];
            let pw = &p[blk_pw_off(b)..blk_pw_off(b) + SZ_PW];

            let h_prev = &ws.h_layers[b][..len * HID_CH];
            let h_out = &ws.h_layers[b + 1][..len * HID_CH];
            let zc_buf = &ws.zc[b][..len * HID_CH];
            let ac_buf = &ws.ac[b][..len * HID_CH];

            let (g_cw_slice, rest) = ws.grads[blk_cw_off(b)..blk_cw_off(b) + SZ_BLOCK].split_at_mut(SZ_CW);
            let (g_cb, rest2) = rest.split_at_mut(SZ_CB);
            let (g_pw, g_pb) = rest2.split_at_mut(SZ_PW);
            let (g_cw0, g_cw12) = g_cw_slice.split_at_mut(HID_CH * HID_CH);
            let (g_cw1, g_cw2) = g_cw12.split_at_mut(HID_CH * HID_CH);

            // dh_pre = dh * (h_out > 0); store in ws.dh_next (also starts residual gradient!)
            for t in 0..len {
                let base = t * HID_CH;
                for j in 0..HID_CH {
                    let v = if h_out[base + j] > 0.0 { ws.dh[base + j] } else { 0.0 };
                    ws.dh[base + j] = v;
                    ws.dh_next[base + j] = v;
                    g_pb[j] += v;
                }
            }

            // g_pw += ac^T @ dh_pre
            gemm_tn_acc_24::<HID_CH>(ac_buf, &ws.dh[..len * HID_CH], g_pw, len);

            // dac = dh_pre @ pw^T
            ws.dac[..len * HID_CH].fill(0.0);
            let pw_t = transpose_24(pw);
            gemm_nn_acc_24::<HID_CH>(&ws.dh[..len * HID_CH], &pw_t, &mut ws.dac[..len * HID_CH], len);

            // dzc = dac * (zc > 0), g_cb += sum(dzc)
            for t in 0..len {
                let base = t * HID_CH;
                for j in 0..HID_CH {
                    let v = if zc_buf[base + j] > 0.0 { ws.dac[base + j] } else { 0.0 };
                    ws.dzc[base + j] = v;
                    g_cb[j] += v;
                }
            }

            let dzc_slice = &ws.dzc[..len * HID_CH];
            let dh_next = &mut ws.dh_next[..len * HID_CH];

            if self.causal {
                if len > 2 * d {
                    let m = len - 2 * d;
                    gemm_tn_acc_24::<HID_CH>(&h_prev[..m * HID_CH], &dzc_slice[2 * d * HID_CH..], g_cw0, m);
                    let cw0_t = transpose_24(cw0);
                    gemm_nn_acc_24::<HID_CH>(&dzc_slice[2 * d * HID_CH..], &cw0_t, &mut dh_next[..m * HID_CH], m);
                }
                if len > d {
                    let m = len - d;
                    gemm_tn_acc_24::<HID_CH>(&h_prev[..m * HID_CH], &dzc_slice[d * HID_CH..], g_cw1, m);
                    let cw1_t = transpose_24(cw1);
                    gemm_nn_acc_24::<HID_CH>(&dzc_slice[d * HID_CH..], &cw1_t, &mut dh_next[..m * HID_CH], m);
                }
                gemm_tn_acc_24::<HID_CH>(h_prev, dzc_slice, g_cw2, len);
                let cw2_t = transpose_24(cw2);
                gemm_nn_acc_24::<HID_CH>(dzc_slice, &cw2_t, dh_next, len);
            } else {
                if len > d {
                    let m = len - d;
                    gemm_tn_acc_24::<HID_CH>(&h_prev[..m * HID_CH], &dzc_slice[d * HID_CH..], g_cw0, m);
                    let cw0_t = transpose_24(cw0);
                    gemm_nn_acc_24::<HID_CH>(&dzc_slice[d * HID_CH..], &cw0_t, &mut dh_next[..m * HID_CH], m);

                    gemm_tn_acc_24::<HID_CH>(&h_prev[d * HID_CH..], &dzc_slice[..m * HID_CH], g_cw2, m);
                    let cw2_t = transpose_24(cw2);
                    gemm_nn_acc_24::<HID_CH>(&dzc_slice[..m * HID_CH], &cw2_t, &mut dh_next[d * HID_CH..], m);
                }
                gemm_tn_acc_24::<HID_CH>(h_prev, dzc_slice, g_cw1, len);
                let cw1_t = transpose_24(cw1);
                gemm_nn_acc_24::<HID_CH>(dzc_slice, &cw1_t, dh_next, len);
            }

            std::mem::swap(&mut ws.dh, &mut ws.dh_next);
        }

        // 6. Backward through input projection
        let (g_in_w, g_in_b) = ws.grads[0..SZ_IN_W + SZ_IN_B].split_at_mut(SZ_IN_W);
        for t in 0..len {
            let base = t * HID_CH;
            for j in 0..HID_CH {
                let v = if ws.z_in[base + j] > 0.0 { ws.dh[base + j] } else { 0.0 };
                ws.dzc[base + j] = v;
                g_in_b[j] += v;
            }
        }
        gemm_tn_acc_24::<IN_CH>(x, &ws.dzc[..len * HID_CH], g_in_w, len);
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

        for idx in 0..TOTAL_PARAMS {
            let g = grads[idx].clamp(-2.0, 2.0);
            let m = b1 * self.m[idx] + (1.0 - b1) * g;
            let v = b2 * self.v[idx] + (1.0 - b2) * g * g;
            self.m[idx] = m;
            self.v[idx] = v;

            // Apply decoupled weight decay to weight matrices only (skip biases)
            let is_bias = (idx >= SZ_IN_W && idx < SZ_IN_W + SZ_IN_B)
                || idx >= out_b_off()
                || (idx >= SZ_IN_W + SZ_IN_B && idx < out_w_off() && {
                    let rem = (idx - (SZ_IN_W + SZ_IN_B)) % SZ_BLOCK;
                    (rem >= SZ_CW && rem < SZ_CW + SZ_CB) || rem >= SZ_CW + SZ_CB + SZ_PW
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
        let in_w = &p[0..SZ_IN_W];
        let in_b = &p[SZ_IN_W..SZ_IN_W + SZ_IN_B];
        let mut h = vec![0.0f32; len * HID_CH];
        let mut h_next = vec![0.0f32; len * HID_CH];

        for t in 0..len {
            let xr = &x[t * IN_CH..(t + 1) * IN_CH];
            let hr = &mut h[t * HID_CH..(t + 1) * HID_CH];
            hr.copy_from_slice(in_b);
            for i in 0..IN_CH {
                let xv = xr[i];
                let wr = &in_w[i * HID_CH..(i + 1) * HID_CH];
                for j in 0..HID_CH {
                    hr[j] += xv * wr[j];
                }
            }
            for j in 0..HID_CH {
                hr[j] = hr[j].max(0.0);
            }
        }

        for b in 0..NUM_BLOCKS {
            let d = DILATIONS[b];
            let cw = &p[blk_cw_off(b)..blk_cw_off(b) + SZ_CW];
            let cw0 = &cw[0..HID_CH * HID_CH];
            let cw1 = &cw[HID_CH * HID_CH..2 * HID_CH * HID_CH];
            let cw2 = &cw[2 * HID_CH * HID_CH..3 * HID_CH * HID_CH];
            let cb = &p[blk_cb_off(b)..blk_cb_off(b) + SZ_CB];
            let pw = &p[blk_pw_off(b)..blk_pw_off(b) + SZ_PW];
            let pb = &p[blk_pb_off(b)..blk_pb_off(b) + SZ_PB];

            for t in 0..len {
                let mut ac = [0.0f32; HID_CH];
                ac.copy_from_slice(cb);
                if self.causal {
                    if t >= 2 * d {
                        let s0 = &h[(t - 2 * d) * HID_CH..(t - 2 * d + 1) * HID_CH];
                        for i in 0..HID_CH {
                            let sv = s0[i];
                            let wr = &cw0[i * HID_CH..(i + 1) * HID_CH];
                            for j in 0..HID_CH {
                                ac[j] += sv * wr[j];
                            }
                        }
                    }
                    if t >= d {
                        let s1 = &h[(t - d) * HID_CH..(t - d + 1) * HID_CH];
                        for i in 0..HID_CH {
                            let sv = s1[i];
                            let wr = &cw1[i * HID_CH..(i + 1) * HID_CH];
                            for j in 0..HID_CH {
                                ac[j] += sv * wr[j];
                            }
                        }
                    }
                    let s2 = &h[t * HID_CH..(t + 1) * HID_CH];
                    for i in 0..HID_CH {
                        let sv = s2[i];
                        let wr = &cw2[i * HID_CH..(i + 1) * HID_CH];
                        for j in 0..HID_CH {
                            ac[j] += sv * wr[j];
                        }
                    }
                } else {
                    if t >= d {
                        let s0 = &h[(t - d) * HID_CH..(t - d + 1) * HID_CH];
                        for i in 0..HID_CH {
                            let sv = s0[i];
                            let wr = &cw0[i * HID_CH..(i + 1) * HID_CH];
                            for j in 0..HID_CH {
                                ac[j] += sv * wr[j];
                            }
                        }
                    }
                    let s1 = &h[t * HID_CH..(t + 1) * HID_CH];
                    for i in 0..HID_CH {
                        let sv = s1[i];
                        let wr = &cw1[i * HID_CH..(i + 1) * HID_CH];
                        for j in 0..HID_CH {
                            ac[j] += sv * wr[j];
                        }
                    }
                    if t + d < len {
                        let s2 = &h[(t + d) * HID_CH..(t + d + 1) * HID_CH];
                        for i in 0..HID_CH {
                            let sv = s2[i];
                            let wr = &cw2[i * HID_CH..(i + 1) * HID_CH];
                            for j in 0..HID_CH {
                                ac[j] += sv * wr[j];
                            }
                        }
                    }
                }
                for j in 0..HID_CH {
                    ac[j] = ac[j].max(0.0);
                }
                let hp = &h[t * HID_CH..(t + 1) * HID_CH];
                let hn = &mut h_next[t * HID_CH..(t + 1) * HID_CH];
                for j in 0..HID_CH {
                    hn[j] = hp[j] + pb[j];
                }
                for i in 0..HID_CH {
                    let av = ac[i];
                    let wr = &pw[i * HID_CH..(i + 1) * HID_CH];
                    for j in 0..HID_CH {
                        hn[j] += av * wr[j];
                    }
                }
                for j in 0..HID_CH {
                    hn[j] = hn[j].max(0.0);
                }
            }
            std::mem::swap(&mut h, &mut h_next);
        }

        let ow = &p[out_w_off()..out_w_off() + SZ_OUT_W];
        let ob = &p[out_b_off()..out_b_off() + SZ_OUT_B];
        let mut probs = vec![0.0f32; len * OUT_CH];
        for t in 0..len {
            let hr = &h[t * HID_CH..(t + 1) * HID_CH];
            let mut l0 = ob[0];
            let mut l1 = ob[1];
            for i in 0..HID_CH {
                let hv = hr[i];
                l0 += hv * ow[i * OUT_CH];
                l1 += hv * ow[i * OUT_CH + 1];
            }
            probs[t * OUT_CH] = 1.0 / (1.0 + (-l0.clamp(-15.0, 15.0)).exp());
            probs[t * OUT_CH + 1] = 1.0 / (1.0 + (-l1.clamp(-15.0, 15.0)).exp());
        }
        probs
    }

    /// Atomic binary `.jvxmodel` export (`JVX7` format, 39,508 bytes, 100% identical to Kotlin `NeuralVarEngine.kt`).
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
                HID_CH as i32,
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

        let read_vec = |r: &mut R| -> std::io::Result<Vec<f32>> {
            let mut raw = vec![0u8; TOTAL_PARAMS * 4];
            r.read_exact(&mut raw)?;
            let mut out = Vec::with_capacity(TOTAL_PARAMS);
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

fn train_model_on_split(
    train_seqs: &[&Sequence],
    neg_seqs: &[Sequence],
    causal: bool,
    epochs: usize,
    seed: u64,
    init_model: Option<DilatedTcnModel>,
    lr_max: f32,
    lr_min: f32,
    teacher: Option<&DilatedTcnModel>,
) -> DilatedTcnModel {
    let mut rng = Rng::new(seed);
    let mut model = init_model.unwrap_or_else(|| DilatedTcnModel::new(causal, seed));
    let mut ws = Workspace::new();

    let n_pos = train_seqs.len();
    let total = n_pos + neg_seqs.len();

    // Precompute teacher soft targets on TRAINING split ONLY (zero validation leakage!)
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
                model.forward_and_backward(alen, &af, &ay, atp.as_deref(), 0.30, &mut ws);
            } else {
                model.forward_and_backward(seq.len, &seq.feats, &seq.targets, tp_ref, 0.30, &mut ws);
            }
            model.step_adamw(&ws.grads, lr, 1e-4);
        }
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
    fold_bi: Vec<Option<DilatedTcnModel>>,
    fold_ca: Vec<Option<DilatedTcnModel>>,
    prod_bi: Option<DilatedTcnModel>,
    prod_ca: Option<DilatedTcnModel>,
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
            w.write_all(&1u32.to_le_bytes()).map_err(|e| e.to_string())?;
            w.write_all(&self.meta.round_num.to_le_bytes()).map_err(|e| e.to_string())?;
            w.write_all(&self.meta.best_bi_f1.to_le_bytes()).map_err(|e| e.to_string())?;
            w.write_all(&self.meta.best_bi_prec.to_le_bytes()).map_err(|e| e.to_string())?;
            w.write_all(&self.meta.best_bi_rec.to_le_bytes()).map_err(|e| e.to_string())?;
            w.write_all(&(self.meta.best_bi_outro_fp as u64).to_le_bytes()).map_err(|e| e.to_string())?;
            w.write_all(&self.meta.best_ca_f1.to_le_bytes()).map_err(|e| e.to_string())?;
            w.write_all(&self.meta.best_ca_prec.to_le_bytes()).map_err(|e| e.to_string())?;
            w.write_all(&self.meta.best_ca_rec.to_le_bytes()).map_err(|e| e.to_string())?;
            w.write_all(&(self.meta.best_ca_outro_fp as u64).to_le_bytes()).map_err(|e| e.to_string())?;

            let write_opt = |w: &mut BufWriter<File>, opt: &Option<DilatedTcnModel>| -> std::io::Result<()> {
                if let Some(m) = opt {
                    w.write_all(&[1u8])?;
                    m.write_ckpt(w)?;
                } else {
                    w.write_all(&[0u8])?;
                }
                Ok(())
            };
            w.write_all(&(self.fold_bi.len() as u32).to_le_bytes()).map_err(|e| e.to_string())?;
            for m in &self.fold_bi {
                write_opt(&mut w, m).map_err(|e| e.to_string())?;
            }
            for m in &self.fold_ca {
                write_opt(&mut w, m).map_err(|e| e.to_string())?;
            }
            write_opt(&mut w, &self.prod_bi).map_err(|e| e.to_string())?;
            write_opt(&mut w, &self.prod_ca).map_err(|e| e.to_string())?;

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
            r.read_exact(&mut u32b)?; // version

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
            let read_opt = |r: &mut BufReader<File>| -> std::io::Result<Option<DilatedTcnModel>> {
                let mut flag = [0u8; 1];
                r.read_exact(&mut flag)?;
                if flag[0] == 1 {
                    Ok(Some(DilatedTcnModel::read_ckpt(r)?))
                } else {
                    Ok(None)
                }
            };
            let mut fold_bi = Vec::with_capacity(nf);
            for _ in 0..nf {
                fold_bi.push(read_opt(&mut r)?);
            }
            let mut fold_ca = Vec::with_capacity(nf);
            for _ in 0..nf {
                fold_ca.push(read_opt(&mut r)?);
            }
            let prod_bi = read_opt(&mut r)?;
            let prod_ca = read_opt(&mut r)?;
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
        "Jumpvex v7 (Rust SIMD Trainer) — Best Continuous Neural VAR Record\n\
         ==================================================================\n\
         Updated Unix TS      : {}\n\
         Completed Rounds     : {}\n\n\
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

    let mut jvxbin = PathBuf::from("training/checkpoints/features64.jvxbin");
    let mut export_dir = PathBuf::from("app/src/main/assets/models");
    let mut ckpt_dir = PathBuf::from("training/checkpoints");
    let mut target_rounds: u64 = 0; // 0 = continuous infinite until Ctrl+C
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
                // Always uses 5 native OS threads for 5 folds simultaneously
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
    println!("  Jumpvex v7.1 — RUST SIMD All-Core Continuous Neural VAR Trainer");
    println!("================================================================================");
    println!("  Engine          : Pure Rust Native SIMD (zero Python overhead, 5 parallel fold threads)");
    println!("  CPU Cores       : {} detected", cores);
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
        println!("[Reset] Cleared previous Rust checkpoint; starting fresh from Round 1.");
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
        let lr_max = (3.5e-3f32 * 0.94f32.powi((round_num - 1) as i32)).max(1.2e-3);
        let lr_min = 1.5e-4f32;

        println!(
            "\n--- [Round {}] 5-Fold Zero-Leakage CV + Distillation (Rust SIMD | TimeStretch + CutMix + Focal | lr_max={:.5}) ---",
            round_num, lr_max
        );

        let mut prev_fold_bi: Vec<Option<DilatedTcnModel>> = ckpt.fold_bi.drain(..).collect();
        let mut prev_fold_ca: Vec<Option<DilatedTcnModel>> = ckpt.fold_ca.drain(..).collect();

        // Run all 5 folds in parallel using native OS threads with zero-copy shared `&sequences`!
        let fold_results: Vec<(usize, DilatedTcnModel, DilatedTcnModel, Vec<(usize, Vec<f32>)>, Vec<(usize, Vec<f32>)>, usize)> =
            thread::scope(|scope| {
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

                        let bi_model = train_model_on_split(
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
                        let ca_model = train_model_on_split(
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
            "  Tier 2 VAR Bi-TCN  : P={:.2}% | R={:.2}% | F1={:.2}% | OutroFP={}{}",
            bi_p * 100.0,
            bi_r * 100.0,
            bi_f1 * 100.0,
            bi_fp,
            bi_tag
        );
        println!(
            "  Tier 1 Live Causal : P={:.2}% | R={:.2}% | F1={:.2}% | OutroFP={}{}",
            ca_p * 100.0,
            ca_r * 100.0,
            ca_f1 * 100.0,
            ca_fp,
            ca_tag
        );
        println!("  Rust SIMD Speed    : {:.2}s ({:.1} seq-epochs/s)", cv_sec, sps);
        println!("==========================================================================");

        let all_refs: Vec<&Sequence> = sequences.iter().collect();
        let mut exported_any = false;

        if need_export_bi {
            println!("[Best-Only Export] Updating Tier 2 VAR Bi-TCN production model on all 64 videos...");
            let mut neg_rng = Rng::new(777 + round_num * 1000 + 9973);
            let all_neg = make_hard_negative_sequences(&sequences, &mut neg_rng);
            let prod_bi = train_model_on_split(
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
                " (distilled from Tier 2 Teacher)"
            } else {
                ""
            };
            println!(
                "[Best-Only Export] Updating Tier 1 Live Causal production model{}...",
                distill_msg
            );
            let mut neg_rng = Rng::new(888 + round_num * 1000 + 9973);
            let all_neg = make_hard_negative_sequences(&sequences, &mut neg_rng);
            let prod_ca = train_model_on_split(
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
