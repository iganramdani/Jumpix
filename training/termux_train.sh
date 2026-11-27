#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# Jumpvex v7.1 — 1-Command Android Termux Dataset Trainer & .jvxmodel Exporter
# ==============================================================================
# Runs 100% locally on your Android smartphone inside Termux (no PC / no PyTorch)!
#
# Usage on Termux:
#   1. termux-setup-storage
#   2. bash training/termux_train.sh [OPTIONAL_DATASET_DIR]
#
# If OPTIONAL_DATASET_DIR contains manifest64.tsv + poses/ + wavs/, runs full
# 5-Fold Cross-Validation training. Otherwise, audits & summarizes your recorded
# Jumpvex CSV logs in /sdcard/Documents/Jumpvex/DebugLogs/ for calibration.
# ==============================================================================
set -e

echo "============================================================"
echo "  Jumpvex v7.1 — Termux On-Device Neural VAR Trainer"
echo "============================================================"

# 1. Ensure Python & NumPy are installed in Termux
if ! command -v python3 >/dev/null 2>&1; then
  echo "[1/3] Installing Python in Termux..."
  pkg update -y && pkg install -y python
fi

python3 -c "import numpy" 2>/dev/null || {
  echo "[1/3] Installing NumPy for Termux ARM64..."
  pkg install -y python-numpy || pip install --no-cache-dir numpy
}

DATASET_DIR="${1:-${JUMPVEX_BENCH64_DIR:-/sdcard/Documents/Jumpvex/Dataset}}"
LOG_DIR="/sdcard/Documents/Jumpvex/DebugLogs"

if [ -f "$DATASET_DIR/manifest64.tsv" ]; then
  echo "[2/3] Found full benchmark dataset at: $DATASET_DIR"
  echo "[3/3] Running 5-Fold Zero-Leakage Neural VAR training (CPU ARM64)..."
  export JUMPVEX_BENCH64_DIR="$DATASET_DIR"
  python3 training/train_neural_var_v7.py
  echo "✅ Done! Exported updated .jvxmodel weights to app/src/main/assets/models/"
else
  echo "[2/3] Full pose/wav dataset not found at $DATASET_DIR."
  echo "      Auditing your local Jumpvex CSV logs in $LOG_DIR ..."
  python3 - "$LOG_DIR" <<'PY'
import glob, os, re, statistics, sys

log_dir = sys.argv[1]
csvs = sorted(glob.glob(os.path.join(log_dir, "*.csv")))
if not csvs:
    print(f"No CSV logs found in {log_dir}.")
    print("Tip: Analyze videos in Jumpvex Video Studio, then re-run this script!")
    sys.exit(0)

print(f"\nFound {len(csvs)} Jumpvex CSV logs in {log_dir}:\n")
print(f"{'File':42s} | {'Frames':>6s} | {'Jumps':>5s} | {'MedUs':>6s} | {'MaxMB':>5s} | Engine")
print("-" * 92)
for p in csvs[-25:]:
    final_j = None; eng = "?"; rows = 0; us = []; mb = []
    with open(p, encoding="utf-8", errors="ignore") as f:
        hdr = None
        for line in f:
            s = line.strip()
            if s.startswith("# Final Summary:"):
                mj = re.search(r"FinalJumps=(\d+)", s)
                me = re.search(r"Engine=([^|]+)", s)
                if mj: final_j = int(mj.group(1))
                if me: eng = me.group(1).strip()
            elif s.startswith("#") or not s:
                continue
            elif hdr is None and "ts_ms" in s:
                hdr = {k.strip(): i for i, k in enumerate(s.split(","))}
            elif hdr:
                parts = s.split(",")
                if len(parts) < 6: continue
                rows += 1
                if "ai_us" in hdr: us.append(float(parts[hdr["ai_us"]]))
                if "heap_mb" in hdr: mb.append(float(parts[hdr["heap_mb"]]))
    print(f"{os.path.basename(p)[:42]:42s} | {rows:6d} | {str(final_j or 0):>5s} | {statistics.median(us) if us else 0:6.0f} | {max(mb) if mb else 0:5.0f} | {eng}")
print("\nTo run full 5-fold .jvxmodel training on Termux, place manifest64.tsv + poses/ + wavs/ in:")
print("  /sdcard/Documents/Jumpvex/Dataset/  and re-run: bash training/termux_train.sh")
PY
fi
