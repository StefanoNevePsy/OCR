"""CLI per il tool dewarp."""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

from .engine import DewarpParams
from .pipeline import ProcessOptions, process_pdf


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(
        prog="dewarp",
        description="Raddrizza scansioni storte/curve in PDF preservando struttura e immagini.",
    )
    p.add_argument("input", type=Path, nargs="?", help="PDF di input (non richiesto con --serve)")
    p.add_argument("-o", "--output", type=Path, required=False, help="PDF di output (default: <input>.dewarped.pdf)")
    p.add_argument("--dpi", type=int, default=300, help="DPI di lavoro (default 300)")
    p.add_argument("--no-split", action="store_true", help="Non spezzare le scansioni 2-up sul gutter")
    p.add_argument("--ocr", action="store_true", help="Aggiunge layer di testo OCR (Tesseract)")
    p.add_argument("--lang", default="ita", help="Lingua OCR (default ita)")
    p.add_argument("--jpeg-quality", type=int, default=88, help="Qualita' JPEG nelle pagine di output (1-100)")
    p.add_argument("--engine", choices=["polynomial", "polyline"], default="polynomial",
                   help="Motore di dewarp. polynomial=fit di grado 2 sulla midline (default); "
                        "polyline=baseline samplate + smoothing, robusto su pagine difficili")
    p.add_argument("--poly-degree", type=int, default=3, help="Grado del polinomio per le righe")
    p.add_argument("--figure-attenuation", type=float, default=0.15,
                   help="Quanto warp applicare sulle figure (0=niente, 1=pieno)")
    p.add_argument("--serve", action="store_true", help="Avvia la GUI web invece di processare un file")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8765)
    args = p.parse_args(argv)

    if args.serve:
        from .webapp.server import run_server
        run_server(host=args.host, port=args.port)
        return 0

    if args.input is None:
        p.error("specifica un file PDF di input o usa --serve per avviare la GUI")
    if not args.input.exists():
        print(f"Input non trovato: {args.input}", file=sys.stderr)
        return 2

    output = args.output or args.input.with_suffix(".dewarped.pdf")

    params = DewarpParams(
        target_dpi=args.dpi,
        engine=args.engine,
        poly_degree=args.poly_degree,
        figure_attenuation=args.figure_attenuation,
    )
    opts = ProcessOptions(
        dpi=args.dpi,
        ocr=args.ocr,
        ocr_lang=args.lang,
        split_two_up=not args.no_split,
        jpeg_quality=args.jpeg_quality,
        params=params,
    )

    t0 = time.time()
    last_msg = [""]

    def progress(i: int, total: int, msg: str):
        if msg != last_msg[0]:
            print(f"  [{i}/{total}] {msg}", flush=True)
            last_msg[0] = msg

    print(f"Elaborazione: {args.input} -> {output}")
    stats = process_pdf(args.input, output, options=opts, progress=progress)
    dt = time.time() - t0
    print(f"Fatto in {dt:.1f}s. Pagine sorgente: {stats['input_pages']}, output: {stats['output_pages']}.")
    print(f"Output: {stats['output']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
