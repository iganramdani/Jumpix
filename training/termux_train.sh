#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# Jumpvex v7.1 — Non-Root Termux Continuous & Full-Speed Multi-Core Trainer
# ==============================================================================
# Runs 100% offline inside non-rooted Android Termux using the 64-video dataset
# bundled inside `training/dataset/` (zero root, zero Google Drive, zero PyTorch).
#
# Features:
#   - All-core parallel 5-fold training (100% CPU utilization on 8-core ARM64)
#   - Continuous warm-start resume (saves full weights + AdamW state)
#   - Best-only atomic export directly to Documents/Jumpvex/Models/
#   - Automatic termux-wake-lock (safe when screen turns off)
#   - Safe Ctrl+C handling (never corrupts .jvxmodel or checkpoint)
#
# Usage:
#   bash training/termux_train.sh                  # Continuous full-speed (Ctrl+C to stop)
#   bash training/termux_train.sh --cool           # Continuous + 20s thermal cooldown/round
#   bash training/termux_train.sh --cool 30        # Continuous + 30s thermal cooldown/round
#   bash training/termux_train.sh --rounds 5       # Run exactly 5 rounds then exit
#   bash training/termux_train.sh --workers 4      # Limit CPU cores
#   bash training/termux_train.sh --reset          # Reset checkpoint & start from Round 1
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

DATASET_DIR="$SCRIPT_DIR/dataset"
CORES="$(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"
WORKERS="$CORES"
ROUNDS="0"
COOL_SEC="0"
RESET_FLAG=""
CUSTOM_EXPORT_DIR=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --cool)
      if [[ $# -ge 2 && "$2" =~ ^[0-9]+$ ]]; then
        COOL_SEC="$2"
        shift 2
      else
        COOL_SEC="20"
        shift
      fi
      ;;
    --rounds)
      ROUNDS="${2:-1}"
      shift 2
      ;;
    --workers)
      WORKERS="${2:-$CORES}"
      shift 2
      ;;
    --reset)
      RESET_FLAG="--reset"
      shift
      ;;
    --dataset)
      DATASET_DIR="${2:-$SCRIPT_DIR/dataset}"
      shift 2
      ;;
    --export-dir)
      CUSTOM_EXPORT_DIR="${2:-}"
      shift 2
      ;;
    -h|--help)
      echo "Usage: bash training/termux_train.sh [--cool [SEC]] [--rounds N] [--workers N] [--reset] [--export-dir DIR]"
      exit 0
      ;;
    *)
      # Backward compatibility: positional arg as dataset dir
      if [[ -d "$1" ]]; then
        DATASET_DIR="$1"
      else
        echo "Unknown option: $1 (use --help for usage)"
        exit 1
      fi
      shift
      ;;
  esac
done

echo "================================================================================"
echo "  Jumpvex v7.1 — Non-Root Termux Continuous Full-Speed Neural VAR Trainer"
echo "================================================================================"
echo "  Repo Root   : $REPO_ROOT"
echo "  CPU Cores   : $CORES detected -> using $WORKERS worker process(es)"
echo "  Mode        : $([ "$ROUNDS" = "0" ] && echo "Continuous (Ctrl+C to stop safely)" || echo "$ROUNDS round(s)")"
echo "  Thermal Cool: ${COOL_SEC}s pause between rounds"
echo "================================================================================"

# 1. Ensure Python & NumPy are installed in Termux
if ! command -v python3 >/dev/null 2>&1; then
  echo "[1/3] Installing Python in Termux..."
  pkg update -y && pkg install -y python
fi

python3 -c "import numpy" 2>/dev/null || {
  echo "[1/3] Installing NumPy for Termux ARM64..."
  pkg install -y python-numpy || pip install --no-cache-dir numpy
}

if [[ ! -f "$DATASET_DIR/manifest64.tsv" ]]; then
  echo "ERROR: Dataset not found at $DATASET_DIR/manifest64.tsv. Run 'git pull' inside $REPO_ROOT first!"
  exit 1
fi

# 2. Verify Non-Root Android Documents Storage (Termux storage symlink)
EXPORT_DIR=""
if [[ -n "$CUSTOM_EXPORT_DIR" ]]; then
  mkdir -p "$CUSTOM_EXPORT_DIR"
  EXPORT_DIR="$CUSTOM_EXPORT_DIR"
elif [[ -d "/data/data/com.termux" ]] || command -v termux-setup-storage >/dev/null 2>&1; then
  if [[ ! -d "$HOME/storage/shared/Documents" ]]; then
    echo "[2/3] Requesting Android storage access (non-root) via termux-setup-storage..."
    echo "      -> Please tap 'Allow / Izinkan' on the Android permission popup!"
    termux-setup-storage || true
    for _ in $(seq 1 10); do
      [[ -d "$HOME/storage/shared/Documents" ]] && break
      sleep 1
    done
  fi

  CANDIDATE_DOCS="$HOME/storage/shared/Documents/Jumpvex/Models"
  if mkdir -p "$CANDIDATE_DOCS" 2>/dev/null && touch "$CANDIDATE_DOCS/.write_test" 2>/dev/null; then
    rm -f "$CANDIDATE_DOCS/.write_test"
    EXPORT_DIR="$CANDIDATE_DOCS"
    echo "[2/3] ✅ Non-root Android Documents verified: $EXPORT_DIR"
  elif mkdir -p "/sdcard/Documents/Jumpvex/Models" 2>/dev/null && touch "/sdcard/Documents/Jumpvex/Models/.write_test" 2>/dev/null; then
    rm -f "/sdcard/Documents/Jumpvex/Models/.write_test"
    EXPORT_DIR="/sdcard/Documents/Jumpvex/Models"
    echo "[2/3] ✅ Shared Documents verified: $EXPORT_DIR"
  else
    echo "[2/3] ⚠️  WARNING: Cannot write to Documents/Jumpvex/Models (storage permission not granted)."
    echo "          Run 'termux-setup-storage' once and allow Files permission, then re-run!"
    EXPORT_DIR="$REPO_ROOT/app/src/main/assets/models"
    mkdir -p "$EXPORT_DIR"
  fi
else
  EXPORT_DIR="$REPO_ROOT/app/src/main/assets/models"
  mkdir -p "$EXPORT_DIR"
  echo "[2/3] Export directory: $EXPORT_DIR"
fi

# 3. Acquire Termux Wake-Lock (prevents Android/MIUI from killing training when screen turns off)
WAKE_LOCKED="0"
cleanup() {
  if [[ "$WAKE_LOCKED" = "1" ]] && command -v termux-wake-unlock >/dev/null 2>&1; then
    termux-wake-unlock 2>/dev/null || true
    echo "[Wake-Lock] Released termux-wake-lock."
  fi
}
trap cleanup EXIT

if command -v termux-wake-lock >/dev/null 2>&1; then
  if termux-wake-lock 2>/dev/null; then
    WAKE_LOCKED="1"
    echo "[3/3] 🔒 termux-wake-lock ACTIVE (safe to turn off screen while training!)"
  fi
fi

# Prevent OpenBLAS/OMP thread oversubscription across multiprocessing workers
export OPENBLAS_NUM_THREADS=1
export OMP_NUM_THREADS=1
export MKL_NUM_THREADS=1
export NUMEXPR_NUM_THREADS=1
export PYTHONUNBUFFERED=1
export JUMPVEX_BENCH64_DIR="$DATASET_DIR"

echo "[3/3] Starting Full-Speed Multi-Core Continuous Neural VAR Trainer..."
echo "      Press Ctrl+C once anytime to finish the current step & exit safely."
echo ""

CMD=(
  python3 "$SCRIPT_DIR/train_neural_var_v7.py"
  --continuous
  --bench-dir "$DATASET_DIR"
  --export-dir "$EXPORT_DIR"
  --checkpoint-dir "$REPO_ROOT/training/checkpoints"
  --workers "$WORKERS"
  --rounds "$ROUNDS"
  --cool "$COOL_SEC"
)
if [[ -n "$RESET_FLAG" ]]; then
  CMD+=("$RESET_FLAG")
fi

# Run trainer (do not fail script on graceful Ctrl+C exit)
"${CMD[@]}" || true

echo ""
echo "================================================================================"
echo "✅ Exported Best Models Verification:"
if ls "$EXPORT_DIR"/*.jvxmodel >/dev/null 2>&1; then
  echo "  [Documents / Primary]: $EXPORT_DIR"
  ls -lh "$EXPORT_DIR"/*.jvxmodel "$EXPORT_DIR"/best_metrics.txt 2>/dev/null || ls -lh "$EXPORT_DIR"/*.jvxmodel
else
  echo "  ⚠️  No .jvxmodel files found yet in $EXPORT_DIR (stopped before Round 1 completed)."
fi

REPO_MODELS_DIR="$REPO_ROOT/app/src/main/assets/models"
if [[ "$REPO_MODELS_DIR" != "$EXPORT_DIR" ]] && ls "$REPO_MODELS_DIR"/*.jvxmodel >/dev/null 2>&1; then
  echo "  [Repo Assets Sync]   : $REPO_MODELS_DIR"
  ls -lh "$REPO_MODELS_DIR"/*.jvxmodel
fi
echo "================================================================================"
