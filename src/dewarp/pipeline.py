"""Pipeline end-to-end: PDF -> split 2-up -> dewarp -> (OCR) -> PDF."""
from __future__ import annotations

import logging
import tempfile
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
    params: DewarpParams = None  # type: ignore[assignment]

    def __post_init__(self):
        if self.params is None:
            self.params = DewarpParams()


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

    out_images: list[np.ndarray] = []
    for i, page_img in enumerate(rendered):
        report(i, n_src, f"pagina {i + 1}/{n_src}")
        halves = split_two_up(page_img, opts.params) if opts.split_two_up else [page_img]
        for half in halves:
            try:
                fixed = dewarp_page(half, opts.params)
            except Exception:
                log.exception("dewarp fallito sulla pagina %d, uso originale", i)
                fixed = half
            out_images.append(fixed)

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
