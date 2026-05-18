#!/usr/bin/env bash
# Build completo dell'app desktop Dewarp:
#   1) genera l'eseguibile sidecar Python con PyInstaller
#   2) compila il wrapper Tauri (Rust) e produce il bundle nativo
#
# Output:
#   - desktop/src-tauri/binaries/dewarp-server-<triple>[.exe]
#   - desktop/src-tauri/target/release/dewarp-desktop[.exe]
#   - desktop/src-tauri/target/release/bundle/  (AppImage, .deb, .dmg, .msi)
#
# Requisiti:
#   - Python 3.10+ con dipendenze del progetto installate (pip install -e .)
#   - PyInstaller (pip install pyinstaller)
#   - Rust stable, cargo
#   - Node 18+ (per la Tauri CLI)
#   - Linux: libwebkit2gtk-4.1-dev libsoup-3.0-dev librsvg2-dev pkg-config
#   - macOS: Xcode Command Line Tools
#   - Windows: WebView2 runtime (preinstallato su Win11)

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "==> Step 1/2: sidecar Python"
"$ROOT/scripts/build_sidecar.sh"

echo "==> Step 2/2: bundle Tauri"
cd "$ROOT/desktop"
if [ ! -d node_modules ]; then
  npm install --silent
fi
npx tauri build "$@"

echo
echo "Build completata. Bundle disponibili in:"
ls -1 src-tauri/target/release/bundle/ 2>/dev/null || true
