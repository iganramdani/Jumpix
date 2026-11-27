#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# Jumpvex v7.1 — 1-Command Android Termux Auto-Download & 5-Fold Neural Trainer
# ==============================================================================
# Runs 100% locally on your Android smartphone inside Termux (no PC / no PyTorch)!
#
# Single-Command Usage on Termux:
#   git pull && bash training/termux_train.sh
# ==============================================================================
set -e

GDRIVE_FILE_ID="18xeSdwpncJ3WHOKnmlR2pU0JvQTWR3cC"
GDRIVE_URL="https://drive.usercontent.google.com/download?id=${GDRIVE_FILE_ID}&export=download&confirm=t"

echo "============================================================"
echo "  Jumpvex v7.1 — 1-Command Termux Neural VAR Trainer"
echo "============================================================"

# 1. Ensure Python, NumPy, curl & unzip are installed in Termux
for tool in python3 curl unzip; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "[1/4] Installing missing package ($tool)..."
    pkg update -y
    pkg install -y python curl unzip
    break
  fi
done

python3 -c "import numpy" 2>/dev/null || {
  echo "[1/4] Installing NumPy for Termux ARM64..."
  pkg install -y python-numpy || pip install --no-cache-dir numpy
}

# 2. Pick writable dataset directory (prefer /sdcard/Documents/Jumpvex/Dataset, fallback to $HOME)
if [ -n "$1" ]; then
  DATASET_DIR="$1"
elif mkdir -p /sdcard/Documents/Jumpvex/Dataset 2>/dev/null; then
  DATASET_DIR="/sdcard/Documents/Jumpvex/Dataset"
else
  DATASET_DIR="$HOME/Jumpvex_Dataset"
  mkdir -p "$DATASET_DIR"
fi

# 3. Auto-download & extract 64-video dataset bundle (58 MB) if not yet present
if [ ! -f "$DATASET_DIR/manifest64.tsv" ]; then
  ZIP_PATH="$DATASET_DIR/Jumpvex_Dataset64_Termux.zip"
  echo "[2/4] Downloading 64-video pose & WAV dataset (~58 MB) to $DATASET_DIR ..."
  curl -fSL --progress-bar "$GDRIVE_URL" -o "$ZIP_PATH"
  if ! unzip -tq "$ZIP_PATH" >/dev/null 2>&1; then
    echo "ERROR: Downloaded file is not a valid ZIP (check internet or Drive link)."
    rm -f "$ZIP_PATH"
    exit 1
  fi
  echo "[3/4] Extracting dataset into $DATASET_DIR ..."
  unzip -qo "$ZIP_PATH" -d "$DATASET_DIR"
  rm -f "$ZIP_PATH"
else
  echo "[2/4] Found existing 64-video dataset at $DATASET_DIR (skipping download)."
fi

# 4. Run full 5-Fold Zero-Leakage Neural VAR Training & Export .jvxmodel weights
echo "[4/4] Running 5-Fold Zero-Leakage Neural VAR training (ARM64 CPU)..."
export JUMPVEX_BENCH64_DIR="$DATASET_DIR"
python3 training/train_neural_var_v7.py

if mkdir -p /sdcard/Documents/Jumpvex/Models 2>/dev/null; then
  cp -f app/src/main/assets/models/*.jvxmodel /sdcard/Documents/Jumpvex/Models/ 2>/dev/null || true
  echo "✅ Also copied trained .jvxmodel weights to /sdcard/Documents/Jumpvex/Models/"
fi

echo "============================================================"
echo "✅ Training Complete! Exported weights:"
ls -lh app/src/main/assets/models/*.jvxmodel
echo "============================================================"
