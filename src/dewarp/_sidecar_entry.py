"""Entry point per il sidecar PyInstaller usato da Tauri.

Riusa lo stesso parser CLI ma forza il flag --serve. Argomenti supportati:
  --host, --port  (default 127.0.0.1 / 8765)
"""
from __future__ import annotations

import sys

from dewarp.cli import main


if __name__ == "__main__":
    args = sys.argv[1:]
    if "--serve" not in args:
        args = ["--serve", *args]
    raise SystemExit(main(args))
