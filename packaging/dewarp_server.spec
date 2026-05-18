# PyInstaller spec per il sidecar dewarp-server (mono-file)
# Generato manualmente per il pattern Tauri.
# Build: pyinstaller --clean packaging/dewarp_server.spec

import sys
from pathlib import Path

ROOT = Path(SPECPATH).parent
SRC = ROOT / "src"

block_cipher = None

a = Analysis(
    [str(SRC / "dewarp" / "_sidecar_entry.py")],
    pathex=[str(SRC)],
    binaries=[],
    datas=[
        # Static SPA
        (str(SRC / "dewarp" / "webapp" / "static"), "dewarp/webapp/static"),
    ],
    hiddenimports=[
        "dewarp",
        "dewarp.engine",
        "dewarp.pdf_io",
        "dewarp.pipeline",
        "dewarp.cli",
        "dewarp.webapp",
        "dewarp.webapp.server",
        "uvicorn",
        "uvicorn.lifespan.on",
        "uvicorn.lifespan.off",
        "uvicorn.logging",
        "uvicorn.loops.auto",
        "uvicorn.loops.asyncio",
        "uvicorn.protocols.http.auto",
        "uvicorn.protocols.http.h11_impl",
        "uvicorn.protocols.websockets.auto",
        "uvicorn.protocols.websockets.websockets_impl",
        "fastapi",
        "starlette",
        "starlette.routing",
        "anyio",
        "pytesseract",
    ],
    excludes=["matplotlib", "scipy", "pandas", "tkinter", "PySide6", "PyQt5", "PyQt6"],
    hookspath=[],
    runtime_hooks=[],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)
pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name="dewarp-server",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=True,
    icon=None,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
