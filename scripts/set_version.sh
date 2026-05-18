#!/usr/bin/env bash
# Sincronizza il numero versione in tutti i file del progetto.
# Uso:   scripts/set_version.sh 0.2.0
# In CI: scripts/set_version.sh "${GITHUB_REF_NAME#v}"
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Uso: $0 <version> (es. 0.2.0)" >&2
  exit 2
fi

VER="$1"
# Validazione minima: deve essere semver tipo X.Y.Z (eventuale -rc.N opzionale)
if ! [[ "$VER" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9._-]+)?$ ]]; then
  echo "Versione non valida: $VER (atteso X.Y.Z)" >&2
  exit 2
fi

# Estrai X.Y.Z (rimuove eventuale -prerelease) per Android versionCode
CORE_VER="${VER%%-*}"
IFS='.' read -r MAJ MIN PATCH <<< "$CORE_VER"
MAJ=${MAJ:-0}; MIN=${MIN:-0}; PATCH=${PATCH:-0}
ANDROID_VC=$((MAJ * 10000 + MIN * 100 + PATCH))

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "==> Versione: $VER (Android versionCode: $ANDROID_VC)"

# Python package
python3 - <<PY
import re
from pathlib import Path
p = Path("$ROOT/pyproject.toml")
txt = p.read_text()
txt = re.sub(r'^version = ".*"', f'version = "$VER"', txt, count=1, flags=re.M)
p.write_text(txt)
print("  pyproject.toml OK")
PY

# Tauri Cargo.toml
python3 - <<PY
import re
from pathlib import Path
p = Path("$ROOT/desktop/src-tauri/Cargo.toml")
txt = p.read_text()
txt = re.sub(r'^version = ".*"', f'version = "$VER"', txt, count=1, flags=re.M)
p.write_text(txt)
print("  Cargo.toml OK")
PY

# Tauri tauri.conf.json
python3 - <<PY
import json
from pathlib import Path
p = Path("$ROOT/desktop/src-tauri/tauri.conf.json")
d = json.loads(p.read_text())
d["version"] = "$VER"
p.write_text(json.dumps(d, indent=2) + "\n")
print("  tauri.conf.json OK")
PY

# Android versionName / versionCode
python3 - <<PY
import re
from pathlib import Path
p = Path("$ROOT/android/app/build.gradle.kts")
txt = p.read_text()
txt = re.sub(r'versionCode = \d+', f'versionCode = $ANDROID_VC', txt, count=1)
txt = re.sub(r'versionName = ".*"', f'versionName = "$VER"', txt, count=1)
p.write_text(txt)
print("  android/app/build.gradle.kts OK")
PY

echo "==> Sync versione completato"
