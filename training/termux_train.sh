#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# Jumpvex v7.1 — 1-Command Android Termux 5-Fold Neural VAR Trainer
# ==============================================================================
# Runs 100% offline inside Termux using the 64-video dataset bundled right inside
# `training/dataset/` (zero Google Drive, zero PC, zero PyTorch!).
#
# Single-Command Usage on Termux:
#   git pull && bash training/termux_train.sh
# ==============================================================================
set -e

echo "============================================================"
echo "  Jumpvex v7.1 — 1-Command Termux Neural VAR Trainer"
echo "============================================================"

# 1. Ensure Python & NumPy are installed in Termux
if ! command -v python3 >/dev/null 2>&1; then
  echo "[1/2] Installing Python in Termux..."
  pkg update -y && pkg install -y python
fi

python3 -c "import numpy" 2>/dev/null || {
  echo "[1/2] Installing NumPy for Termux ARM64..."
  pkg install -y python-numpy || pip install --no-cache-dir numpy
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATASET_DIR="${1:-$SCRIPT_DIR/dataset}"

if [ ! -f "$DATASET_DIR/manifest64.tsv" ]; then
  echo "ERROR: Dataset not found at $DATASET_DIR/manifest64.tsv. Run 'git pull' first!"
  exit 1
fi

# 2. Run full 5-Fold Zero-Leakage Neural VAR Training & Export .jvxmodel weights
echo "[2/2] Running 5-Fold Zero-Leakage Neural VAR training from $DATASET_DIR ..."
export JUMPVEX_BENCH64_DIR="$DATASET_DIR"
python3 "$SCRIPT_DIR/train_neural_var_v7.py"

if mkdir -p /sdcard/Documents/Jumpvex/Models 2>/dev/null; then
  cp -f app/src/main/assets/models/*.jvxmodel /sdcard/Documents/Jumpvex/Models/ 2>/dev/null || true
  echo "✅ Also copied trained .jvxmodel weights to /sdcard/Documents/Jumpvex/Models/"
fi

echo "============================================================"
echo "✅ Training Complete! Exported weights:"
ls -lh app/src/main/assets/models/*.jvxmodel
echo "============================================================"
