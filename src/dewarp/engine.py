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
    # "polynomial": fit polinomiale di grado 2 sulla midline di ogni riga, robust
    #   per pagine con tante righe regolari ma puo' oscillare se le features sono
    #   poche o rumorose (cap maxDisplacementFrac protegge dai casi peggiori).
    # "polyline": polyline (campioni della baseline smussati) per ogni riga;
    #   non estrapola, non oscilla, segue letteralmente il dato. Migliore per
    #   pagine con poche righe, figure grandi, o curvature non-polinomiali.
    engine: str = "polynomial"
    min_line_height_px: int = 8          # altezza minima di una riga di testo
    morph_kernel_w: int = 41             # larghezza kernel orizzontale per legare le parole
    poly_degree: int = 2                 # grado massimo del polinomio (puo' essere ridotto adattivamente)
    min_features_for_warp: int = 3       # sotto questo numero applichiamo solo deskew
    min_text_lines_full_confidence: int = 6  # da qui in su si usa max_displacement pieno
    max_displacement_frac: float = 0.04  # |map_y - y| viene clampato a frac * h
    support_feature_weight: float = 0.5  # peso simbolico (non usato come weighted fit)
    hough_min_len_frac: float = 0.18     # lunghezza minima linea Hough come frazione di w
    hough_max_slope: float = 0.12        # tan(angolo) massimo per considerarla orizzontale
    # Filtri "is text line" usati dal motore polyline:
    line_min_width_frac: float = 0.18    # lunghezza minima riga (frazione di w)
    line_min_transitions: int = 8        # transizioni 0<->255 nel profilo verticale
                                         # del bounding box (specks/graffi ne hanno poche)
    polyline_smooth_px: int = 25         # finestra di smoothing orizzontale per la baseline
    polyline_descender_thresh: float = 0.35  # tolleranza per scartare descender (g, p, q)
                                             # come frazione dell'altezza riga
    figure_attenuation: float = 0.15     # 0 = niente warp sulle figure, 1 = warp pieno
    figure_min_area_frac: float = 0.010  # area minima (frazione pagina) per essere figura
    figure_skip_edges_area_frac: float = 0.18  # figure piu' grandi: bordi NON usati come features
                                               # (la weight mask le protegge, ma se i bordi entrano
                                               #  nel fit producono slope estremi al confine)
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
    """Trova l'angolo che massimizza la separazione orizzontale delle righe.

    Ricerca coarse-to-fine: prima passa con step ~1.0 deg, poi rifinisce con
    step ~0.1 deg attorno al miglior angolo. Circa 3x piu' veloce della
    ricerca uniforme con lo stesso risultato.
    """
    gray = _to_gray(img)
    # Downsample per velocita'
    scale = 800 / max(gray.shape)
    small = cv2.resize(gray, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    binary_small = _binarize(small)

    base_score = _row_score(binary_small)

    def search(center: float, radius: float, step: float) -> tuple[float, float]:
        best_a = center
        best_s = base_score if center == 0.0 else _row_score(_rotate(binary_small, center, border_value=0))
        a = center - radius
        while a <= center + radius + 1e-6:
            if abs(a) < 1e-3:
                a += step
                continue
            rot = _rotate(binary_small, a, border_value=0)
            s = _row_score(rot)
            if s > best_s:
                best_s = s; best_a = a
            a += step
        return best_a, best_s

    # Coarse: ~1 deg su tutto il range
    coarse_step = max(1.0, params.deskew_step_deg * 5)
    coarse_a, _ = search(0.0, params.deskew_max_deg, coarse_step)
    # Fine: 0.1 deg in [+/-coarse_step]
    fine_a, _ = search(coarse_a, coarse_step, params.deskew_step_deg)

    binary_small.release() if hasattr(binary_small, "release") else None

    if abs(fine_a) < 1e-3:
        return img, 0.0
    return _rotate(img, fine_a, border_value=255), fine_a


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


def _hough_horizontal_features(gray: np.ndarray, p: DewarpParams) -> list[np.ndarray]:
    """Linee Hough quasi-orizzontali (bordi figure, tabelle, decorazioni).

    Ognuna viene espressa nello stesso formato di _trace_lines: array (w,) con la
    y stimata per ogni colonna nel range della linea, NaN altrove.
    """
    h, w = gray.shape
    edges = cv2.Canny(gray, 60, 160)
    min_len = max(40, int(w * p.hough_min_len_frac))
    lines = cv2.HoughLinesP(
        edges, 1, np.pi / 180,
        threshold=80, minLineLength=min_len, maxLineGap=15,
    )
    out: list[np.ndarray] = []
    if lines is None:
        return out
    for ln in lines:
        x1, y1, x2, y2 = (int(v) for v in ln[0])
        if x2 < x1:
            x1, x2 = x2, x1
            y1, y2 = y2, y1
        dx = x2 - x1
        if dx < min_len:
            continue
        slope = (y2 - y1) / dx
        if abs(slope) > p.hough_max_slope:
            continue
        ys = np.full(w, np.nan, dtype=np.float64)
        xs = np.arange(x1, x2 + 1)
        ys[xs] = y1 + slope * (xs - x1)
        out.append(ys)
    return out


def _figure_horizontal_edges(gray: np.ndarray, params: DewarpParams) -> list[np.ndarray]:
    """Top e bottom edge delle figure rilevate. Quando la pagina ha poco testo ma
    una figura grande, questi bordi sono spesso l'unica fonte di info sul warp.
    """
    h, w = gray.shape
    binary = _binarize(gray)
    k_big = cv2.getStructuringElement(cv2.MORPH_RECT, (35, 35))
    blob = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, k_big)
    num, labels, stats, _ = cv2.connectedComponentsWithStats(blob, connectivity=8)
    page_area = h * w
    feats: list[np.ndarray] = []
    for i in range(1, num):
        x, y, cw, ch, area = stats[i]
        if area < params.figure_min_area_frac * page_area:
            continue
        # Figure troppo grandi dominano il polynomial fit: i loro bordi non
        # vengono piu' usati come features. La weight mask le protegge comunque.
        if area > params.figure_skip_edges_area_frac * page_area:
            continue
        ratio = cw / max(ch, 1)
        if ratio > 12 or cw < w * 0.10:
            continue
        density = area / (cw * ch)
        if density < 0.35:
            continue
        comp = (labels[y:y + ch, x:x + cw] == i)
        # bordo superiore: per ogni colonna, prima riga attiva
        col_any = comp.any(axis=0)
        first_row = comp.argmax(axis=0).astype(np.float64)
        last_row = (ch - 1) - comp[::-1, :].argmax(axis=0).astype(np.float64)
        ys_top = np.full(w, np.nan, dtype=np.float64)
        ys_bot = np.full(w, np.nan, dtype=np.float64)
        ys_top[x:x + cw][col_any] = y + first_row[col_any]
        ys_bot[x:x + cw][col_any] = y + last_row[col_any]
        feats.append(ys_top)
        feats.append(ys_bot)
    return feats


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


# ----------------------------- motore polyline ----------------------------- #

def _is_text_line_component(roi_bin: np.ndarray, cw: int, params: DewarpParams) -> bool:
    """Filtra "specks and scratches": una vera riga di testo ha tante transizioni
    inchiostro/sfondo lungo l'asse x. Un graffio o un piccolo blob no.

    `roi_bin` e' la ROI binarizzata della componente (255 = inchiostro).
    """
    if cw < 4:
        return False
    # Profilo orizzontale: per ogni colonna, c'e' inchiostro? (1 o 0)
    col_has_ink = (roi_bin.max(axis=0) > 0).astype(np.int8)
    transitions = int(np.abs(np.diff(col_has_ink)).sum())
    return transitions >= params.line_min_transitions


def _extract_baselines(
    text_mask: np.ndarray,
    binary: np.ndarray,
    params: DewarpParams,
) -> list[np.ndarray]:
    """Per ogni riga di testo, costruisce la baseline (y inferiore) come polyline.

    Ritorna una lista di array (W,) con y baseline per colonna, NaN dove la riga
    non e' presente. La baseline e' robusta ai descender (g, p, q, y): per
    colonne dove il pixel piu' basso scende sotto la mediana della riga di una
    quantita' significativa, si interpola dai vicini.
    """
    h, w = text_mask.shape
    num, labels, stats, _ = cv2.connectedComponentsWithStats(text_mask, connectivity=8)

    baselines: list[tuple[float, np.ndarray]] = []
    for i in range(1, num):
        x, y, cw, ch, _area = stats[i]
        if ch < params.min_line_height_px or ch > h * 0.25:
            continue
        if cw < w * params.line_min_width_frac:
            continue

        # ROI binarizzata: pixel di inchiostro dentro il bounding box di questa riga.
        # Usiamo `binary` (no morfologia di linking) per avere lettere distinte.
        roi_label = (labels[y:y + ch, x:x + cw] == i)
        roi_bin = binary[y:y + ch, x:x + cw] * roi_label.astype(np.uint8)

        if not _is_text_line_component(roi_bin, cw, params):
            continue

        # baseline[col] = riga piu' bassa che ha inchiostro
        ys_per_col = np.full(cw, np.nan, dtype=np.float64)
        has_ink = roi_bin > 0
        col_any = has_ink.any(axis=0)
        # indice riga piu' basso per ogni colonna con inchiostro
        # (argmax dal basso su flip)
        flipped = has_ink[::-1, :]
        rev_first = flipped.argmax(axis=0)
        last_row = (ch - 1) - rev_first  # in coordinate locali della ROI
        ys_per_col[col_any] = y + last_row[col_any].astype(np.float64)

        # Filtra descender: scarta colonne dove la baseline locale scende sotto
        # la mediana di una quantita' significativa.
        valid = ~np.isnan(ys_per_col)
        if valid.sum() < cw * 0.5:
            continue
        median_y = float(np.median(ys_per_col[valid]))
        descender_limit = median_y + params.polyline_descender_thresh * ch
        outliers = valid & (ys_per_col > descender_limit)
        ys_per_col[outliers] = np.nan

        # Smussa orizzontalmente con uniform_filter (ignorando NaN tramite forward fill).
        # Forward/backward fill dei NaN per evitare di propagarli, poi smooth.
        valid_after = ~np.isnan(ys_per_col)
        if valid_after.sum() < cw * 0.5:
            continue
        filled = ys_per_col.copy()
        # forward fill
        last = np.nan
        for j in range(cw):
            if np.isnan(filled[j]):
                if not np.isnan(last):
                    filled[j] = last
            else:
                last = filled[j]
        # backward fill (per la coda iniziale)
        last = np.nan
        for j in range(cw - 1, -1, -1):
            if np.isnan(filled[j]):
                if not np.isnan(last):
                    filled[j] = last
            else:
                last = filled[j]
        # Smoothing: media mobile lungo x
        sm_w = max(3, params.polyline_smooth_px | 1)  # dispari
        kernel = np.ones(sm_w, dtype=np.float64) / sm_w
        smoothed = np.convolve(filled, kernel, mode="same")

        # Promuovi alla larghezza piena della pagina, NaN al di fuori
        ys_full = np.full(w, np.nan, dtype=np.float64)
        ys_full[x:x + cw] = smoothed
        baselines.append((float(np.mean(smoothed)), ys_full))

    baselines.sort(key=lambda p: p[0])
    return [b for _, b in baselines]


def _dewarp_with_polylines(
    img: np.ndarray,
    baselines: list[np.ndarray],
    h: int,
    w: int,
    params: DewarpParams,
) -> np.ndarray:
    """Costruisce mapY interpolando verticalmente tra polyline adiacenti, con
    estensione costante (non lineare) ai margini orizzontali e clamp finale.
    """
    n = len(baselines)
    # Per ogni baseline, riempie NaN ai margini orizzontali con valore al bordo
    # (estensione costante: non estrapola lo slope, evita lo "spirale" effect).
    extended = []
    targets = []
    for bl in baselines:
        valid = ~np.isnan(bl)
        if valid.sum() < 2:
            continue
        idx = np.where(valid)[0]
        first_v = bl[idx[0]]
        last_v = bl[idx[-1]]
        ext = bl.copy()
        ext[:idx[0]] = first_v
        ext[idx[-1] + 1:] = last_v
        # Eventuali NaN interni (improbabili dopo fill): interp lineare
        if np.isnan(ext).any():
            xs = np.arange(w)
            valid_ext = ~np.isnan(ext)
            ext = np.interp(xs, xs[valid_ext], ext[valid_ext])
        extended.append(ext)
        targets.append(float(np.mean(ext)))

    if len(extended) < params.min_features_for_warp:
        return img.copy()

    polys = np.stack(extended, axis=0)               # (N, W) float64
    targets_arr = np.array(targets, dtype=np.float64)
    # Ordina per y media (di solito gia' ordinato)
    order = np.argsort(targets_arr)
    polys = polys[order]
    targets_arr = targets_arr[order]
    n = polys.shape[0]

    # Forza monotonia colonna per colonna
    polys = np.maximum.accumulate(polys, axis=0)

    y_dst = np.arange(h, dtype=np.float64)
    map_x = np.tile(np.arange(w, dtype=np.float32), (h, 1))

    # Per ogni y_dst: trova k tale che targets[k] <= y_dst < targets[k+1]
    idx = np.searchsorted(targets_arr, y_dst, side="right")
    k = np.clip(idx - 1, 0, n - 2)
    src_top = polys[k]
    src_bot = polys[k + 1]
    t_top = targets_arr[k]
    t_bot = targets_arr[k + 1]
    denom = np.maximum(t_bot - t_top, 1e-6)
    t = ((y_dst - t_top) / denom)[:, None]
    inside = src_top + t * (src_bot - src_top)

    # Sopra il primo target / sotto l'ultimo: estensione COSTANTE, non slope.
    # Cosi' anche se le baseline iniziali/finali non coprono tutta la pagina,
    # i margini non esplodono.
    below_mask = (idx == 0)[:, None]
    above_mask = (idx == n)[:, None]
    # Per "below" usiamo polys[0] + (y - targets[0]) cosi' il warp sopra la prima
    # riga e' "trasportato rigidamente": pattern del primo target traslato.
    y_below = polys[0][None, :] + (y_dst[:, None] - targets_arr[0])
    y_above = polys[-1][None, :] + (y_dst[:, None] - targets_arr[-1])

    map_y = np.where(below_mask, y_below, np.where(above_mask, y_above, inside))

    # Clamp displacement (rete di sicurezza)
    identity = np.tile(np.arange(h, dtype=np.float64).reshape(-1, 1), (1, w))
    max_disp = params.max_displacement_frac * h
    delta = np.clip(map_y - identity, -max_disp, max_disp)
    map_y = (identity + delta).astype(np.float32)
    map_x = map_x.astype(np.float32)

    bv = (255,) * (1 if img.ndim == 2 else img.shape[2])
    out = cv2.remap(
        img, map_x, map_y,
        interpolation=cv2.INTER_CUBIC,
        borderMode=cv2.BORDER_CONSTANT, borderValue=bv,
    )
    return out


def dewarp_page_polyline(img: np.ndarray, params: Optional[DewarpParams] = None) -> np.ndarray:
    """Variante con polyline (baseline samplate + smoothing) invece di polynomial fit."""
    p = params or DewarpParams()

    # Pad bianco
    pad = p.pad_white
    if pad > 0:
        bv = (255,) * (1 if img.ndim == 2 else img.shape[2])
        img_p = cv2.copyMakeBorder(img, pad, pad, pad, pad, cv2.BORDER_CONSTANT, value=bv)
    else:
        img_p = img

    # Deskew
    img_d, _ = deskew(img_p, p)
    gray_d = _to_gray(img_d)
    h, w = gray_d.shape

    binary = _binarize(gray_d)
    text_mask = _detect_text_mask(gray_d, p)
    baselines = _extract_baselines(text_mask, binary, p)

    if len(baselines) < p.min_features_for_warp:
        return img_d

    return _dewarp_with_polylines(img_d, baselines, h, w, p)


# ----------------------------- dewarp principale ----------------------------- #

def dewarp_page(img: np.ndarray, params: Optional[DewarpParams] = None) -> np.ndarray:
    """Raddrizza una pagina singola. Input/output in BGR o gray uint8.

    Dispatch sul motore selezionato da `params.engine`:
      - "polyline": baseline samplate + smoothing, no estrapolazione polinomiale
      - "polynomial" (default): fit polinomiale di grado 2 sulla midline
    """
    p = params or DewarpParams()
    if p.engine == "polyline":
        return dewarp_page_polyline(img, p)
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
    text_lines = _trace_lines(text_mask, p)
    n_text = len(text_lines)

    # Strategia:
    # - molte righe di testo (>= min_text_lines_full_confidence): usiamo solo loro,
    #   le support features introdurrebbero rumore e duplicati;
    # - poche righe ma >= min_features_for_warp: integriamo con Hough e bordi figure,
    #   deduplicando quelli che cadono vicino a righe esistenti.
    if n_text >= p.min_text_lines_full_confidence:
        features = list(text_lines)
    else:
        support_hough = _hough_horizontal_features(gray_d, p)
        support_figs = _figure_horizontal_edges(gray_d, p)
        features = list(text_lines)
        existing_means = [float(np.nanmean(f)) for f in features if not np.isnan(np.nanmean(f))]
        dedup_tol = max(p.min_line_height_px * 1.5, 12)
        for cand in support_hough + support_figs:
            cm = np.nanmean(cand)
            if np.isnan(cm):
                continue
            if any(abs(cm - em) < dedup_tol for em in existing_means):
                continue
            features.append(cand)
            existing_means.append(float(cm))

    n_feats = len(features)

    if n_feats < p.min_features_for_warp:
        # Davvero nulla da raddrizzare: deskew e basta
        return img_d

    # Degree adattivo: parte da 1 e cresce solo se abbiamo abbastanza features
    if n_feats < 5:
        adaptive_degree = 1
    elif n_feats < 9:
        adaptive_degree = min(2, p.poly_degree)
    else:
        adaptive_degree = p.poly_degree

    # Confidence basata sulle righe di testo (le piu' affidabili). Pagine con
    # solo features di supporto ricevono displacement molto piu' conservativo.
    text_confidence = min(1.0, n_text / max(p.min_text_lines_full_confidence, 1))
    feat_confidence = min(1.0, n_feats / 12.0)
    confidence = 0.35 + 0.65 * (0.6 * text_confidence + 0.4 * feat_confidence)
    adaptive_max_disp_frac = p.max_displacement_frac * confidence

    # 2. Fit polinomiale di ogni feature
    polys = np.stack([_fit_polynomial(ys, adaptive_degree, w) for ys in features], axis=0)
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

    # Costruzione vettorizzata del campo di displacement.
    # `targets` (N,) e' uguale per ogni colonna x: precomputiamo gli indici
    # piecewise una sola volta, e poi facciamo le interpolazioni come operazioni
    # su array (H, W). Niente loop Python su x.
    map_x = np.tile(np.arange(w, dtype=np.float32), (h, 1))
    y_dst = np.arange(h, dtype=np.float64)

    # Forza monotonia colonna per colonna (vettorizzato)
    polys_mono = np.maximum.accumulate(polys_s, axis=0).astype(np.float64)  # (N, W)
    targets64 = targets.astype(np.float64)

    if N >= 2:
        dtop = targets64[1] - targets64[0]
        if dtop == 0:
            dtop = 1.0
        slope_top = (polys_mono[1] - polys_mono[0]) / dtop                 # (W,)
        anchor_top = polys_mono[0] - targets64[0] * slope_top              # (W,)
        dbot = targets64[-1] - targets64[-2]
        if dbot == 0:
            dbot = 1.0
        slope_bot = (polys_mono[-1] - polys_mono[-2]) / dbot               # (W,)
        anchor_bot_src = polys_mono[-1] + (h - 1 - targets64[-1]) * slope_bot
    else:
        slope_top = np.zeros(w, dtype=np.float64)
        slope_bot = np.zeros(w, dtype=np.float64)
        anchor_top = polys_mono[0].copy()
        anchor_bot_src = polys_mono[-1].copy()

    # Per ogni y_dst, l'indice del segmento di interpolazione fra le righe
    idx = np.searchsorted(targets64, y_dst, side="right")  # (H,), valori in [0, N]
    k = np.clip(idx - 1, 0, N - 2)                          # (H,)

    # Caso "dentro" l'intervallo [targets[0], targets[-1]]
    src_top = polys_mono[k]            # (H, W)
    src_bot = polys_mono[k + 1]        # (H, W)
    t_top = targets64[k]               # (H,)
    t_bot = targets64[k + 1]           # (H,)
    denom = np.maximum(t_bot - t_top, 1e-6)
    t = ((y_dst - t_top) / denom)[:, None]  # (H, 1)
    inside = src_top + t * (src_bot - src_top)  # (H, W)

    # Sotto il primo target: estrapolazione lineare con anchor_top + y * slope_top
    y_below = anchor_top[None, :] + y_dst[:, None] * slope_top[None, :]   # (H, W)
    # Sopra l'ultimo target: anchor_bot_src + (y - targets[-1]) * slope_bot
    y_above = polys_mono[-1][None, :] + (y_dst[:, None] - targets64[-1]) * slope_bot[None, :]

    below_mask = (idx == 0)[:, None]
    above_mask = (idx == N)[:, None]
    map_y = np.where(below_mask, y_below, np.where(above_mask, y_above, inside)).astype(np.float32)

    # 5. Attenuazione su aree figura: blend tra map_y (warp) e y identita'
    weight = _find_figure_mask(gray_d, p)  # shape (h, w), float32
    identity = np.tile(np.arange(h, dtype=np.float32).reshape(-1, 1), (1, w))
    map_y = weight * map_y + (1.0 - weight) * identity

    # 6. Clamp del displacement totale per evitare distorsioni catastrofiche.
    max_disp = adaptive_max_disp_frac * h
    delta = np.clip(map_y - identity, -max_disp, max_disp)
    map_y = (identity + delta).astype(np.float32)

    # 7. Remap (richiede entrambe le mappe in CV_32F)
    bv = (255,) * (1 if img_d.ndim == 2 else img_d.shape[2])
    out = cv2.remap(
        img_d, map_x, map_y,
        interpolation=cv2.INTER_CUBIC,
        borderMode=cv2.BORDER_CONSTANT, borderValue=bv,
    )
    return out
