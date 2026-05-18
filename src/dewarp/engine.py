"""Core image-space dewarp algorithms.

Pipeline per pagina singola (gia' separata da un eventuale 2-up):
  1. Binarizzazione adattiva
  2. Deskew globale (rotazione che minimizza l'entropia del profilo orizzontale)
  3. Rilevamento midline delle righe di testo (morfologia orizzontale + tracking colonna per colonna)
  4. Fit polinomiale di ogni midline (grado configurabile)
  5. Costruzione di un campo di displacement verticale interpolando tra le righe
  6. Rilevamento aree "figura" (componenti grandi non-testuali) e attenuazione del warp lì
  7. cv2.remap

L'output ha le righe di testo orizzontali, mantenendo larghezza e ordine.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional

import cv2
import numpy as np


@dataclass
class DewarpParams:
    """Parametri esposti all'utente. I default funzionano sulla maggior parte dei libri."""
    target_dpi: int = 300                # DPI di lavoro
    min_line_height_px: int = 8          # altezza minima di una riga di testo
    morph_kernel_w: int = 41             # larghezza kernel orizzontale per legare le parole
    poly_degree: int = 2                 # grado del polinomio di fit per ogni riga
    min_lines_for_warp: int = 6          # sotto questo numero applichiamo solo deskew
    max_displacement_frac: float = 0.04  # |map_y - y| viene clampato a frac * h
    figure_attenuation: float = 0.15     # 0 = niente warp sulle figure, 1 = warp pieno
    figure_min_area_frac: float = 0.010  # area minima (frazione pagina) per essere figura
    gutter_search_frac: float = 0.20     # zona di ricerca del gutter attorno al centro
    gutter_min_contrast: float = 12.0    # contrasto minimo per accettare lo split
    deskew_max_deg: float = 6.0          # rotazione massima cercata
    deskew_step_deg: float = 0.2         # step della ricerca
    pad_white: int = 20                  # padding bianco per il remap (evita bordi neri)
    smooth_lines_sigma: float = 1.5      # smoothing verticale tra righe vicine


# ----------------------------- utilita' di base ----------------------------- #

def _to_gray(img: np.ndarray) -> np.ndarray:
    if img.ndim == 2:
        return img
    if img.shape[2] == 4:
        img = cv2.cvtColor(img, cv2.COLOR_BGRA2BGR)
    return cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)


def _binarize(gray: np.ndarray) -> np.ndarray:
    """Sauvola-like via adaptive threshold; restituisce maschera testo (255 = ink)."""
    # block size dispari, ~ altezza di una x in pixel a 300 dpi (~30) * 3
    bs = max(31, (gray.shape[0] // 30) | 1)
    th = cv2.adaptiveThreshold(
        gray, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY_INV, bs, 15
    )
    return th


# ----------------------------- split 2-up sul gutter ----------------------------- #

def split_two_up(img: np.ndarray, params: Optional[DewarpParams] = None) -> list[np.ndarray]:
    """Se l'immagine sembra contenere due pagine affiancate, la divide sul gutter.

    Restituisce 1 o 2 immagini. Euristica: cerca una valle scura verticale nella zona centrale.
    Se la valle non e' significativa restituisce la pagina intera.
    """
    p = params or DewarpParams()
    h, w = img.shape[:2]
    if w < h * 1.15:
        # Aspect ratio non da doppia pagina
        return [img]

    gray = _to_gray(img)
    # Profilo verticale: media di scurezza per colonna
    inv = 255 - gray
    col_dark = inv.mean(axis=0)
    # zona di ricerca
    mid = w // 2
    half = int(w * p.gutter_search_frac / 2)
    lo, hi = max(0, mid - half), min(w, mid + half)
    region = col_dark[lo:hi]
    if region.size == 0:
        return [img]

    # Smooth per evitare false valli
    k = max(5, (w // 200) | 1)
    region_s = cv2.GaussianBlur(region.reshape(1, -1).astype(np.float32), (k, 1), 0).ravel()
    # Cerchiamo il MASSIMO di scurezza (gutter ombrato) oppure il MINIMO (margine bianco).
    # Nei libri scansionati il gutter e' spesso piu' scuro; nei reflow puliti piu' chiaro.
    baseline = float(np.median(col_dark))
    peak_idx = int(np.argmax(region_s))
    trough_idx = int(np.argmin(region_s))
    peak_val = float(region_s[peak_idx])
    trough_val = float(region_s[trough_idx])

    if peak_val - baseline > baseline - trough_val:
        split_x = lo + peak_idx
    else:
        split_x = lo + trough_idx

    # Se la differenza dal baseline e' minima, non splittare
    contrast = max(abs(peak_val - baseline), abs(trough_val - baseline))
    if contrast < p.gutter_min_contrast:
        return [img]

    left = img[:, :split_x]
    right = img[:, split_x:]
    return [left, right]


# ----------------------------- deskew globale ----------------------------- #

def _row_score(binary: np.ndarray) -> float:
    """Score alto se le righe sono ben separate orizzontalmente (varianza del profilo)."""
    profile = binary.sum(axis=1).astype(np.float64)
    return float(profile.var())


def _rotate(img: np.ndarray, angle_deg: float, border_value: int = 255) -> np.ndarray:
    h, w = img.shape[:2]
    M = cv2.getRotationMatrix2D((w / 2, h / 2), angle_deg, 1.0)
    cos = abs(M[0, 0])
    sin = abs(M[0, 1])
    new_w = int((h * sin) + (w * cos))
    new_h = int((h * cos) + (w * sin))
    M[0, 2] += (new_w / 2) - (w / 2)
    M[1, 2] += (new_h / 2) - (h / 2)
    bv = (border_value,) * (1 if img.ndim == 2 else img.shape[2])
    return cv2.warpAffine(
        img, M, (new_w, new_h),
        flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_CONSTANT, borderValue=bv,
    )


def deskew(img: np.ndarray, params: DewarpParams) -> tuple[np.ndarray, float]:
    """Trova l'angolo che massimizza la separazione orizzontale delle righe."""
    gray = _to_gray(img)
    # Downsample per velocita'
    scale = 800 / max(gray.shape)
    small = cv2.resize(gray, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    binary_small = _binarize(small)

    best_angle = 0.0
    best_score = _row_score(binary_small)
    step = params.deskew_step_deg
    limit = params.deskew_max_deg
    angle = -limit
    while angle <= limit + 1e-6:
        if abs(angle) < 1e-3:
            angle += step
            continue
        rot = _rotate(binary_small, angle, border_value=0)
        s = _row_score(rot)
        if s > best_score:
            best_score = s
            best_angle = angle
        angle += step

    if abs(best_angle) < 1e-3:
        return img, 0.0
    return _rotate(img, best_angle, border_value=255), best_angle


# ----------------------------- rilevamento midline righe ----------------------------- #

def _detect_text_mask(gray: np.ndarray, params: DewarpParams) -> np.ndarray:
    """Maschera binaria del testo, parole legate orizzontalmente."""
    binary = _binarize(gray)
    # Chiude i gap tra lettere/parole per ottenere "salami" orizzontali per riga
    k = cv2.getStructuringElement(cv2.MORPH_RECT, (params.morph_kernel_w, 3))
    closed = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, k)
    # Apri verticalmente per togliere linee verticali spurie
    k2 = cv2.getStructuringElement(cv2.MORPH_RECT, (3, 3))
    closed = cv2.morphologyEx(closed, cv2.MORPH_OPEN, k2)
    return closed


def _find_figure_mask(gray: np.ndarray, params: DewarpParams) -> np.ndarray:
    """Maschera (0..1) con peso del warp da applicare. 0 = identita', 1 = warp pieno.

    Le figure (componenti grandi e tozze) ricevono peso vicino a figure_attenuation.
    """
    h, w = gray.shape
    binary = _binarize(gray)
    # Chiusura grande per fondere figure intere
    k_big = cv2.getStructuringElement(cv2.MORPH_RECT, (35, 35))
    blob = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, k_big)
    num, labels, stats, _ = cv2.connectedComponentsWithStats(blob, connectivity=8)

    figure_mask = np.zeros((h, w), dtype=np.uint8)
    page_area = h * w
    for i in range(1, num):
        x, y, cw, ch, area = stats[i]
        if area < params.figure_min_area_frac * page_area:
            continue
        # Una riga di testo e' molto larga e bassa: ratio w/h alto. Le figure sono piu' "quadrate".
        ratio = cw / max(ch, 1)
        if ratio > 12:
            continue  # probabilmente una riga di testo lunga
        # Densita': testo ha bassa densita' nel bounding box dopo la chiusura grande, figure alta
        density = area / (cw * ch)
        if density < 0.35:
            continue
        cv2.rectangle(figure_mask, (x, y), (x + cw, y + ch), 255, -1)

    # Smooth della maschera
    figure_mask = cv2.GaussianBlur(figure_mask, (61, 61), 0)
    weight = 1.0 - (figure_mask.astype(np.float32) / 255.0) * (1.0 - params.figure_attenuation)
    return weight  # shape (h, w), float32 in [figure_attenuation, 1.0]


def _trace_lines(text_mask: np.ndarray, params: DewarpParams) -> list[np.ndarray]:
    """Per ogni riga di testo, restituisce un array (W,) con la y della midline (NaN dove assente)."""
    h, w = text_mask.shape
    num, labels, stats, _ = cv2.connectedComponentsWithStats(text_mask, connectivity=8)

    lines: list[np.ndarray] = []
    for i in range(1, num):
        x, y, cw, ch, area = stats[i]
        if ch < params.min_line_height_px:
            continue
        if cw < w * 0.10:  # troppo corta per essere una riga utile
            continue
        if ch > h * 0.25:  # blob enorme, e' una figura
            continue
        comp = (labels[y:y + ch, x:x + cw] == i)
        # Per ogni colonna del bounding box, y medio dei pixel attivi
        ys = np.full(w, np.nan, dtype=np.float64)
        col_any = comp.any(axis=0)
        # indice (locale) della y media
        row_idx = np.arange(ch).reshape(-1, 1)
        sums = (comp * row_idx).sum(axis=0)
        counts = comp.sum(axis=0)
        with np.errstate(invalid="ignore", divide="ignore"):
            means = np.where(counts > 0, sums / np.maximum(counts, 1), np.nan)
        ys[x:x + cw][col_any] = y + means[col_any]
        lines.append(ys)
    # Ordina per y media
    lines.sort(key=lambda a: np.nanmean(a))
    return lines


def _fit_polynomial(ys: np.ndarray, degree: int, width: int) -> np.ndarray:
    """Fitta un polinomio sulle x dove ys non e' NaN; estrapola in modo conservativo.

    Fuori dal range dei dati osservati, manteniamo costante il valore al bordo
    invece di lasciare oscillare il polinomio (causa primaria di distorsioni grosse).
    """
    valid = ~np.isnan(ys)
    if valid.sum() < degree + 2:
        return np.full(width, np.nanmean(ys) if valid.any() else 0.0)
    xs_idx = np.where(valid)[0]
    x = xs_idx.astype(np.float64)
    y = ys[valid].astype(np.float64)
    xc = x.mean()
    xs = x.std() or 1.0
    coef = np.polyfit((x - xc) / xs, y, degree)
    xx_all = np.arange(width, dtype=np.float64)
    xx_norm = (xx_all - xc) / xs
    out = np.polyval(coef, xx_norm)
    # Clamp ai bordi: estrapolazione costante sotto x_min e sopra x_max
    x_min, x_max = float(xs_idx.min()), float(xs_idx.max())
    val_left = np.polyval(coef, (x_min - xc) / xs)
    val_right = np.polyval(coef, (x_max - xc) / xs)
    out[xx_all < x_min] = val_left
    out[xx_all > x_max] = val_right
    return out


# ----------------------------- dewarp principale ----------------------------- #

def dewarp_page(img: np.ndarray, params: Optional[DewarpParams] = None) -> np.ndarray:
    """Raddrizza una pagina singola. Input/output in BGR o gray uint8."""
    p = params or DewarpParams()
    gray = _to_gray(img)

    # Pad bianco per evitare bordi neri dopo remap
    pad = p.pad_white
    if pad > 0:
        bv = (255,) * (1 if img.ndim == 2 else img.shape[2])
        img_p = cv2.copyMakeBorder(img, pad, pad, pad, pad, cv2.BORDER_CONSTANT, value=bv)
        gray_p = cv2.copyMakeBorder(gray, pad, pad, pad, pad, cv2.BORDER_CONSTANT, value=255)
    else:
        img_p, gray_p = img, gray

    # 1. Deskew globale
    img_d, _angle = deskew(img_p, p)
    gray_d = _to_gray(img_d)

    h, w = gray_d.shape
    text_mask = _detect_text_mask(gray_d, p)
    lines = _trace_lines(text_mask, p)

    if len(lines) < p.min_lines_for_warp:
        # Troppo poche righe: rischiamo distorsioni, ci fermiamo al deskew
        return img_d

    # 2. Fit polinomiale di ogni riga
    polys = np.stack([_fit_polynomial(ys, p.poly_degree, w) for ys in lines], axis=0)
    # Ordina per y media (gia' ordinato, ma sicurezza)
    order = np.argsort(polys.mean(axis=1))
    polys = polys[order]

    # 3. Target y per ogni riga: la y media stessa (cosi' la pagina non si "comprime")
    targets = polys.mean(axis=1)  # shape (N,)

    # 4. Costruisci campo di displacement verticale dy(x, y):
    #    per ciascuna colonna x, interpola y_source come funzione lineare tra le righe.
    #    map_y[y_dst, x] = y_src.
    N = polys.shape[0]
    # Smoothing tra righe consecutive (sigma in indici riga): aggiunge stabilita'.
    if p.smooth_lines_sigma > 0 and N >= 5:
        k = max(3, int(p.smooth_lines_sigma * 3) | 1)
        polys_s = cv2.GaussianBlur(polys.astype(np.float32), (1, k), p.smooth_lines_sigma)
    else:
        polys_s = polys.astype(np.float32)

    # Per ogni colonna, costruisci la funzione monotona target -> source
    map_y = np.zeros((h, w), dtype=np.float32)
    map_x = np.tile(np.arange(w, dtype=np.float32), (h, 1))
    y_dst = np.arange(h, dtype=np.float32)

    for x in range(w):
        src_ys = polys_s[:, x]
        # Forza monotonia (le righe non si scavalcano)
        src_ys = np.maximum.accumulate(src_ys)
        # Aggiunge punti di ancoraggio a y=0 e y=h-1 estrapolando con la pendenza media
        # tra prima/ultima coppia per non distorcere margini
        if N >= 2:
            slope_top = src_ys[1] - src_ys[0]
            tgt_step_top = targets[1] - targets[0] if targets[1] != targets[0] else 1.0
            slope_top_ratio = slope_top / tgt_step_top
            anchor_top_src = src_ys[0] - targets[0] * slope_top_ratio
            slope_bot = src_ys[-1] - src_ys[-2]
            tgt_step_bot = targets[-1] - targets[-2] if targets[-1] != targets[-2] else 1.0
            slope_bot_ratio = slope_bot / tgt_step_bot
            anchor_bot_src = src_ys[-1] + (h - 1 - targets[-1]) * slope_bot_ratio
        else:
            anchor_top_src = src_ys[0]
            anchor_bot_src = src_ys[-1]

        xp = np.concatenate([[0.0], targets.astype(np.float64), [float(h - 1)]])
        fp = np.concatenate([[anchor_top_src], src_ys.astype(np.float64), [anchor_bot_src]])
        # Garantisce xp monotono crescente
        order_xp = np.argsort(xp)
        xp = xp[order_xp]
        fp = fp[order_xp]
        map_y[:, x] = np.interp(y_dst, xp, fp).astype(np.float32)

    # 5. Attenuazione su aree figura: blend tra map_y (warp) e y identita'
    weight = _find_figure_mask(gray_d, p)  # shape (h, w), float32
    identity = np.tile(y_dst.reshape(-1, 1), (1, w))
    map_y = weight * map_y + (1.0 - weight) * identity

    # 6. Clamp del displacement totale per evitare distorsioni catastrofiche
    max_disp = p.max_displacement_frac * h
    delta = np.clip(map_y - identity, -max_disp, max_disp)
    map_y = identity + delta

    # 6. Remap
    bv = (255,) * (1 if img_d.ndim == 2 else img_d.shape[2])
    out = cv2.remap(
        img_d, map_x, map_y,
        interpolation=cv2.INTER_CUBIC,
        borderMode=cv2.BORDER_CONSTANT, borderValue=bv,
    )
    return out
