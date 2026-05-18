"""Pipeline end-to-end: PDF -> split 2-up -> dewarp -> (OCR) -> PDF."""
from __future__ import annotations

import logging
import os
import tempfile
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional

import cv2
import numpy as np

from .engine import DewarpParams, dewarp_page, split_two_up
from .pdf_io import images_to_pdf, render_pdf_pages

log = logging.getLogger(__name__)

ProgressCb = Callable[[int, int, str], None]


@dataclass
class ProcessOptions:
    dpi: int = 300
    ocr: bool = False
    ocr_lang: str = "ita"
    split_two_up: bool = True
    jpeg_quality: int = 88
    workers: int = 0  # 0 = auto (min 4, max numero CPU)
    params: DewarpParams = None  # type: ignore[assignment]

    def __post_init__(self):
        if self.params is None:
            self.params = DewarpParams()
        if self.workers <= 0:
            self.workers = max(1, min(os.cpu_count() or 1, 4))


def _ocr_pdf_overlay(image_pdf_path: Path, output_path: Path, lang: str) -> None:
    """Usa Tesseract per generare un PDF searchable a partire da un PDF di sole immagini.

    Strategia: re-rendiamo ogni pagina e passiamo a tesseract con config 'pdf'; poi
    uniamo i singoli PDF per pagina. Manteniamo le immagini per non rovinare il visivo.
    """
    import pytesseract
    import fitz

    doc = fitz.open(str(image_pdf_path))
    merged = fitz.open()
    try:
        with tempfile.TemporaryDirectory() as td:
            tdp = Path(td)
            for i, page in enumerate(doc):
                pix = page.get_pixmap(dpi=300, alpha=False)
                img_path = tdp / f"p_{i:04d}.png"
                pix.save(str(img_path))
                pdf_bytes = pytesseract.image_to_pdf_or_hocr(
                    str(img_path), lang=lang, extension="pdf"
                )
                page_pdf = fitz.open(stream=pdf_bytes, filetype="pdf")
                merged.insert_pdf(page_pdf)
                page_pdf.close()
            merged.save(str(output_path), deflate=True, garbage=3)
    finally:
        merged.close()
        doc.close()


def process_pdf(
    input_path: str | Path,
    output_path: str | Path,
    options: Optional[ProcessOptions] = None,
    progress: Optional[ProgressCb] = None,
) -> dict:
    """Elabora un PDF e scrive il risultato. Ritorna un dict con statistiche."""
    opts = options or ProcessOptions()
    input_path = Path(input_path)
    output_path = Path(output_path)

    def report(i: int, total: int, msg: str):
        if progress:
            try:
                progress(i, total, msg)
            except Exception:
                log.exception("progress callback raised")

    # Conta pagine sorgente
    import fitz
    with fitz.open(str(input_path)) as d:
        n_src = d.page_count

    report(0, n_src, "rendering")
    rendered = list(render_pdf_pages(input_path, dpi=opts.dpi))

    # Costruisce una lista di "tasks" (idx, half) preservando l'ordine.
    tasks: list[tuple[int, "np.ndarray"]] = []
    for i, page_img in enumerate(rendered):
        halves = split_two_up(page_img, opts.params) if opts.split_two_up else [page_img]
        for half in halves:
            tasks.append((i, half))

    out_images: list[np.ndarray | None] = [None] * len(tasks)
    progress_counter = [0]

    def run_one(args: tuple[int, int, np.ndarray]):
        slot, src_idx, half = args
        try:
            fixed = dewarp_page(half, opts.params)
        except Exception:
            log.exception("dewarp fallito sulla pagina %d, uso originale", src_idx)
            fixed = half
        out_images[slot] = fixed
        progress_counter[0] += 1
        report(progress_counter[0], len(tasks), f"pagina {progress_counter[0]}/{len(tasks)}")

    workers = opts.workers
    if workers > 1 and len(tasks) > 1:
        with ThreadPoolExecutor(max_workers=workers) as pool:
            list(pool.map(run_one, [(s, i, h) for s, (i, h) in enumerate(tasks)]))
    else:
        for slot, (i, h) in enumerate(tasks):
            run_one((slot, i, h))

    report(n_src, n_src, "scrittura PDF")
    if opts.ocr:
        with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as tmp:
            tmp_pdf = Path(tmp.name)
        try:
            images_to_pdf(out_images, tmp_pdf, dpi=opts.dpi, quality=opts.jpeg_quality)
            report(n_src, n_src, "OCR (puo' richiedere alcuni minuti)")
            _ocr_pdf_overlay(tmp_pdf, output_path, lang=opts.ocr_lang)
        finally:
            try:
                tmp_pdf.unlink()
            except OSError:
                pass
    else:
        images_to_pdf(out_images, output_path, dpi=opts.dpi, quality=opts.jpeg_quality)

    report(n_src, n_src, "done")
    return {
        "input_pages": n_src,
        "output_pages": len(out_images),
        "ocr": opts.ocr,
        "output": str(output_path),
    }
