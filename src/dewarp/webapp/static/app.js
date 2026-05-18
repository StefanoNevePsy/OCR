// Dewarp client: upload + polling + previews. Niente framework, niente build.

const form = document.getElementById("upload-form");
const fileInput = document.getElementById("file-input");
const dropZone = document.getElementById("drop-zone");
const dzTitle = document.getElementById("drop-zone-title");
const dzHint = document.getElementById("drop-zone-hint");
const submitBtn = document.getElementById("submit");

const stageUpload = document.getElementById("stage-upload");
const stageProgress = document.getElementById("stage-progress");
const stageDone = document.getElementById("stage-done");

const progressFill = document.getElementById("progress-fill");
const progressMsg = document.getElementById("progress-msg");
const progressMeta = document.getElementById("progress-meta");
const jobIdEl = document.getElementById("job-id");
const doneStats = document.getElementById("done-stats");
const downloadLink = document.getElementById("download-link");
const newJobBtn = document.getElementById("new-job");
const previewsEl = document.getElementById("previews");
const viewToggle = document.querySelector(".toggle");

let currentView = "after";
let currentJobId = null;
let pollHandle = null;

// ---------- formattazione ----------
function fmtPct(v) { return Math.round(v * 100) + "%"; }
function fmtBytes(n) {
  if (n < 1024) return n + " B";
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + " KB";
  return (n / (1024 * 1024)).toFixed(1) + " MB";
}

// ---------- drag & drop ----------
["dragenter", "dragover"].forEach(ev => dropZone.addEventListener(ev, e => {
  e.preventDefault(); e.stopPropagation();
  dropZone.classList.add("drag");
}));
["dragleave", "drop"].forEach(ev => dropZone.addEventListener(ev, e => {
  e.preventDefault(); e.stopPropagation();
  dropZone.classList.remove("drag");
}));
dropZone.addEventListener("drop", e => {
  const f = e.dataTransfer?.files?.[0];
  if (f && f.type === "application/pdf") {
    fileInput.files = e.dataTransfer.files;
    onFileChange();
  }
});

fileInput.addEventListener("change", onFileChange);

function onFileChange() {
  const f = fileInput.files?.[0];
  if (!f) {
    submitBtn.disabled = true;
    dzTitle.textContent = "Trascina un PDF qui";
    dzHint.textContent = "oppure clicca per sceglierlo";
    dropZone.classList.remove("has-file");
    return;
  }
  submitBtn.disabled = false;
  dropZone.classList.add("has-file");
  dzTitle.textContent = f.name;
  dzHint.textContent = fmtBytes(f.size);
}

// ---------- reset opzioni ----------
// Snapshot dei default presenti nel markup, per ripristinare con un click
// (utili dopo aver provato valori estremi che producono risultati strani).
const defaultsByName = {};
for (const el of form.elements) {
  if (!el.name) continue;
  if (el.type === "checkbox") defaultsByName[el.name] = el.checked;
  else defaultsByName[el.name] = el.value;
}
const resetBtn = document.getElementById("reset-options");
resetBtn.addEventListener("click", (e) => {
  e.stopPropagation();  // non far chiudere il <details>
  e.preventDefault();
  for (const el of form.elements) {
    if (!(el.name in defaultsByName)) continue;
    if (el.type === "checkbox") el.checked = defaultsByName[el.name];
    else el.value = defaultsByName[el.name];
    el.dispatchEvent(new Event("input", { bubbles: true }));
  }
});

// ---------- range outputs live ----------
document.querySelectorAll(".range input[type=range]").forEach(r => {
  const out = document.querySelector(`[data-out="${r.name}"]`);
  const update = () => {
    if (!out) return;
    const v = parseFloat(r.value);
    if (r.name === "figure_attenuation" || r.name === "max_displacement_frac") {
      out.textContent = Math.round(v * 100) + "%";
    } else {
      out.textContent = v;
    }
  };
  update();
  r.addEventListener("input", update);
});

// ---------- submit ----------
form.addEventListener("submit", async e => {
  e.preventDefault();
  if (!fileInput.files?.[0]) return;
  submitBtn.disabled = true;
  submitBtn.querySelector("span").textContent = "Caricamento...";

  const fd = new FormData();
  fd.append("file", fileInput.files[0]);
  for (const el of form.elements) {
    if (!el.name || el.type === "file" || el.type === "submit") continue;
    if (el.type === "checkbox") fd.append(el.name, el.checked ? "true" : "false");
    else fd.append(el.name, el.value);
  }

  try {
    const r = await fetch("/api/jobs", { method: "POST", body: fd });
    if (!r.ok) throw new Error(await r.text());
    const { job_id } = await r.json();
    currentJobId = job_id;
    jobIdEl.textContent = job_id;
    stageUpload.hidden = true;
    stageProgress.hidden = false;
    startPolling(job_id);
  } catch (err) {
    alert("Errore: " + err.message);
    submitBtn.disabled = false;
    submitBtn.querySelector("span").textContent = "Raddrizza il PDF";
  }
});

// ---------- polling ----------
function startPolling(jobId) {
  let lastMsg = "";
  pollHandle = setInterval(async () => {
    try {
      const r = await fetch(`/api/jobs/${jobId}`);
      if (!r.ok) throw new Error("status " + r.status);
      const j = await r.json();
      progressFill.style.width = fmtPct(j.progress);
      progressFill.parentElement.setAttribute("aria-valuenow", Math.round(j.progress * 100));
      if (j.message && j.message !== lastMsg) {
        progressMsg.textContent = j.message;
        lastMsg = j.message;
      }
      progressMeta.textContent = (j.pages_src ? `${j.pages_src} pagine sorgente` : "") + (j.pages_out ? ` · ${j.pages_out} pagine output` : "");
      if (j.state === "done") {
        clearInterval(pollHandle);
        showDone(jobId, j);
      } else if (j.state === "error") {
        clearInterval(pollHandle);
        progressMsg.textContent = "Errore: " + (j.error || "sconosciuto");
        progressFill.style.background = "var(--accent-strong)";
      }
    } catch (err) {
      console.error(err);
    }
  }, 700);
}

// ---------- done ----------
function showDone(jobId, status) {
  stageProgress.hidden = true;
  stageDone.hidden = false;
  doneStats.textContent = `${status.pages_src} pagine sorgente · ${status.pages_out} pagine raddrizzate`;
  // Conserva URL ma intercetta il click: alcune webview (Tauri / WKWebView)
  // ignorano l'attributo `download` su link verso server locali. Forziamo
  // il download via fetch + Blob + anchor temporaneo, che funziona ovunque.
  downloadLink.dataset.url = `/api/jobs/${jobId}/download`;
  downloadLink.href = `/api/jobs/${jobId}/download`;
  renderPreviews(jobId, status.pages_out);
}

async function triggerDownload(url, filename) {
  try {
    downloadLink.setAttribute("aria-busy", "true");
    const r = await fetch(url);
    if (!r.ok) throw new Error("download fallito: " + r.status);
    const blob = await r.blob();
    const objectUrl = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = objectUrl;
    a.download = filename || "dewarped.pdf";
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(objectUrl), 60_000);
  } catch (err) {
    alert("Impossibile scaricare il PDF: " + err.message);
  } finally {
    downloadLink.removeAttribute("aria-busy");
  }
}

downloadLink.addEventListener("click", (e) => {
  const url = downloadLink.dataset.url;
  if (!url) return; // bottone non ancora pronto
  e.preventDefault();
  triggerDownload(url, "dewarped.pdf");
});

function renderPreviews(jobId, n) {
  previewsEl.innerHTML = "";
  for (let i = 0; i < n; i++) {
    const fig = document.createElement("div");
    fig.className = "preview " + (currentView === "split" ? "split" : "");
    const num = document.createElement("span");
    num.className = "num";
    num.textContent = String(i + 1).padStart(2, "0");
    fig.appendChild(num);

    if (currentView === "split") {
      const row = document.createElement("div");
      row.className = "split-row";
      row.appendChild(makeFig(jobId, i, "before", "prima"));
      row.appendChild(makeFig(jobId, i, "after", "dopo"));
      fig.appendChild(row);
    } else {
      const img = document.createElement("img");
      img.loading = "lazy";
      img.src = `/api/jobs/${jobId}/page/${i}/${currentView}.jpg`;
      img.alt = `Pagina ${i + 1} ${currentView}`;
      fig.appendChild(img);
    }
    previewsEl.appendChild(fig);
  }
}

function makeFig(jobId, i, side, label) {
  const f = document.createElement("figure");
  const img = document.createElement("img");
  img.loading = "lazy";
  img.src = `/api/jobs/${jobId}/page/${i}/${side}.jpg`;
  img.alt = `Pagina ${i + 1} ${label}`;
  const cap = document.createElement("figcaption");
  cap.textContent = label;
  f.append(img, cap);
  return f;
}

viewToggle.addEventListener("click", e => {
  const btn = e.target.closest("button[data-view]");
  if (!btn) return;
  viewToggle.querySelectorAll("button").forEach(b => b.setAttribute("aria-selected", "false"));
  btn.setAttribute("aria-selected", "true");
  currentView = btn.dataset.view;
  if (currentJobId != null) {
    const n = previewsEl.children.length;
    renderPreviews(currentJobId, n);
  }
});

newJobBtn.addEventListener("click", async () => {
  if (currentJobId) {
    fetch(`/api/jobs/${currentJobId}`, { method: "DELETE" }).catch(() => {});
    currentJobId = null;
  }
  stageDone.hidden = true;
  stageUpload.hidden = false;
  fileInput.value = "";
  onFileChange();
  progressFill.style.width = "0";
  progressFill.style.background = "";
  submitBtn.querySelector("span").textContent = "Raddrizza il PDF";
});
