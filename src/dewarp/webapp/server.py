"""FastAPI server per la GUI web di dewarp.

Espone:
  GET  /                          -> SPA statica
  POST /api/jobs                  -> upload PDF, ritorna job_id
  GET  /api/jobs/{id}             -> stato + statistiche
  GET  /api/jobs/{id}/page/{n}/before.jpg
  GET  /api/jobs/{id}/page/{n}/after.jpg
  GET  /api/jobs/{id}/download    -> PDF raddrizzato

Tutto e' locale: i file vivono in una cartella temporanea per la durata del processo.
"""
from __future__ import annotations

import asyncio
import logging
import shutil
import tempfile
import threading
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

import cv2
from fastapi import BackgroundTasks, FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles

from ..engine import DewarpParams, split_two_up
from ..pdf_io import render_pdf_pages
from ..pipeline import ProcessOptions, process_pdf

log = logging.getLogger(__name__)


@dataclass
class Job:
    id: str
    workdir: Path
    input_path: Path
    output_path: Path
    n_pages_src: int = 0
    n_pages_out: int = 0
    progress: float = 0.0
    message: str = "in coda"
    state: str = "queued"  # queued | running | done | error
    error: Optional[str] = None
    options: dict = field(default_factory=dict)


_jobs: dict[str, Job] = {}
_jobs_lock = threading.Lock()
_root_tmp = Path(tempfile.gettempdir()) / "dewarp_jobs"
_root_tmp.mkdir(exist_ok=True)


def _get_job(job_id: str) -> Job:
    with _jobs_lock:
        j = _jobs.get(job_id)
    if j is None:
        raise HTTPException(404, "job non trovato")
    return j


def _run_job(job: Job):
    try:
        with _jobs_lock:
            job.state = "running"
            job.message = "rendering PDF"

        engine_choice = str(job.options.get("engine", "polynomial"))
        if engine_choice not in ("polynomial", "polyline"):
            engine_choice = "polynomial"
        params = DewarpParams(
            engine=engine_choice,
            figure_attenuation=float(job.options.get("figure_attenuation", 0.15)),
            max_displacement_frac=float(job.options.get("max_displacement_frac", 0.04)),
            poly_degree=int(job.options.get("poly_degree", 2)),
            min_features_for_warp=int(job.options.get("min_features_for_warp", 3)),
            polyline_smooth_px=max(3, int(job.options.get("polyline_smooth_px", 81)) | 1),
        )
        opts = ProcessOptions(
            dpi=int(job.options.get("dpi", 300)),
            ocr=bool(job.options.get("ocr", False)),
            ocr_lang=str(job.options.get("ocr_lang", "ita")),
            split_two_up=bool(job.options.get("split_two_up", True)),
            jpeg_quality=int(job.options.get("jpeg_quality", 88)),
            params=params,
        )

        def progress(i: int, total: int, msg: str):
            with _jobs_lock:
                job.n_pages_src = total
                job.progress = (i / total) if total else 0.0
                job.message = msg

        stats = process_pdf(job.input_path, job.output_path, options=opts, progress=progress)

        # Genera anteprime (before/after) a bassa risoluzione per la UI
        previews_before = job.workdir / "previews" / "before"
        previews_after = job.workdir / "previews" / "after"
        previews_before.mkdir(parents=True, exist_ok=True)
        previews_after.mkdir(parents=True, exist_ok=True)
        # "Before" deve seguire lo stesso indice di "after" (post-split) per avere un
        # confronto pagina-a-pagina sensato.
        idx = 0
        for src_img in render_pdf_pages(job.input_path, dpi=90):
            if opts.split_two_up:
                halves = split_two_up(src_img, params)
            else:
                halves = [src_img]
            for half in halves:
                cv2.imwrite(str(previews_before / f"{idx:04d}.jpg"), half, [cv2.IMWRITE_JPEG_QUALITY, 80])
                idx += 1
        for i, img in enumerate(render_pdf_pages(job.output_path, dpi=90)):
            cv2.imwrite(str(previews_after / f"{i:04d}.jpg"), img, [cv2.IMWRITE_JPEG_QUALITY, 80])

        with _jobs_lock:
            job.n_pages_out = stats["output_pages"]
            job.progress = 1.0
            job.state = "done"
            job.message = "completato"
    except Exception as e:  # noqa: BLE001
        log.exception("job %s fallito", job.id)
        with _jobs_lock:
            job.state = "error"
            job.error = str(e)
            job.message = f"errore: {e}"


def create_app() -> FastAPI:
    app = FastAPI(title="Dewarp", docs_url=None, redoc_url=None)

    static_dir = Path(__file__).parent / "static"

    @app.post("/api/jobs")
    async def create_job(
        file: UploadFile = File(...),
        engine: str = Form("polynomial"),
        figure_attenuation: float = Form(0.15),
        max_displacement_frac: float = Form(0.04),
        poly_degree: int = Form(2),
        polyline_smooth_px: int = Form(81),
        dpi: int = Form(300),
        ocr: bool = Form(False),
        ocr_lang: str = Form("ita"),
        split_two_up: bool = Form(True),
    ):
        if not file.filename or not file.filename.lower().endswith(".pdf"):
            raise HTTPException(400, "carica un file .pdf")
        job_id = uuid.uuid4().hex[:12]
        workdir = _root_tmp / job_id
        workdir.mkdir(parents=True, exist_ok=True)
        input_path = workdir / "input.pdf"
        output_path = workdir / "output.pdf"
        with open(input_path, "wb") as f:
            shutil.copyfileobj(file.file, f)
        job = Job(
            id=job_id,
            workdir=workdir,
            input_path=input_path,
            output_path=output_path,
            options={
                "engine": engine,
                "figure_attenuation": figure_attenuation,
                "max_displacement_frac": max_displacement_frac,
                "poly_degree": poly_degree,
                "polyline_smooth_px": polyline_smooth_px,
                "dpi": dpi,
                "ocr": ocr,
                "ocr_lang": ocr_lang,
                "split_two_up": split_two_up,
            },
        )
        with _jobs_lock:
            _jobs[job_id] = job
        # Avvia in thread (process_pdf e' bloccante e CPU bound)
        threading.Thread(target=_run_job, args=(job,), daemon=True).start()
        return {"job_id": job_id}

    @app.get("/api/jobs/{job_id}")
    async def job_status(job_id: str):
        job = _get_job(job_id)
        with _jobs_lock:
            return {
                "id": job.id,
                "state": job.state,
                "progress": job.progress,
                "message": job.message,
                "error": job.error,
                "pages_src": job.n_pages_src,
                "pages_out": job.n_pages_out,
            }

    @app.get("/api/jobs/{job_id}/page/{n}/before.jpg")
    async def before(job_id: str, n: int):
        job = _get_job(job_id)
        path = job.workdir / "previews" / "before" / f"{n:04d}.jpg"
        if not path.exists():
            raise HTTPException(404)
        return FileResponse(path, media_type="image/jpeg")

    @app.get("/api/jobs/{job_id}/page/{n}/after.jpg")
    async def after(job_id: str, n: int):
        job = _get_job(job_id)
        path = job.workdir / "previews" / "after" / f"{n:04d}.jpg"
        if not path.exists():
            raise HTTPException(404)
        return FileResponse(path, media_type="image/jpeg")

    @app.get("/api/jobs/{job_id}/download")
    async def download(job_id: str):
        job = _get_job(job_id)
        if job.state != "done":
            raise HTTPException(409, "job non ancora completato")
        return FileResponse(
            job.output_path,
            media_type="application/pdf",
            filename="dewarped.pdf",
        )

    @app.delete("/api/jobs/{job_id}")
    async def delete_job(job_id: str):
        job = _get_job(job_id)
        with _jobs_lock:
            _jobs.pop(job_id, None)
        shutil.rmtree(job.workdir, ignore_errors=True)
        return {"ok": True}

    # Static SPA (mounted ultimo per non oscurare /api/*)
    app.mount("/", StaticFiles(directory=str(static_dir), html=True), name="static")
    return app


def run_server(host: str = "127.0.0.1", port: int = 8765):
    import uvicorn
    app = create_app()
    print(f"Apri http://{host}:{port} nel browser")
    uvicorn.run(app, host=host, port=port, log_level="info")
