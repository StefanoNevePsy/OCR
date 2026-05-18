#!/usr/bin/env bash
# Costruisce l'eseguibile dewarp-server con PyInstaller e lo deposita
# in desktop/src-tauri/binaries/ con il suffisso del target triple
# (richiesto da Tauri per i sidecar).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# Triple target (es. x86_64-unknown-linux-gnu, x86_64-apple-darwin, aarch64-apple-darwin, x86_64-pc-windows-msvc)
TRIPLE="${TARGET_TRIPLE:-$(rustc -vV | sed -n 's|host: ||p')}"
if [[ -z "$TRIPLE" ]]; then
  echo "Impossibile determinare il TARGET_TRIPLE. Imposta TARGET_TRIPLE manualmente." >&2
  exit 1
fi

echo "==> target triple: $TRIPLE"
echo "==> PyInstaller build"

rm -rf build dist
python -m PyInstaller --clean packaging/dewarp_server.spec

OUT="dist/dewarp-server"
if [[ "$TRIPLE" == *"windows"* ]]; then
  OUT="$OUT.exe"
fi
[[ -f "$OUT" ]] || { echo "Output PyInstaller non trovato: $OUT" >&2; exit 2; }

mkdir -p desktop/src-tauri/binaries
DEST="desktop/src-tauri/binaries/dewarp-server-$TRIPLE"
if [[ "$TRIPLE" == *"windows"* ]]; then
  DEST="$DEST.exe"
fi
cp "$OUT" "$DEST"
chmod +x "$DEST" || true

echo "==> sidecar pronto: $DEST"
