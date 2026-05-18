"""PDF I/O: rendering pagina -> ndarray e scrittura pagine raddrizzate -> PDF."""
from __future__ import annotations

import io
from pathlib import Path
from typing import Iterator

import cv2
import fitz  # PyMuPDF
import img2pdf
import numpy as np
from PIL import Image


def render_pdf_pages(path: str | Path, dpi: int = 300) -> Iterator[np.ndarray]:
    """Genera le pagine del PDF come ndarray BGR uint8."""
    doc = fitz.open(str(path))
    try:
        zoom = dpi / 72.0
        mat = fitz.Matrix(zoom, zoom)
        for page in doc:
            pix = page.get_pixmap(matrix=mat, alpha=False)
            arr = np.frombuffer(pix.samples, dtype=np.uint8).reshape(pix.height, pix.width, pix.n)
            # PyMuPDF restituisce RGB; OpenCV usa BGR
            if pix.n == 3:
                arr = cv2.cvtColor(arr, cv2.COLOR_RGB2BGR)
            elif pix.n == 4:
                arr = cv2.cvtColor(arr, cv2.COLOR_RGBA2BGR)
            yield arr
    finally:
        doc.close()


def encode_jpeg(img: np.ndarray, quality: int = 88) -> bytes:
    """Codifica un'immagine BGR/gray uint8 in bytes JPEG."""
    if img.ndim == 2:
        pil = Image.fromarray(img, mode="L")
    else:
        pil = Image.fromarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
    buf = io.BytesIO()
    pil.save(buf, format="JPEG", quality=quality, optimize=True)
    return buf.getvalue()


def images_to_pdf(images: list[np.ndarray], output_path: str | Path, dpi: int = 300, quality: int = 88) -> None:
    """Scrive un PDF non-searchable a partire da una lista di immagini."""
    jpegs = [encode_jpeg(img, quality=quality) for img in images]
    # img2pdf vuole la dimensione fisica per evitare upscaling
    layout = img2pdf.get_layout_fun()
    with open(str(output_path), "wb") as f:
        f.write(img2pdf.convert(jpegs, layout_fun=layout, dpi=dpi))
