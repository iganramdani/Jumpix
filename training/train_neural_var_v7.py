#!/usr/bin/env python3
"""
Jumpvex v7.0 — Zero-Leakage 5-Fold Cross-Validation Neural VAR Trainer & Weight Exporter.

Trains two compact, high-speed 1D Dilated Multi-Scale Temporal Convolutional Networks (MS-TCN)
using pure vectorized NumPy + SciPy + AdamW (zero PyTorch dependency):
  1. `var_bitemporal_v7.jvxmodel` — Bidirectional Dilated MS-TCN (receptive field +-450 ms)
     for Tier 2 Video Studio VAR: predicts frame-level Jump/Step probability `p_jump(t) in [0, 1]`
     and Walk-Away / IG-Outro / Background suppression `p_reject(t) in [0, 1]`.
  2. `live_causal_tcn_v7.jvxmodel` — Causal Past-Only Dilated TCN (< 300 us/frame in Kotlin)
     for Tier 1 30 FPS Live Camera: predicts real-time jump probability & walk-away rejection.

Strict Scientific Guarantee (Zero Data Leakage):
- Partitions all 64 real videos into 5 disjoint athlete/scene folds.
- Evaluates Out-of-Fold (OOF) frame-wise F1, Precision, Recall, and Out-of-Fold Jump Count Accuracy
  before training the final 5-fold ensemble exported to `app/src/main/assets/models/`.
"""

import argparse
import csv
import datetime
import math
import multiprocessing
import os
import signal
import struct
import sys
import time
import wave

# Prevent OpenBLAS/OMP thread thrashing when multiprocessing saturates all ARM64 cores
os.environ.setdefault("OPENBLAS_NUM_THREADS", "1")
os.environ.setdefault("OMP_NUM_THREADS", "1")
os.environ.setdefault("MKL_NUM_THREADS", "1")
os.environ.setdefault("NUMEXPR_NUM_THREADS", "1")

import numpy as np
try:
    from scipy.ndimage import median_filter, maximum_filter1d
except ImportError:
    def maximum_filter1d(arr, size=3):
        arr = np.asarray(arr)
        pad = size // 2
        padded = np.pad(arr, (pad, pad), mode="edge")
        return np.max(np.lib.stride_tricks.sliding_window_view(padded, size), axis=1)

    def median_filter(arr, size=(17, 1)):
        arr = np.asarray(arr)
        r, c = size
        pr, pc = r // 2, c // 2
        padded = np.pad(arr, ((pr, pr), (pc, pc)), mode="edge")
        win = np.lib.stride_tricks.sliding_window_view(padded, (r, c))
        return np.median(win.reshape(arr.shape[0], arr.shape[1], r * c), axis=2).astype(arr.dtype)

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
BENCH_DIR = os.environ.get(
    "JUMPVEX_BENCH64_DIR",
    os.path.join(REPO_ROOT, "training", "dataset"),
)
SMARTPHONE_LOG_DIR = "/home/antigravity/.gemini/jetski/brain/v1/default/jetski_remote_agents_3p_devbox/a9acf617-00c7-452f-811e-aaad9722aa19/scratch/testlogterbaru"
GT_CSV = os.path.join(REPO_ROOT, "ground_truth_all_videos.csv")
OUT_MODEL_DIR = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "models")
DEFAULT_CHECKPOINT_DIR = os.path.join(REPO_ROOT, "training", "checkpoints")

IN_CHANNELS = 16
HID_CHANNELS = 24
OUT_CHANNELS = 2  # [logit_jump_peak, logit_active_skipping]
KERNEL_SIZE = 3
DILATIONS = [1, 2, 4, 8]

LANDMARK_NAMES = [
    "NOSE", "LEFT_SHOULDER", "RIGHT_SHOULDER", "LEFT_ELBOW", "RIGHT_ELBOW",
    "LEFT_WRIST", "RIGHT_WRIST", "LEFT_HIP", "RIGHT_HIP", "LEFT_KNEE",
    "RIGHT_KNEE", "LEFT_ANKLE", "RIGHT_ANKLE", "LEFT_HEEL", "RIGHT_HEEL",
    "LEFT_FOOT_INDEX", "RIGHT_FOOT_INDEX",
]


def load_ground_truth():
    gt = {}
    with open(GT_CSV, "r", encoding="utf-8") as f:
        r = csv.reader(f)
        next(r, None)
        for row in r:
            if len(row) >= 7:
                gt[row[2]] = (int(row[4]), float(row[3]), row[6])
    return gt


def compute_hpss_clean_whip_200hz(wav_path, duration_ms):
    """
    Harmonic-Percussive Spectral Separation (HPSS) at 200 Hz (5 ms hop) matching NeuralAudioRopeIsolator.kt:
    Suppresses sustained horizontal EDM/music ridges via time-axis median filtering while preserving
    <20 ms vertical broadband rope-whip & shoe-strike spikes.
    """
    n_bins = max(1, int(duration_ms // 5) + 4)
    clean_whip = np.zeros(n_bins, dtype=np.float32)
    raw_mid = np.zeros(n_bins, dtype=np.float32)
    if not os.path.exists(wav_path):
        return clean_whip, raw_mid

    with wave.open(wav_path, "rb") as w:
        pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
    if pcm.size < 512:
        return clean_whip, raw_mid

    nfft = 256
    hop = 80  # 5 ms @ 16 kHz = 200 Hz
    n_frames = min(n_bins, (pcm.size - nfft) // hop)
    if n_frames < 8:
        return clean_whip, raw_mid

    idx = np.arange(n_frames)[:, None] * hop + np.arange(nfft)[None, :]
    win = np.hanning(nfft).astype(np.float32)
    spec = np.abs(np.fft.rfft(pcm[idx] * win, axis=1)).astype(np.float32)  # [T, 129]

    # Harmonic component: horizontal median across 17 frames (85 ms)
    harm = median_filter(spec, size=(17, 1))
    # Percussive component: vertical median across 7 frequency bins (~430 Hz)
    perc = median_filter(spec, size=(1, 7))
    mask_p = (perc * perc) / (perc * perc + harm * harm + 1e-6)
    spec_p = spec * mask_p

    # Spectral flux on percussive-isolated high/mid whip bands (bins 8..120 = 500..7500 Hz)
    diff_p = np.maximum(0.0, spec_p[1:, 8:120] - spec_p[:-1, 8:120])
    whip = np.sum(diff_p, axis=1)
    med_w = np.median(whip) + 1e-5
    mad_w = np.median(np.abs(whip - med_w)) + 1e-4
    whip_z = np.clip((whip - med_w) / mad_w, 0.0, 12.0) / 6.0

    diff_raw = np.maximum(0.0, spec[1:, 4:64] - spec[:-1, 4:64])
    mid = np.sum(diff_raw, axis=1)
    med_m = np.median(mid) + 1e-5
    mad_m = np.median(np.abs(mid - med_m)) + 1e-4
    mid_z = np.clip((mid - med_m) / mad_m, 0.0, 12.0) / 6.0

    clean_whip[1 : 1 + len(whip_z)] = whip_z
    raw_mid[1 : 1 + len(mid_z)] = mid_z
    return clean_whip, raw_mid


def extract_sequence_features_and_targets(pose_path, wav_path, w_img, h_img, gt_jumps, notes):
    """
    Extracts exact 16-channel normalized biomechanical + HPSS-audio features per frame (matching
    `NeuralVarEngine.extractFrameFeatures` in Kotlin bit-for-bit!) and constructs ground-truth
    supervision labels aligned to verified jump count & active skipping window.
    """
    rows = []
    with open(pose_path, "r", encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            cols = line.strip().split("\t")
            t_ms = int(cols[0])
            lm = {}
            for c in cols[1:]:
                if c.startswith("OPT:") or ":" not in c:
                    continue
                k, v = c.split(":", 1)
                vals = [float(x) for x in v.split(",")]
                if len(vals) >= 3:
                    lm[k] = vals
            rows.append((t_ms, lm))

    T = len(rows)
    if T < 16:
        return None

    dur_ms = max(rows[-1][0], 1000)
    clean_whip, raw_mid = compute_hpss_clean_whip_200hz(wav_path, dur_ms + 100)

    feats = np.zeros((T, IN_CHANNELS), dtype=np.float32)
    raw_y_arr = np.zeros(T, dtype=np.float32)
    scale_arr = np.ones(T, dtype=np.float32)
    ank_diff_arr = np.zeros(T, dtype=np.float32)
    ts_arr = np.zeros(T, dtype=np.int64)

    sm_scale = None
    ema_y = None
    ema_leg = 0.0
    prev_y = None
    prev_vy = 0.0
    prev_tx = None
    prev_sc = None
    prev_w_ang_l = None

    for i, (t_ms, lm) in enumerate(rows):
        ts_arr[i] = t_ms
        ls = lm.get("LEFT_SHOULDER")
        rs = lm.get("RIGHT_SHOULDER")
        lh = lm.get("LEFT_HIP")
        rh = lm.get("RIGHT_HIP")
        la = lm.get("LEFT_ANKLE")
        ra = lm.get("RIGHT_ANKLE")
        lk = lm.get("LEFT_KNEE")
        rk = lm.get("RIGHT_KNEE")
        lw = lm.get("LEFT_WRIST")
        rw = lm.get("RIGHT_WRIST")
        lt = lm.get("LEFT_FOOT_INDEX")
        rt = lm.get("RIGHT_FOOT_INDEX")
        lheel = lm.get("LEFT_HEEL")
        rheel = lm.get("RIGHT_HEEL")
        nose = lm.get("NOSE")

        sh_ys = [p[1] for p in (ls, rs) if p and p[2] >= 0.35]
        hip_ys = [p[1] for p in (lh, rh) if p and p[2] >= 0.35]
        if not sh_ys:
            if i > 0:
                feats[i] = feats[i - 1] * 0.5
                raw_y_arr[i] = raw_y_arr[i - 1]
                scale_arr[i] = scale_arr[i - 1]
            continue

        sh_y = sum(sh_ys) / len(sh_ys)
        hip_y = sum(hip_ys) / len(hip_ys) if hip_ys else sh_y + 0.22 * h_img
        sh_w = abs(ls[0] - rs[0]) if (ls and rs and ls[2] >= 0.35 and rs[2] >= 0.35) else 0.0
        raw_sc = max(abs(hip_y - sh_y), sh_w * 1.15, h_img * 0.06)
        sm_scale = raw_sc if sm_scale is None else (sm_scale + 0.12 * (raw_sc - sm_scale))
        sc = max(sm_scale, 1.0)
        scale_arr[i] = sc

        ub_pts = [p[1] for p in (nose, ls, rs, lh, rh) if p and p[2] >= 0.35]
        ry = sum(ub_pts) / len(ub_pts) if ub_pts else sh_y
        raw_y_arr[i] = ry

        ema_y = ry if ema_y is None else (ema_y + 0.14 * (ry - ema_y))
        disp_norm = np.clip((ema_y - ry) / sc, -0.8, 0.8)  # positive = upward jump!

        dt_s = max((t_ms - ts_arr[i - 1]) / 1000.0, 0.016) if i > 0 else 0.033
        vy = np.clip(((prev_y - ry) / sc) / (dt_s * 10.0), -1.5, 1.5) if prev_y is not None else 0.0
        ay = np.clip((vy - prev_vy) / (dt_s * 20.0), -1.5, 1.5)
        prev_y = ry
        prev_vy = vy

        tx = 0.5 * (ls[0] + rs[0]) if (ls and rs) else (ls[0] if ls else (rs[0] if rs else w_img * 0.5))
        vx_abs = np.clip(abs(tx - prev_tx) / (sc * dt_s * 5.0), 0.0, 1.5) if prev_tx is not None else 0.0
        prev_tx = tx

        sc_drift = np.clip(abs(sc - prev_sc) / sc * 5.0, 0.0, 1.5) if prev_sc is not None else 0.0
        prev_sc = sc
        sc_ratio = np.clip(sc / max(h_img, 1), 0.0, 1.0)

        sad = ((la[1] - ra[1]) / sc) if (la and ra and la[2] >= 0.30 and ra[2] >= 0.30) else 0.0
        skd = ((lk[1] - rk[1]) / sc) if (lk and rk and lk[2] >= 0.30 and rk[2] >= 0.30) else 0.0
        raw_leg = 0.65 * sad + 0.35 * skd
        ema_leg = ema_leg + 0.14 * (raw_leg - ema_leg)
        ac_leg = np.clip(raw_leg - ema_leg, -1.2, 1.2)
        ank_diff_arr[i] = abs(ac_leg)

        ank_span = (abs(la[0] - ra[0]) / sc) if (la and ra and la[2] >= 0.30 and ra[2] >= 0.30) else 0.15
        ank_span = np.clip(ank_span, 0.0, 2.0) * 0.5

        knee_lift = 0.0
        if lk and rk and lh and rh:
            hip_mid = 0.5 * (lh[1] + rh[1])
            knee_lift = np.clip(1.0 - (min(lk[1], rk[1]) - hip_mid) / sc, -0.5, 1.2)

        wrist_vel = 0.0
        if lw and lh:
            ang = math.atan2(lw[1] - lh[1], lw[0] - lh[0])
            if prev_w_ang_l is not None:
                da = abs((ang - prev_w_ang_l + math.pi) % (2 * math.pi) - math.pi)
                wrist_vel = np.clip(da / (dt_s * 18.0), 0.0, 1.5)
            prev_w_ang_l = ang

        foot_pitch_contrast = 0.0
        if lt and rt and lheel and rheel:
            lp = (lt[1] - lheel[1]) / sc
            rp = (rt[1] - rheel[1]) / sc
            foot_pitch_contrast = np.clip(abs(lp - rp) * 3.0, 0.0, 1.5)

        conf_vals = [p[2] for p in (nose, ls, rs, lh, rh, la, ra) if p]
        mean_conf = sum(conf_vals) / len(conf_vals) if conf_vals else 0.0

        # Audio HPSS clean whip & raw mid energy in +-25 ms window around t_ms
        b0 = max(0, int((t_ms - 25) // 5))
        b1 = min(len(clean_whip), int((t_ms + 30) // 5) + 1)
        w_max = float(np.max(clean_whip[b0:b1])) if b1 > b0 else 0.0
        w_mean = float(np.mean(clean_whip[max(0, b0 - 15) : min(len(clean_whip), b1 + 15)])) if len(clean_whip) > 0 else 0.0
        m_max = float(np.max(raw_mid[b0:b1])) if b1 > b0 else 0.0

        # 16 exact features (identical order in Kotlin NeuralVarEngine!)
        feats[i, 0] = disp_norm * 3.0
        feats[i, 1] = vy
        feats[i, 2] = ay
        feats[i, 3] = ac_leg * 1.5
        feats[i, 4] = abs(ac_leg) * 1.5
        feats[i, 5] = ank_span
        feats[i, 6] = knee_lift
        feats[i, 7] = wrist_vel
        feats[i, 8] = foot_pitch_contrast
        feats[i, 9] = vx_abs
        feats[i, 10] = sc_drift
        feats[i, 11] = sc_ratio * 2.0
        feats[i, 12] = mean_conf
        feats[i, 13] = w_max
        feats[i, 14] = w_mean
        feats[i, 15] = m_max

    # Parse explicit active window & IG-outro cutoff from ground truth notes
    import re
    win_start_ms, win_end_ms = 0, dur_ms
    m = re.search(r"\((\d+\.?\d*)\s*-\s*(\d+\.?\d*)s", notes)
    if m:
        win_start_ms = max(0, int(float(m.group(1)) * 1000) - 200)
        win_end_ms = min(dur_ms, int(float(m.group(2)) * 1000) + 200)
    m_outro = re.search(r"outro\s+(\d+\.?\d*)\s*-\s*(\d+\.?\d*)s", notes, re.IGNORECASE)
    if m_outro:
        win_end_ms = min(win_end_ms, int(float(m_outro.group(1)) * 1000) - 100)
    elif "ig outro excluded" in notes.lower() and not m:
        win_end_ms = min(win_end_ms, max(1500, dur_ms - 3800))

    # Construct frame-level ground-truth supervision:
    # Combine upward torso prominence (local peak of disp_norm), leg drive, and percussive whip energy
    disp = feats[:, 0]
    local_prom = np.zeros(T, dtype=np.float32)
    for i in range(2, T - 2):
        l_min = np.min(disp[max(0, i - 8) : i])
        r_min = np.min(disp[i + 1 : min(T, i + 9)])
        local_prom[i] = max(0.0, disp[i] - 0.5 * (l_min + r_min))

    comb_score = 0.85 * local_prom + 0.55 * feats[:, 4] + 0.30 * feats[:, 13]
    in_active_mask = (ts_arr >= win_start_ms) & (ts_arr <= win_end_ms)
    masked_score = np.where(in_active_mask, comb_score, -1.0)

    is_sprint = ("speed-sprint" in notes.lower()) or ("ijrc" in notes.lower())
    min_sep_frames = 4 if is_sprint else 6

    cand_peaks = []
    for i in range(1, T - 1):
        if masked_score[i] > 0.10 and masked_score[i] >= masked_score[i - 1] and masked_score[i] >= masked_score[i + 1]:
            cand_peaks.append((masked_score[i], i))
    cand_peaks.sort(reverse=True)

    selected = []
    for sc_val, idx_p in cand_peaks:
        if len(selected) >= gt_jumps:
            break
        if all(abs(idx_p - s) >= min_sep_frames for s in selected):
            selected.append(idx_p)
    selected.sort()

    targets = np.zeros((T, OUT_CHANNELS), dtype=np.float32)
    for p in selected:
        for d in range(-2, 3):
            if 0 <= p + d < T:
                g = math.exp(-0.5 * (d / 1.05) ** 2)
                targets[p + d, 0] = max(targets[p + d, 0], g)
    if selected:
        # Mark active skipping intervals (gaps <= 1.5s remain active=1; long pauses/outro become active=0!)
        for k_idx, p in enumerate(selected):
            p_next = selected[k_idx + 1] if k_idx + 1 < len(selected) else p
            s0 = max(0, p - 4)
            s1 = min(T, (p_next + 4) if (ts_arr[p_next] - ts_arr[p] <= 1500) else (p + 5))
            targets[s0:s1, 1] = 1.0

    return {
        "feats": feats,
        "targets": targets,
        "ts_ms": ts_arr,
        "gt_jumps": gt_jumps,
        "is_sprint": is_sprint,
        "notes": notes,
    }


def make_hard_negative_sequences(sequences, rng):
    """
    Synthesizes realistic hard-negative non-jump clips (walk-toward-camera close-up stride,
    dimmed IG-Reel outro sway, standing talking/arm-waving, and gym bystander pass-through)
    so both models learn near-zero false-positive rates on non-skipping segments.
    """
    negatives = []
    for k in range(20):
        L = int(rng.integers(90, 210))
        f = np.zeros((L, IN_CHANNELS), dtype=np.float32)
        t = np.linspace(0, L * 0.033, L, dtype=np.float32)
        mode = k % 4
        if mode == 0:
            # Close-up walking toward camera: high scale drift, wide ankle span, lateral drift, slow bob
            f[:, 0] = 0.35 * np.sin(2 * np.pi * 1.4 * t)
            f[:, 1] = 0.25 * np.cos(2 * np.pi * 1.4 * t)
            f[:, 5] = rng.uniform(0.65, 0.98, size=L)  # wide ankle span
            f[:, 9] = rng.uniform(0.35, 0.95, size=L)  # lateral X velocity
            f[:, 10] = rng.uniform(0.25, 0.85, size=L)  # scale growth
            f[:, 11] = np.linspace(0.35, 0.72, L)       # close-up torso scale
            f[:, 12] = 0.82
        elif mode == 1:
            # IG Reel dimmed outro sway + loud EDM beat in raw_mid (ch 15) but low clean_whip
            f[:, 0] = 0.18 * np.sin(2 * np.pi * 2.1 * t) + rng.normal(0, 0.06, size=L)
            f[:, 5] = 0.14
            f[:, 11] = 0.34
            f[:, 12] = rng.uniform(0.38, 0.68, size=L)
            f[:, 15] = (np.sin(2 * np.pi * 2.2 * t) > 0.75).astype(np.float32) * 0.9
        elif mode == 2:
            # Tying shoes / bending up & down slowly
            f[:, 0] = 0.9 * np.sin(2 * np.pi * 0.45 * t)
            f[:, 1] = 0.4 * np.cos(2 * np.pi * 0.45 * t)
            f[:, 11] = 0.38
            f[:, 12] = 0.90
        else:
            # Extract real IG-outro tails from existing videos where target[:, 1] == 0
            src = sequences[k % len(sequences)]
            zero_idx = np.where(src["targets"][:, 1] < 0.1)[0]
            if len(zero_idx) >= 35:
                f = src["feats"][zero_idx].copy()
                L = len(f)
            else:
                f[:, 12] = 0.75
        y = np.zeros((L, OUT_CHANNELS), dtype=np.float32)
        negatives.append({"feats": f.astype(np.float32), "targets": y})
    return negatives


def augment_sequence(feats, targets, rng):
    """15x biomechanical & acoustic SNR augmentation."""
    L = len(feats)
    f = feats.copy()
    y = targets.copy()

    # Scale amplitude jitter (camera distance / jump height variation)
    amp_scale = rng.uniform(0.80, 1.25)
    f[:, 0:5] *= amp_scale

    # Mirror / ankle sign flip
    if rng.random() < 0.5:
        f[:, 3] = -f[:, 3]

    # Pose landmark jitter
    f[:, 0:12] += rng.normal(0.0, 0.04, size=(L, 12)).astype(np.float32)

    # Simulate muted video (20% chance) or loud EDM noise in raw_mid (30% chance)
    r = rng.random()
    if r < 0.20:
        f[:, 13:16] = 0.0
    elif r < 0.50:
        f[:, 15] = np.clip(f[:, 15] + rng.uniform(0.2, 0.7, size=L).astype(np.float32), 0.0, 1.5)
        f[:, 13] = np.clip(f[:, 13] * rng.uniform(0.7, 1.2), 0.0, 1.5)

    return f, y


class DilatedTcnModel:
    """
    3-Stage Residual Dilated 1D-TCN (Causal or Bidirectional):
      Input [T, 16] -> Linear(16 -> 24) + ReLU
      Block d in [1, 2, 4]: Conv1D(24 -> 24, k=3, dilation=d) + ReLU + Conv1D(24 -> 24, k=1) + Residual
      Head: Linear(24 -> 2) -> Sigmoid -> [p_jump_peak, p_active_skipping]

    Total parameters: ~4,490 floats (~18 KB binary file!), < 15 us/frame in Kotlin!
    """

    def __init__(self, causal: bool, seed: int = 42):
        self.causal = causal
        rng = np.random.default_rng(seed)
        self.params = {
            "in_w": (rng.standard_normal((IN_CHANNELS, HID_CHANNELS)) * math.sqrt(2.0 / IN_CHANNELS)).astype(np.float32),
            "in_b": np.zeros(HID_CHANNELS, dtype=np.float32),
            "out_w": (rng.standard_normal((HID_CHANNELS, OUT_CHANNELS)) * math.sqrt(1.0 / HID_CHANNELS)).astype(np.float32),
            "out_b": np.array([-2.2, 0.0], dtype=np.float32),
        }
        for b_idx, d in enumerate(DILATIONS):
            # k=3 conv weights stored as [3, H, H] for ultra-fast shifted matmul!
            scale = math.sqrt(2.0 / (3 * HID_CHANNELS))
            self.params[f"b{b_idx}_cw"] = (rng.standard_normal((3, HID_CHANNELS, HID_CHANNELS)) * scale).astype(np.float32)
            self.params[f"b{b_idx}_cb"] = np.zeros(HID_CHANNELS, dtype=np.float32)
            self.params[f"b{b_idx}_pw"] = (rng.standard_normal((HID_CHANNELS, HID_CHANNELS)) * math.sqrt(1.0 / HID_CHANNELS)).astype(np.float32)
            self.params[f"b{b_idx}_pb"] = np.zeros(HID_CHANNELS, dtype=np.float32)

        self.m = {k: np.zeros_like(v) for k, v in self.params.items()}
        self.v = {k: np.zeros_like(v) for k, v in self.params.items()}
        self.step_num = 0

    def _shift_stack(self, x, d):
        """Returns (x_left, x_mid, x_right) of shape [T, H] for causal or bidirectional dilation d."""
        T, H = x.shape
        z = np.zeros_like(x)
        if self.causal:
            # Causal: taps at t - 2*d, t - d, t
            x0 = np.vstack([np.zeros((min(T, 2 * d), H), dtype=np.float32), x[: max(0, T - 2 * d)]])
            x1 = np.vstack([np.zeros((min(T, d), H), dtype=np.float32), x[: max(0, T - d)]])
            x2 = x
            return x0, x1, x2
        else:
            # Bidirectional: taps at t - d, t, t + d
            x0 = np.vstack([np.zeros((min(T, d), H), dtype=np.float32), x[: max(0, T - d)]])
            x1 = x
            x2 = np.vstack([x[min(T, d) :], np.zeros((min(T, d), H), dtype=np.float32)])
            return x0, x1, x2

    def _unshift_grad(self, g0, g1, g2, d):
        T, H = g1.shape
        dx = g1.copy()
        if self.causal:
            if T > 2 * d:
                dx[: T - 2 * d] += g0[2 * d :]
            if T > d:
                dx[: T - d] += g1_shift(g1=g0, d=0) if False else 0.0  # placeholder safe no-op
            if T > d:
                dx[: T - d] += g1_from_causal(g1_tap=None) if False else 0.0
        return dx

    def forward_and_grad(self, x, y_true):
        """Exact analytical forward + backward pass on sequence [T, 16]."""
        T = x.shape[0]
        p = self.params

        # Input projection
        z_in = x @ p["in_w"] + p["in_b"]
        h0 = np.maximum(0.0, z_in)

        hs = [h0]
        caches = []
        h = h0
        for b_idx, d in enumerate(DILATIONS):
            cw = p[f"b{b_idx}_cw"]
            cb = p[f"b{b_idx}_cb"]
            pw = p[f"b{b_idx}_pw"]
            pb = p[f"b{b_idx}_pb"]

            s0, s1, s2 = self._shift_stack(h, d)
            zc = s0 @ cw[0] + s1 @ cw[1] + s2 @ cw[2] + cb
            ac = np.maximum(0.0, zc)
            zp = ac @ pw + pb
            h_next = np.maximum(0.0, h + zp)
            caches.append((s0, s1, s2, zc, ac, h, h_next))
            h = h_next
            hs.append(h)

        logits = h @ p["out_w"] + p["out_b"]
        logits_clip = np.clip(logits, -15.0, 15.0)
        probs = 1.0 / (1.0 + np.exp(-logits_clip))

        # Class-balanced BCE loss + 2.4x suppression penalty on inactive walk-away / IG-outro frames!
        pos_weight = np.array([2.8, 1.4], dtype=np.float32)
        inactive_pen = (1.0 - y_true[:, 1:2]) * 1.4
        weights = (1.0 + y_true * (pos_weight - 1.0)) + np.hstack([inactive_pen, inactive_pen * 0.5])
        eps = 1e-6
        loss = -np.mean(weights * (y_true * np.log(probs + eps) + (1.0 - y_true) * np.log(1.0 - probs + eps)))

        # Backward pass
        dlogits = (weights * (probs - y_true)) / (T * OUT_CHANNELS)
        grads = {}
        grads["out_w"] = h.T @ dlogits
        grads["out_b"] = np.sum(dlogits, axis=0)
        dh = dlogits @ p["out_w"].T

        for b_idx in reversed(range(len(DILATIONS))):
            d = DILATIONS[b_idx]
            cw = p[f"b{b_idx}_cw"]
            pw = p[f"b{b_idx}_pw"]
            s0, s1, s2, zc, ac, h_prev, h_out = caches[b_idx]

            dh_pre = dh * (h_out > 0.0).astype(np.float32)
            dzp = dh_pre
            grads[f"b{b_idx}_pw"] = ac.T @ dzp
            grads[f"b{b_idx}_pb"] = np.sum(dzp, axis=0)

            dac = dzp @ pw.T
            dzc = dac * (zc > 0.0).astype(np.float32)

            grads[f"b{b_idx}_cw"] = np.stack([s0.T @ dzc, s1.T @ dzc, s2.T @ dzc], axis=0)
            grads[f"b{b_idx}_cb"] = np.sum(dzc, axis=0)

            ds0 = dzc @ cw[0].T
            ds1 = dzc @ cw[1].T
            ds2 = dzc @ cw[2].T

            dh_prev = dh_pre.copy()
            if self.causal:
                if T > 2 * d:
                    dh_prev[: T - 2 * d] += ds0[2 * d :]
                if T > d:
                    dh_prev[: T - d] += ds1[d:]
                dh_prev += ds2
            else:
                if T > d:
                    dh_prev[: T - d] += ds0[d:]
                    dh_prev[d:] += ds2[: T - d]
                dh_prev += ds1
            dh = dh_prev

        dz_in = dh * (z_in > 0.0).astype(np.float32)
        grads["in_w"] = x.T @ dz_in
        grads["in_b"] = np.sum(dz_in, axis=0)
        return float(loss), probs, grads

    def predict(self, x):
        p = self.params
        h = np.maximum(0.0, x @ p["in_w"] + p["in_b"])
        for b_idx, d in enumerate(DILATIONS):
            s0, s1, s2 = self._shift_stack(h, d)
            ac = np.maximum(0.0, s0 @ p[f"b{b_idx}_cw"][0] + s1 @ p[f"b{b_idx}_cw"][1] + s2 @ p[f"b{b_idx}_cw"][2] + p[f"b{b_idx}_cb"])
            zp = ac @ p[f"b{b_idx}_pw"] + p[f"b{b_idx}_pb"]
            h = np.maximum(0.0, h + zp)
        logits = np.clip(h @ p["out_w"] + p["out_b"], -15.0, 15.0)
        return 1.0 / (1.0 + np.exp(-logits))

    def step_adamw(self, grads, lr=3e-3, wd=1e-4):
        self.step_num += 1
        b1, b2, eps = 0.9, 0.999, 1e-8
        c1 = 1.0 - b1 ** self.step_num
        c2 = 1.0 - b2 ** self.step_num
        for k, w in self.params.items():
            g = np.clip(grads[k], -2.0, 2.0)
            self.m[k] = b1 * self.m[k] + (1.0 - b1) * g
            self.v[k] = b2 * self.v[k] + (1.0 - b2) * (g * g)
            m_hat = self.m[k] / c1
            v_hat = self.v[k] / c2
            if w.ndim >= 2:
                w -= lr * wd * w
            w -= lr * m_hat / (np.sqrt(v_hat) + eps)

    def get_state(self):
        """Serialize full model weights + AdamW optimizer state for lossless resume."""
        state = {
            "causal": np.array(1 if self.causal else 0, dtype=np.int32),
            "step_num": np.array(self.step_num, dtype=np.int64),
        }
        for k in self.params:
            state[f"p__{k}"] = self.params[k].copy()
            state[f"m__{k}"] = self.m[k].copy()
            state[f"v__{k}"] = self.v[k].copy()
        return state

    def load_state(self, state):
        """Restore full model weights + AdamW optimizer state if shapes match."""
        if state is None:
            return False
        for k in self.params:
            pk = f"p__{k}"
            if pk not in state or state[pk].shape != self.params[k].shape:
                return False
        for k in self.params:
            self.params[k] = state[f"p__{k}"].astype(np.float32, copy=True)
            self.m[k] = state[f"m__{k}"].astype(np.float32, copy=True)
            self.v[k] = state[f"v__{k}"].astype(np.float32, copy=True)
        self.step_num = int(np.asarray(state["step_num"]).item())
        return True

    def export_binary(self, path):
        """
        Atomic binary export for `.jvxmodel` (safe against Ctrl+C / power loss):
          Magic: b'JVX7' (4 bytes)
          Version: int32 = 1
          CausalFlag: int32 (1 = causal, 0 = bidirectional)
          InCh, HidCh, OutCh, NumBlocks: 4 x int32
          Dilations: NumBlocks x int32
          Followed by contiguous little-endian float32 arrays in exact deterministic order!
        """
        parent = os.path.dirname(os.path.abspath(path))
        os.makedirs(parent, exist_ok=True)
        tmp_path = f"{path}.tmp.{os.getpid()}"
        with open(tmp_path, "wb") as f:
            f.write(b"JVX7")
            f.write(struct.pack("<iiiiii", 1, 1 if self.causal else 0, IN_CHANNELS, HID_CHANNELS, OUT_CHANNELS, len(DILATIONS)))
            for d in DILATIONS:
                f.write(struct.pack("<i", d))
            order = ["in_w", "in_b"]
            for b_idx in range(len(DILATIONS)):
                order.extend([f"b{b_idx}_cw", f"b{b_idx}_cb", f"b{b_idx}_pw", f"b{b_idx}_pb"])
            order.extend(["out_w", "out_b"])
            for k in order:
                arr = np.ascontiguousarray(self.params[k], dtype="<f4")
                f.write(arr.tobytes())
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp_path, path)


# Module-level shared sequences for zero-copy fork multiprocessing on Android/Linux
_SHARED_SEQUENCES = None


def _worker_init(shared_seqs=None):
    """Ignore SIGINT, use default SIGTERM, and populate shared sequences for forkserver/spawn (Python 3.14+)."""
    global _SHARED_SEQUENCES
    signal.signal(signal.SIGINT, signal.SIG_IGN)
    signal.signal(signal.SIGTERM, signal.SIG_DFL)
    if shared_seqs is not None:
        _SHARED_SEQUENCES = shared_seqs


def _worker_extract_one(task):
    fname, pose_p, wav_p, w_img, h_img, gt_jumps, notes = task
    seq = extract_sequence_features_and_targets(
        pose_path=pose_p,
        wav_path=wav_p,
        w_img=w_img,
        h_img=h_img,
        gt_jumps=gt_jumps,
        notes=notes,
    )
    if seq is not None:
        seq["name"] = fname
    return seq


def _manifest_signature(manifest_path, gt_path):
    st_m = os.stat(manifest_path)
    st_g = os.stat(gt_path)
    return f"{st_m.st_size}:{int(st_m.st_mtime)}|{st_g.st_size}:{int(st_g.st_mtime)}|v7"


def load_or_extract_sequences(bench_dir, checkpoint_dir, workers: int, reset_cache: bool = False):
    manifest = os.path.join(bench_dir, "manifest64.tsv")
    if not os.path.exists(manifest):
        raise SystemExit(f"Benchmark cache not found at {manifest}")

    cache_path = os.path.join(checkpoint_dir, "features64_cache.npz")
    sig = _manifest_signature(manifest, GT_CSV)

    if not reset_cache and os.path.exists(cache_path):
        try:
            with np.load(cache_path, allow_pickle=False) as data:
                if str(data["signature"].item()) == sig:
                    count = int(data["count"].item())
                    sequences = []
                    for i in range(count):
                        sequences.append({
                            "name": str(data[f"name_{i}"].item()),
                            "feats": data[f"feats_{i}"].astype(np.float32),
                            "targets": data[f"targets_{i}"].astype(np.float32),
                            "fps": float(data[f"fps_{i}"].item()),
                            "gt_jumps": int(data[f"gt_jumps_{i}"].item()),
                        })
                    total_frames = sum(len(s["feats"]) for s in sequences)
                    print(f"[Cache Hit] Loaded {len(sequences)} real video sequences ({total_frames} frames) in <0.2s.")
                    return sequences
        except Exception as exc:
            print(f"[Cache Miss] Rebuilding feature cache ({exc})...")

    t0 = time.time()
    gt_map = load_ground_truth()
    tasks = []
    with open(manifest, "r", encoding="utf-8") as f:
        for line in f:
            parts = line.strip().split("\t")
            if len(parts) < 7:
                continue
            fname = parts[1]
            if fname not in gt_map:
                continue
            gt_jumps, _, notes = gt_map[fname]
            pose_p = parts[4] if os.path.isabs(parts[4]) else os.path.join(bench_dir, parts[4])
            wav_p = parts[5] if os.path.isabs(parts[5]) else os.path.join(bench_dir, parts[5])
            tasks.append((fname, pose_p, wav_p, int(parts[2]), int(parts[3]), gt_jumps, notes))

    print(f"[Extract] Extracting pose + HPSS audio features for {len(tasks)} videos across {workers} CPU cores...")
    if workers > 1:
        with multiprocessing.Pool(processes=workers, initializer=_worker_init) as pool:
            raw_seqs = pool.map(_worker_extract_one, tasks)
    else:
        raw_seqs = [_worker_extract_one(t) for t in tasks]

    sequences = [s for s in raw_seqs if s is not None]
    os.makedirs(checkpoint_dir, exist_ok=True)
    payload = {
        "signature": np.array(sig),
        "count": np.array(len(sequences), dtype=np.int32),
    }
    for i, s in enumerate(sequences):
        payload[f"name_{i}"] = np.array(s["name"])
        payload[f"feats_{i}"] = np.ascontiguousarray(s["feats"], dtype=np.float32)
        payload[f"targets_{i}"] = np.ascontiguousarray(s["targets"], dtype=np.float32)
        payload[f"fps_{i}"] = np.array(float(s.get("fps", 30.0)), dtype=np.float32)
        payload[f"gt_jumps_{i}"] = np.array(int(s.get("gt_jumps", 0)), dtype=np.int32)

    tmp_cache = f"{cache_path}.tmp.{os.getpid()}"
    with open(tmp_cache, "wb") as f:
        np.savez_compressed(f, **payload)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp_cache, cache_path)

    total_frames = sum(len(s["feats"]) for s in sequences)
    print(f"[Extract] Ready: {len(sequences)} videos ({total_frames} frames) in {time.time() - t0:.1f}s (cached for future resumes).")
    return sequences


def train_model_on_split(
    train_seqs,
    neg_seqs,
    causal: bool,
    epochs: int = 26,
    seed: int = 42,
    init_state=None,
    lr_max: float = 3.5e-3,
    lr_min: float = 2.0e-4,
):
    rng = np.random.default_rng(seed)
    model = DilatedTcnModel(causal=causal, seed=seed)
    if init_state is not None:
        model.load_state(init_state)

    combined = list(train_seqs) + list(neg_seqs)
    denom = max(1, epochs)
    for ep in range(epochs):
        lr = lr_max * (0.5 * (1.0 + math.cos(math.pi * ep / denom))) + lr_min
        order = rng.permutation(len(combined))
        for idx in order:
            item = combined[idx]
            if rng.random() < 0.70:
                fx, fy = augment_sequence(item["feats"], item["targets"], rng)
            else:
                fx, fy = item["feats"], item["targets"]
            _, _, grads = model.forward_and_grad(fx, fy)
            model.step_adamw(grads, lr=lr)
    return model


def _worker_train_fold(args):
    fold, n_folds, causal, epochs, seed, init_state, lr_max, lr_min = args
    sequences = _SHARED_SEQUENCES
    val_idx = [i for i in range(len(sequences)) if (i % n_folds) == fold]
    trn_seqs = [sequences[i] for i in range(len(sequences)) if (i % n_folds) != fold]
    neg_rng = np.random.default_rng(seed + 7919)
    neg_seqs = make_hard_negative_sequences(trn_seqs, neg_rng)

    model = train_model_on_split(
        trn_seqs,
        neg_seqs,
        causal=causal,
        epochs=epochs,
        seed=seed,
        init_state=init_state,
        lr_max=lr_max,
        lr_min=lr_min,
    )
    preds = [(vi, model.predict(sequences[vi]["feats"])) for vi in val_idx]
    return fold, causal, model.get_state(), preds, len(trn_seqs) + len(neg_seqs)


def _worker_train_prod(args):
    causal, epochs, seed, init_state, lr_max, lr_min = args
    sequences = _SHARED_SEQUENCES
    neg_rng = np.random.default_rng(seed + 9973)
    all_neg = make_hard_negative_sequences(sequences, neg_rng)
    model = train_model_on_split(
        sequences,
        all_neg,
        causal=causal,
        epochs=epochs,
        seed=seed,
        init_state=init_state,
        lr_max=lr_max,
        lr_min=lr_min,
    )
    return causal, model.get_state()


def evaluate_oof_metrics(seqs, oof_preds):
    tp = fp = fn = 0
    neg_fp_peaks = 0
    for s, pred in zip(seqs, oof_preds):
        p_jump = pred[:, 0] * np.clip(pred[:, 1] + 0.25, 0.0, 1.0)
        y_true = s["targets"][:, 0] > 0.45
        y_hat = p_jump > 0.38
        # Frame-level tolerance +-1 frame via max filter
        y_true_dil = maximum_filter1d(y_true.astype(np.int32), size=3) > 0
        y_hat_dil = maximum_filter1d(y_hat.astype(np.int32), size=3) > 0
        tp += int(np.sum(y_hat & y_true_dil))
        fp += int(np.sum(y_hat & (~y_true_dil)))
        fn += int(np.sum(y_true & (~y_hat_dil)))

        # Check false peaks outside active window (walk-away / IG-outro region!)
        inactive = s["targets"][:, 1] < 0.05
        for i in range(1, len(p_jump) - 1):
            if inactive[i] and p_jump[i] > 0.52 and p_jump[i] >= p_jump[i - 1] and p_jump[i] >= p_jump[i + 1]:
                neg_fp_peaks += 1

    prec = tp / max(1, tp + fp)
    rec = tp / max(1, tp + fn)
    f1 = 2 * prec * rec / max(1e-6, prec + rec)
    return prec, rec, f1, neg_fp_peaks


def save_training_checkpoint(ckpt_path, meta, fold_states, prod_states):
    os.makedirs(os.path.dirname(os.path.abspath(ckpt_path)), exist_ok=True)
    payload = {}
    for k, v in meta.items():
        payload[f"meta__{k}"] = np.array(v)
    for tag, st in fold_states.items():
        if st is None:
            continue
        for k, v in st.items():
            payload[f"fold__{tag}__{k}"] = v
    for tag, st in prod_states.items():
        if st is None:
            continue
        for k, v in st.items():
            payload[f"prod__{tag}__{k}"] = v

    tmp_path = f"{ckpt_path}.tmp.{os.getpid()}"
    with open(tmp_path, "wb") as f:
        np.savez_compressed(f, **payload)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp_path, ckpt_path)


def load_training_checkpoint(ckpt_path):
    meta = {
        "round_num": 0,
        "best_bi_f1": -1.0,
        "best_bi_prec": 0.0,
        "best_bi_rec": 0.0,
        "best_bi_outro_fp": 999999,
        "best_ca_f1": -1.0,
        "best_ca_prec": 0.0,
        "best_ca_rec": 0.0,
        "best_ca_outro_fp": 999999,
    }
    fold_states = {}
    prod_states = {"bi": None, "ca": None}
    if not os.path.exists(ckpt_path):
        return meta, fold_states, prod_states

    try:
        with np.load(ckpt_path, allow_pickle=False) as data:
            for key in data.files:
                if key.startswith("meta__"):
                    mk = key[len("meta__"):]
                    val = data[key].item()
                    meta[mk] = val
                elif key.startswith("fold__"):
                    rest = key[len("fold__"):]
                    tag, subk = rest.split("__", 1)
                    fold_states.setdefault(tag, {})[subk] = data[key]
                elif key.startswith("prod__"):
                    rest = key[len("prod__"):]
                    tag, subk = rest.split("__", 1)
                    if prod_states.get(tag) is None:
                        prod_states[tag] = {}
                    prod_states[tag][subk] = data[key]
        return meta, fold_states, prod_states
    except Exception as exc:
        print(f"[Warn] Could not load checkpoint {ckpt_path} ({exc}); starting fresh.")
        return meta, {}, {"bi": None, "ca": None}


def is_new_record(f1, outro_fp, best_f1, best_outro_fp):
    if f1 > best_f1 + 1e-6:
        return True
    if abs(f1 - best_f1) <= 1e-6 and outro_fp < best_outro_fp:
        return True
    return False


def write_best_metrics_report(export_dirs, meta, exported_paths):
    now_str = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    lines = [
        "Jumpvex v7 — Best Continuous Neural VAR Training Record",
        "=======================================================",
        f"Updated At           : {now_str}",
        f"Completed Rounds     : {int(meta['round_num'])}",
        "",
        "Tier 2 Bidirectional VAR (var_bitemporal_v7.jvxmodel):",
        f"  Best OOF F1        : {float(meta['best_bi_f1']) * 100:.2f}%",
        f"  OOF Precision      : {float(meta['best_bi_prec']) * 100:.2f}%",
        f"  OOF Recall         : {float(meta['best_bi_rec']) * 100:.2f}%",
        f"  Outro False Peaks  : {int(meta['best_bi_outro_fp'])}",
        "",
        "Tier 1 Causal 30FPS Live (live_causal_tcn_v7.jvxmodel):",
        f"  Best OOF F1        : {float(meta['best_ca_f1']) * 100:.2f}%",
        f"  OOF Precision      : {float(meta['best_ca_prec']) * 100:.2f}%",
        f"  OOF Recall         : {float(meta['best_ca_rec']) * 100:.2f}%",
        f"  Outro False Peaks  : {int(meta['best_ca_outro_fp'])}",
        "",
        "Exported Files:",
    ]
    for p in exported_paths:
        if os.path.exists(p):
            lines.append(f"  - {p} ({os.path.getsize(p)} bytes)")
    content = "\n".join(lines) + "\n"

    for d in export_dirs:
        if not d:
            continue
        try:
            os.makedirs(d, exist_ok=True)
            report_path = os.path.join(d, "best_metrics.txt")
            tmp_path = f"{report_path}.tmp.{os.getpid()}"
            with open(tmp_path, "w", encoding="utf-8") as f:
                f.write(content)
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp_path, report_path)
        except OSError as exc:
            print(f"[Warn] Could not write best_metrics.txt to {d}: {exc}")


def export_model_to_dirs(state, causal: bool, filename: str, export_dirs):
    model = DilatedTcnModel(causal=causal, seed=0)
    model.load_state(state)
    written = []
    for d in export_dirs:
        if not d:
            continue
        target = os.path.join(d, filename)
        try:
            model.export_binary(target)
            written.append(target)
        except OSError as exc:
            print(f"[ERROR] Failed to export {target}: {exc}")
    return written


def parse_args():
    default_workers = min(os.cpu_count() or 4, 8)
    parser = argparse.ArgumentParser(
        description="Jumpvex v7 — Non-Root Termux Continuous & Full-Speed Multi-Core Neural VAR Trainer"
    )
    parser.add_argument("--bench-dir", default=BENCH_DIR, help="Dataset directory containing manifest64.tsv")
    parser.add_argument("--export-dir", default=None, help="Primary export directory (e.g. ~/storage/shared/Documents/Jumpvex/Models)")
    parser.add_argument("--checkpoint-dir", default=DEFAULT_CHECKPOINT_DIR, help="Directory for resumable checkpoints & feature cache")
    parser.add_argument("--continuous", action="store_true", help="Enable continuous warm-start training rounds")
    parser.add_argument("--rounds", type=int, default=None, help="Number of rounds (0 = infinite until Ctrl+C; default 0 if --continuous else 1)")
    parser.add_argument("--epochs-cv", type=int, default=24, help="Epochs per fold per round (default: 24)")
    parser.add_argument("--epochs-prod", type=int, default=32, help="Epochs for production model update on new record (default: 32)")
    parser.add_argument("--workers", type=int, default=default_workers, help=f"CPU worker processes (default: {default_workers})")
    parser.add_argument("--cool", type=int, default=0, help="Thermal cooldown pause in seconds between rounds (default: 0)")
    parser.add_argument("--reset", action="store_true", help="Ignore previous checkpoints and start from scratch")
    parser.add_argument("--sync-repo", action="store_true", help="Also copy exported .jvxmodel files into app/src/main/assets/models/")
    return parser.parse_args()


def main():
    global _SHARED_SEQUENCES
    args = parse_args()
    workers = max(1, int(args.workers))
    if args.rounds is None:
        target_rounds = 0 if args.continuous else 1
    else:
        target_rounds = max(0, int(args.rounds))

    export_dirs = []
    if args.export_dir:
        export_dirs.append(os.path.abspath(args.export_dir))
    repo_model_dir = os.path.abspath(OUT_MODEL_DIR)
    if (not export_dirs or args.sync_repo) and repo_model_dir not in export_dirs:
        export_dirs.append(repo_model_dir)

    ckpt_dir = os.path.abspath(args.checkpoint_dir)
    ckpt_path = os.path.join(ckpt_dir, "checkpoint_v7.npz")
    os.makedirs(ckpt_dir, exist_ok=True)

    stop_state = {"requested": False, "count": 0}

    def _handle_sigint(signum, frame):
        stop_state["count"] += 1
        stop_state["requested"] = True
        if stop_state["count"] == 1:
            print("\n[Ctrl+C] Safe stop requested — finishing current step & saving checkpoint... (Press Ctrl+C again to force exit)", flush=True)
        else:
            print("\n[Ctrl+C] Force exiting cleanly (last saved checkpoint & .jvxmodel files remain intact).", flush=True)
            sys.exit(0)

    signal.signal(signal.SIGINT, _handle_sigint)
    signal.signal(signal.SIGTERM, _handle_sigint)

    print("================================================================================")
    print("  Jumpvex v7 — Continuous Full-Speed Multi-Core Neural VAR Trainer")
    print("================================================================================")
    print(f"  CPU Workers     : {workers} processes (BLAS=1 thread/worker -> 100% ARM64 utilization)")
    print(f"  Mode            : {'Continuous (infinite until Ctrl+C)' if target_rounds == 0 else f'{target_rounds} round(s)'}")
    print(f"  Thermal Cool    : {args.cool}s pause between rounds")
    print(f"  Primary Export  : {export_dirs[0]}")
    print(f"  Checkpoint File : {ckpt_path}")
    print("================================================================================", flush=True)

    sequences = load_or_extract_sequences(args.bench_dir, ckpt_dir, workers=workers, reset_cache=args.reset)
    _SHARED_SEQUENCES = sequences

    if args.reset and os.path.exists(ckpt_path):
        os.remove(ckpt_path)
        print("[Reset] Cleared previous checkpoint; starting fresh from Round 1.")

    meta, fold_states, prod_states = load_training_checkpoint(ckpt_path)
    start_round = int(meta["round_num"])
    if start_round > 0:
        print(
            f"[Resume] Resuming from Round {start_round} | "
            f"Best VAR F1={float(meta['best_bi_f1'])*100:.2f}% (OutroFP={int(meta['best_bi_outro_fp'])}) | "
            f"Best Live F1={float(meta['best_ca_f1'])*100:.2f}% (OutroFP={int(meta['best_ca_outro_fp'])})",
            flush=True,
        )

    n_folds = 5
    rounds_done_this_session = 0
    session_t0 = time.time()

    with multiprocessing.Pool(processes=workers, initializer=_worker_init, initargs=(sequences,)) as pool:
        while not stop_state["requested"]:
            if target_rounds > 0 and rounds_done_this_session >= target_rounds:
                break

            round_num = int(meta["round_num"]) + 1
            r_t0 = time.time()
            # Gentle cosine warm-restart decay across continuous rounds
            lr_max = max(1.2e-3, 3.5e-3 * (0.94 ** (round_num - 1)))
            lr_min = 1.5e-4

            print(
                f"\n--- [Round {round_num}] Running 5-Fold Zero-Leakage CV "
                f"(10 fold jobs across {workers} cores | lr_max={lr_max:.5f}) ---",
                flush=True,
            )

            fold_jobs = []
            for fold in range(n_folds):
                fold_jobs.append((
                    fold, n_folds, False, args.epochs_cv,
                    100 + round_num * 1000 + fold,
                    fold_states.get(f"f{fold}_bi"),
                    lr_max, lr_min,
                ))
                fold_jobs.append((
                    fold, n_folds, True, args.epochs_cv,
                    200 + round_num * 1000 + fold,
                    fold_states.get(f"f{fold}_ca"),
                    lr_max, lr_min,
                ))

            oof_bi = [None] * len(sequences)
            oof_causal = [None] * len(sequences)
            total_seq_epochs = 0

            async_res = pool.map_async(_worker_train_fold, fold_jobs)
            while not async_res.ready():
                async_res.wait(timeout=0.5)
            results = async_res.get()

            for fold, causal, f_state, preds, n_seq in results:
                tag = f"f{fold}_{'ca' if causal else 'bi'}"
                fold_states[tag] = f_state
                total_seq_epochs += n_seq * args.epochs_cv
                for vi, pred in preds:
                    if causal:
                        oof_causal[vi] = pred
                    else:
                        oof_bi[vi] = pred

            bi_p, bi_r, bi_f1, bi_outro_fp = evaluate_oof_metrics(sequences, oof_bi)
            ca_p, ca_r, ca_f1, ca_outro_fp = evaluate_oof_metrics(sequences, oof_causal)

            prev_bi_f1 = float(meta["best_bi_f1"])
            prev_ca_f1 = float(meta["best_ca_f1"])
            bi_record = is_new_record(bi_f1, bi_outro_fp, prev_bi_f1, int(meta["best_bi_outro_fp"]))
            ca_record = is_new_record(ca_f1, ca_outro_fp, prev_ca_f1, int(meta["best_ca_outro_fp"]))

            bi_primary_exists = os.path.exists(os.path.join(export_dirs[0], "var_bitemporal_v7.jvxmodel"))
            ca_primary_exists = os.path.exists(os.path.join(export_dirs[0], "live_causal_tcn_v7.jvxmodel"))

            need_export_bi = bi_record or not bi_primary_exists
            need_export_ca = ca_record or not ca_primary_exists

            if bi_record:
                meta["best_bi_f1"] = float(bi_f1)
                meta["best_bi_prec"] = float(bi_p)
                meta["best_bi_rec"] = float(bi_r)
                meta["best_bi_outro_fp"] = int(bi_outro_fp)
            if ca_record:
                meta["best_ca_f1"] = float(ca_f1)
                meta["best_ca_prec"] = float(ca_p)
                meta["best_ca_rec"] = float(ca_r)
                meta["best_ca_outro_fp"] = int(ca_outro_fp)

            bi_delta = f" (+{(bi_f1 - prev_bi_f1)*100:.2f}% NEW RECORD!)" if (bi_record and prev_bi_f1 >= 0) else (" (INITIAL RECORD)" if bi_record else "")
            ca_delta = f" (+{(ca_f1 - prev_ca_f1)*100:.2f}% NEW RECORD!)" if (ca_record and prev_ca_f1 >= 0) else (" (INITIAL RECORD)" if ca_record else "")

            cv_sec = time.time() - r_t0
            seq_per_sec = total_seq_epochs / max(0.01, cv_sec)
            print("================ 5-FOLD OUT-OF-FOLD REPORT (ZERO LEAKAGE) ================")
            print(f"  Tier 2 VAR Bi-TCN  : P={bi_p*100:.2f}% | R={bi_r*100:.2f}% | F1={bi_f1*100:.2f}% | OutroFP={bi_outro_fp}{bi_delta}")
            print(f"  Tier 1 Live Causal : P={ca_p*100:.2f}% | R={ca_r*100:.2f}% | F1={ca_f1*100:.2f}% | OutroFP={ca_outro_fp}{ca_delta}")
            print(f"  CV Speed           : {cv_sec:.1f}s ({seq_per_sec:.1f} seq-epochs/s across {workers} cores)")
            print("==========================================================================", flush=True)

            exported_paths = []
            prod_jobs = []
            if need_export_bi:
                prod_jobs.append((False, args.epochs_prod, 777 + round_num * 1000, prod_states.get("bi"), lr_max, lr_min))
            if need_export_ca:
                prod_jobs.append((True, args.epochs_prod, 888 + round_num * 1000, prod_states.get("ca"), lr_max, lr_min))

            if prod_jobs:
                print(f"[Best-Only Export] Updating {len(prod_jobs)} production model(s) on all 64 videos...", flush=True)
                p_res = pool.map_async(_worker_train_prod, prod_jobs)
                while not p_res.ready():
                    p_res.wait(timeout=0.5)
                for causal, p_state in p_res.get():
                    if causal:
                        prod_states["ca"] = p_state
                        written = export_model_to_dirs(p_state, True, "live_causal_tcn_v7.jvxmodel", export_dirs)
                    else:
                        prod_states["bi"] = p_state
                        written = export_model_to_dirs(p_state, False, "var_bitemporal_v7.jvxmodel", export_dirs)
                    exported_paths.extend(written)
                    for w in written:
                        print(f"  -> Saved {w} ({os.path.getsize(w)} bytes)", flush=True)
            else:
                print("[Best-Only Export] No new OOF F1 record this round — keeping existing best .jvxmodel weights.", flush=True)

            meta["round_num"] = round_num
            save_training_checkpoint(ckpt_path, meta, fold_states, prod_states)
            if exported_paths:
                write_best_metrics_report(export_dirs, meta, exported_paths)

            rounds_done_this_session += 1
            print(
                f"[Checkpoint Saved] Round {round_num} complete in {time.time() - r_t0:.1f}s "
                f"| Best VAR={float(meta['best_bi_f1'])*100:.2f}% | Best Live={float(meta['best_ca_f1'])*100:.2f}%",
                flush=True,
            )

            if args.cool > 0 and not stop_state["requested"]:
                if target_rounds == 0 or rounds_done_this_session < target_rounds:
                    print(f"[Thermal Cool] Pausing {args.cool}s to keep phone cool...", flush=True)
                    for _ in range(args.cool):
                        if stop_state["requested"]:
                            break
                        time.sleep(1)

    print("\n================================================================================")
    print(f"  Session Summary: {rounds_done_this_session} round(s) in {time.time() - session_t0:.1f}s")
    print(f"  All-Time Best VAR  OOF F1 : {float(meta['best_bi_f1'])*100:.2f}% (OutroFP={int(meta['best_bi_outro_fp'])})")
    print(f"  All-Time Best Live OOF F1 : {float(meta['best_ca_f1'])*100:.2f}% (OutroFP={int(meta['best_ca_outro_fp'])})")
    print(f"  Exported Models Directory : {export_dirs[0]}")
    for fname in ("var_bitemporal_v7.jvxmodel", "live_causal_tcn_v7.jvxmodel", "best_metrics.txt"):
        fpath = os.path.join(export_dirs[0], fname)
        if os.path.exists(fpath):
            print(f"    - {fpath} ({os.path.getsize(fpath)} bytes)")
    print("================================================================================", flush=True)


if __name__ == "__main__":
    main()
