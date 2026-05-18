"""Test minimi: end-to-end sul PDF di esempio."""
from __future__ import annotations

import os
import subprocess
from pathlib import Path

import cv2
import fitz
import numpy as np
import pytest

from dewarp.engine import DewarpParams, dewarp_page, split_two_up
from dewarp.pipeline import ProcessOptions, process_pdf

ROOT = Path(__file__).resolve().parents[1]
SAMPLE = ROOT / "samples" / "genogramma.pdf"


def _synthetic_warped_page(h: int = 1200, w: int = 800) -> np.ndarray:
    """Crea una pagina sintetica con righe di testo curve, per test riproducibili."""
    img = np.full((h, w, 3), 255, dtype=np.uint8)
    rng = np.random.default_rng(0)
    for i, y in enumerate(range(80, h - 80, 35)):
        # curva sinusoidale leggera per riga
        amp = 6 + (i % 3) * 2
        phase = (i % 5) * 0.4
        for x in range(40, w - 40):
            yy = int(y + amp * np.sin(2 * np.pi * x / w + phase))
            # parola di "lettere" simulata: blocchi neri intervallati
            if (x // 7) % 9 != 0:
                cv2.rectangle(img, (x, yy - 5), (x + 1, yy + 5), (10, 10, 10), -1)
    return img


def test_split_two_up_returns_one_when_no_gutter():
    page = _synthetic_warped_page(1000, 700)
    parts = split_two_up(page)
    assert len(parts) == 1


def test_dewarp_reduces_line_curvature():
    page = _synthetic_warped_page()
    out = dewarp_page(page, DewarpParams())
    # Misura la curvatura media delle righe via varianza dei centroidi per riga
    def line_var(im):
        gray = cv2.cvtColor(im, cv2.COLOR_BGR2GRAY)
        binary = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_MEAN_C,
                                       cv2.THRESH_BINARY_INV, 41, 15)
        k = cv2.getStructuringElement(cv2.MORPH_RECT, (41, 3))
        closed = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, k)
        num, labels, stats, _ = cv2.connectedComponentsWithStats(closed)
        variances = []
        for i in range(1, num):
            x, y, cw, ch, area = stats[i]
            if cw < im.shape[1] * 0.3 or ch < 5:
                continue
            comp = (labels[y:y + ch, x:x + cw] == i).astype(np.float32)
            row_idx = np.arange(ch).reshape(-1, 1)
            sums = (comp * row_idx).sum(axis=0)
            counts = comp.sum(axis=0)
            valid = counts > 0
            means = sums[valid] / counts[valid]
            if means.size > 5:
                variances.append(float(means.var()))
        return float(np.mean(variances)) if variances else 0.0

    v_before = line_var(page)
    v_after = line_var(out)
    # Almeno 25% di riduzione della curvatura attesa sul sintetico
    assert v_after < v_before * 0.75, f"varianza non ridotta: before={v_before:.2f} after={v_after:.2f}"


@pytest.mark.skipif(not SAMPLE.exists(), reason="PDF di esempio non disponibile")
def test_pipeline_on_sample_pdf(tmp_path):
    out = tmp_path / "out.pdf"
    stats = process_pdf(SAMPLE, out, ProcessOptions(dpi=200, ocr=False))
    assert out.exists()
    assert stats["output_pages"] >= stats["input_pages"]  # 2-up split
    with fitz.open(str(out)) as d:
        assert d.page_count == stats["output_pages"]
