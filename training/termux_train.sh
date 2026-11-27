#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# Jumpvex v7.2 — 100% Pure Rust Non-Root Termux Asymmetric Push-to-Limit Trainer
# ==============================================================================
# Zero Python, zero NumPy, zero Google Drive, zero root!
# Loads pre-extracted 64-video features (`training/dataset/features64.jvxbin`)
# in ~6 ms and trains:
#   - Tier 2 VAR Bi-TCN  : HID_CH=48 x 5 blocks [1,2,4,8,16] (~186 KB, ±1.03s)
#   - Tier 1 Live Causal : HID_CH=32 x 5 blocks [1,2,4,8,16] (~84 KB, distilled)
#
# Usage:
#   bash training/termux_train.sh                  # Continuous full-speed (Ctrl+C to stop)
#   bash training/termux_train.sh --cool           # Continuous + 20s thermal cooldown/round
#   bash training/termux_train.sh --rounds 5       # Run exactly 5 rounds then exit
#   bash training/termux_train.sh --reset          # Reset checkpoint & start from Round 1
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

DATASET_DIR="$SCRIPT_DIR/dataset"
JVXBIN="$DATASET_DIR/features64.jvxbin"
CKPT_DIR="$REPO_ROOT/training/checkpoints"
RUST_DIR="$SCRIPT_DIR/rust_trainer"
RUST_BIN="$RUST_DIR/target/release/jumpvex_trainer"
CORES="$(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"
ROUNDS="0"
COOL_SEC="0"
RESET_FLAG=""
CUSTOM_EXPORT_DIR=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --rust)
      # Accepted for backward compatibility (always 100% Pure Rust now)
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
    --reset)
      RESET_FLAG="--reset"
      shift
      ;;
    --dataset)
      DATASET_DIR="${2:-$SCRIPT_DIR/dataset}"
      JVXBIN="$DATASET_DIR/features64.jvxbin"
      shift 2
      ;;
    --export-dir)
      CUSTOM_EXPORT_DIR="${2:-}"
      shift 2
      ;;
    -h|--help)
      echo "Usage: bash training/termux_train.sh [--cool [SEC]] [--rounds N] [--reset] [--export-dir DIR]"
      exit 0
      ;;
    *)
      if [[ -d "$1" ]]; then
        DATASET_DIR="$1"
        JVXBIN="$DATASET_DIR/features64.jvxbin"
      else
        echo "Unknown option: $1 (use --help for usage)"
        exit 1
      fi
      shift
      ;;
  esac
done

echo "================================================================================"
echo "  Jumpvex v7.2 — 100% Pure Rust Non-Root Termux Neural VAR Trainer"
echo "================================================================================"
echo "  Repo Root   : $REPO_ROOT"
echo "  CPU Cores   : $CORES detected (5 parallel native Rust SIMD fold threads)"
echo "  Mode        : $([ "$ROUNDS" = "0" ] && echo "Continuous (Ctrl+C to stop safely)" || echo "$ROUNDS round(s)")"
echo "  Thermal Cool: ${COOL_SEC}s pause between rounds"
echo "================================================================================"

# 1. Ensure Rust toolchain is installed (zero Python / zero NumPy required!)
if ! command -v cargo >/dev/null 2>&1; then
  if command -v pkg >/dev/null 2>&1; then
    echo "[1/3] Installing Rust toolchain in Termux (one-time setup)..."
    pkg update -y && pkg install -y rust
  else
    echo "ERROR: 'cargo' not found. Install Rust first (https://rustup.rs)."
    exit 1
  fi
fi

if [[ ! -f "$JVXBIN" ]]; then
  echo "ERROR: Pre-extracted dataset not found at $JVXBIN. Run 'git pull' inside $REPO_ROOT first!"
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

# Build Native SIMD Rust binary only if missing or source changed
if [[ ! -x "$RUST_BIN" || "$RUST_DIR/src/main.rs" -nt "$RUST_BIN" || "$RUST_DIR/Cargo.toml" -nt "$RUST_BIN" ]]; then
  echo "[3/3] 🦀 Compiling 100% Pure Rust SIMD Trainer (target-cpu=native)..."
  RUSTFLAGS="-C target-cpu=native" cargo build --release --manifest-path "$RUST_DIR/Cargo.toml"
fi

echo "[3/3] 🦀 Launching Pure Rust Asymmetric Push-to-Limit Trainer (HID48x5 -> HID32x5)..."
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
