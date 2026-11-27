#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# Jumpvex v7.1 — Non-Root Termux Continuous & Full-Speed Multi-Core Trainer
#                (Auto-Accelerated by Zero-Dependency Native SIMD Rust Engine!)
# ==============================================================================
# Runs 100% offline inside non-rooted Android Termux using the 64-video dataset
# bundled inside `training/dataset/` (zero root, zero Google Drive, zero PyTorch).
#
# Usage:
#   bash training/termux_train.sh                  # Auto-uses Rust SIMD if installed, else Python
#   bash training/termux_train.sh --rust           # Auto-installs Rust in Termux & runs Native SIMD
#   bash training/termux_train.sh --cool           # Continuous + 20s thermal cooldown/round
#   bash training/termux_train.sh --rounds 5       # Run exactly 5 rounds then exit
#   bash training/termux_train.sh --reset          # Reset checkpoint & start from Round 1
#   bash training/termux_train.sh --python         # Force Python NumPy trainer
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

DATASET_DIR="$SCRIPT_DIR/dataset"
CKPT_DIR="$REPO_ROOT/training/checkpoints"
CORES="$(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"
WORKERS="$CORES"
ROUNDS="0"
COOL_SEC="0"
RESET_FLAG=""
CUSTOM_EXPORT_DIR=""
ENGINE_PREF="auto" # auto | rust | python

while [[ $# -gt 0 ]]; do
  case "$1" in
    --rust)
      ENGINE_PREF="rust"
      shift
      ;;
    --python)
      ENGINE_PREF="python"
      shift
      ;;
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
      echo "Usage: bash training/termux_train.sh [--rust|--python] [--cool [SEC]] [--rounds N] [--workers N] [--reset] [--export-dir DIR]"
      exit 0
      ;;
    *)
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
echo "  CPU Cores   : $CORES detected -> using $WORKERS worker(s)"
echo "  Mode        : $([ "$ROUNDS" = "0" ] && echo "Continuous (Ctrl+C to stop safely)" || echo "$ROUNDS round(s)")"
echo "  Thermal Cool: ${COOL_SEC}s pause between rounds"
echo "================================================================================"

# 1. Ensure Python & NumPy are available (needed for feature extraction / JVXF cache)
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

# Optionally auto-install Rust when user explicitly passes --rust on Termux
if [[ "$ENGINE_PREF" = "rust" ]] && ! command -v cargo >/dev/null 2>&1; then
  if command -v pkg >/dev/null 2>&1; then
    echo "[1/3] Installing Rust toolchain in Termux (one-time setup)..."
    pkg update -y && pkg install -y rust
  else
    echo "ERROR: 'cargo' not found. Install Rust first or run with --python."
    exit 1
  fi
fi

USE_RUST="0"
if [[ "$ENGINE_PREF" = "rust" ]]; then
  USE_RUST="1"
elif [[ "$ENGINE_PREF" = "auto" ]] && command -v cargo >/dev/null 2>&1; then
  USE_RUST="1"
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

export OPENBLAS_NUM_THREADS=1
export OMP_NUM_THREADS=1
export MKL_NUM_THREADS=1
export NUMEXPR_NUM_THREADS=1
export PYTHONUNBUFFERED=1
export JUMPVEX_BENCH64_DIR="$DATASET_DIR"

if [[ "$USE_RUST" = "1" ]]; then
  RUST_DIR="$SCRIPT_DIR/rust_trainer"
  RUST_BIN="$RUST_DIR/target/release/jumpvex_trainer"
  JVXBIN="$CKPT_DIR/features64.jvxbin"

  if [[ ! -x "$RUST_BIN" || "$RUST_DIR/src/main.rs" -nt "$RUST_BIN" || "$RUST_DIR/Cargo.toml" -nt "$RUST_BIN" ]]; then
    echo "[3/3] 🦀 Compiling Zero-Dependency Rust SIMD Trainer (target-cpu=native)..."
    RUSTFLAGS="-C target-cpu=native" cargo build --release --manifest-path "$RUST_DIR/Cargo.toml"
  fi

  if [[ ! -f "$JVXBIN" || "$DATASET_DIR/manifest64.tsv" -nt "$JVXBIN" ]]; then
    echo "[3/3] Preparing binary feature cache ($JVXBIN)..."
    python3 "$SCRIPT_DIR/train_neural_var_v7.py" \
      --bench-dir "$DATASET_DIR" \
      --checkpoint-dir "$CKPT_DIR" \
      --workers "$WORKERS" \
      --dump-jvxbin "$JVXBIN"
  fi

  echo "[3/3] 🦀 Launching Native Rust SIMD Continuous Trainer..."
  echo "      Press Ctrl+C once anytime to finish the current round & exit safely."
  echo ""

  CMD=(
    "$RUST_BIN"
    --jvxbin "$JVXBIN"
    --export-dir "$EXPORT_DIR"
    --checkpoint-dir "$CKPT_DIR"
    --rounds "$ROUNDS"
    --cool "$COOL_SEC"
  )
  if [[ -n "$RESET_FLAG" ]]; then
    CMD+=("$RESET_FLAG")
  fi
  "${CMD[@]}" || true
else
  echo "[3/3] 🐍 Starting Python Multi-Core Continuous Trainer..."
  echo "      Tip: Run 'bash training/termux_train.sh --rust' to install Rust SIMD for max speed!"
  echo "      Press Ctrl+C once anytime to finish the current step & exit safely."
  echo ""

  CMD=(
    python3 "$SCRIPT_DIR/train_neural_var_v7.py"
    --continuous
    --bench-dir "$DATASET_DIR"
    --export-dir "$EXPORT_DIR"
    --checkpoint-dir "$CKPT_DIR"
    --workers "$WORKERS"
    --rounds "$ROUNDS"
    --cool "$COOL_SEC"
  )
  if [[ -n "$RESET_FLAG" ]]; then
    CMD+=("$RESET_FLAG")
  fi
  "${CMD[@]}" || true
fi

echo ""
echo "================================================================================"
echo "✅ Exported Best Models Verification:"
if ls "$EXPORT_DIR"/*.jvxmodel >/dev/null 2>&1; then
  echo "  [Documents / Primary]: $EXPORT_DIR"
  ls -lh "$EXPORT_DIR"/*.jvxmodel "$EXPORT_DIR"/best_metrics.txt 2>/dev/null || ls -lh "$EXPORT_DIR"/*.jvxmodel
else
  echo "  ⚠️  No .jvxmodel files found yet in $EXPORT_DIR (stopped before Round 1 completed)."
fi
echo "================================================================================"
