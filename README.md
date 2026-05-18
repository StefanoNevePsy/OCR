# Dewarp

Tool per raddrizzare scansioni di libri (skew + warp da piega centrale) in PDF, mantenendo struttura del testo, ordine delle immagini e leggibilità. Pensato per studenti e clinici che evidenziano su tablet (es. Samsung Notes su Galaxy Tab) e hanno bisogno di righe orizzontali.

## Cosa fa

- Spezza automaticamente le scansioni a doppia pagina sul gutter (rilevamento valle scura centrale).
- Applica un deskew globale per ciascuna metà.
- Rileva le righe di testo, ne stima la curvatura individuale, costruisce un campo di displacement verticale e raddrizza ogni riga, anche se hanno angoli/curvature diversi tra loro.
- Rileva le aree "figura" (es. genogrammi, schemi) e applica solo una correzione minima per non distorcerle.
- Riassembla un PDF non distruttivo. OCR italiano opzionale via Tesseract per output ricercabile.

## Installazione (PC/Mac)

```bash
# dipendenze di sistema
sudo apt-get install poppler-utils tesseract-ocr tesseract-ocr-ita   # Linux
# brew install poppler tesseract tesseract-lang                       # macOS

git clone <repo>
cd OCR
pip install -e .
```

Python 3.10 o superiore.

## Uso da riga di comando

```bash
dewarp input.pdf -o output.pdf
dewarp input.pdf -o output.pdf --ocr --lang ita
dewarp input.pdf --no-split                         # se non ci sono pagine doppie
dewarp input.pdf --figure-attenuation 0 --poly-degree 3
```

## GUI web

```bash
dewarp --serve            # apre http://127.0.0.1:8765
dewarp --serve --host 0.0.0.0 --port 8765   # accessibile dal tablet sulla stessa rete
```

Dal tablet apri l'indirizzo del PC nel browser, trascini il PDF, regoli le opzioni, scarichi il risultato. Le anteprime "prima/dopo/affianco" ti permettono di valutare la qualità pagina per pagina.

## App desktop self-contained (Mac, Windows, Linux)

Il progetto include un wrapper Tauri che impacchetta tutto in un'app nativa: nessuna installazione di Python sull'utente finale.

```bash
# Build completo (PyInstaller sidecar + Tauri bundle):
./scripts/build_desktop.sh
```

Output in `desktop/src-tauri/target/release/bundle/`:
- Linux: `.AppImage`, `.deb`
- macOS: `.dmg`, `.app`
- Windows: `.msi`, `.exe`

Architettura:
- `desktop/src-tauri/`: wrapper Tauri 2 (Rust + WebView nativa OS)
- `packaging/dewarp_server.spec`: PyInstaller spec, produce `dewarp-server` (binario standalone con Python+OpenCV+Tesseract embedded)
- All'apertura, il wrapper avvia il sidecar su una porta libera locale, attende che risponda, carica la SPA nella WebView e ferma il sidecar alla chiusura.

Per build cross-platform: ogni piattaforma va buildata sulla rispettiva. CI matrix (GitHub Actions) raccomandata.

## App Android nativa (Galaxy Tab S11 e altri)

In `android/`: app Kotlin con Jetpack Compose, OpenCV Android e PdfBox-Android. L'engine è una traduzione 1:1 della versione Python, in `it.dewarp.engine.Engine`.

```bash
cd android
./gradlew :app:assembleRelease   # APK unsigned in app/build/outputs/apk/release/
./gradlew :app:installDebug      # build + push su device connesso (debug)
```

Funzionalità:
- Apri PDF via SAF (Files / Drive / Samsung Notes share-to)
- Slider per `figureAttenuation` e `maxDisplacementFrac`
- Toggle split 2-up
- Salva il PDF risultante via SAF (qualunque cartella scelta)
- Opzione GPU (sperimentale, placeholder per UMat/OpenCL futuro)

Min SDK 26 (Android 8). Build su `compileSdk = 35`, AGP 8.7, Kotlin 2.0, Compose BoM 2024.12, OpenCV 4.10 da Maven Central.

## CI

`.github/workflows/build.yml` builda su ogni push:
- **python-tests** (Ubuntu): pytest sull'engine Python
- **desktop** (Linux/Mac Intel/Mac ARM/Windows): PyInstaller sidecar + Tauri bundle, artifact `.AppImage` / `.deb` / `.dmg` / `.msi`
- **android** (Ubuntu): debug e release APK, artifact in `dewarp-android-apk`

Niente upload esterni, niente cose offerte: tutto resta dentro l'organizzazione.

## Parametri principali

| flag CLI / GUI | default | quando toccarlo |
|---|---|---|
| `--figure-attenuation` | 0.15 | 0 se ci sono molti genogrammi/schemi e non vuoi distorsioni; 1 se la pagina è solo testo curvo |
| `--poly-degree` | 2 | 3-4 se la curvatura è estrema; 1 se la pagina è solo skew |
| `--no-split` | off | scansioni a singola pagina |
| `--ocr` | off | per ottenere PDF ricercabili |

## Architettura

```
src/dewarp/
├── engine.py            algoritmi: split 2-up, deskew, line tracing, fit, remap
├── pdf_io.py            rendering PDF -> ndarray, scrittura PDF da immagini
├── pipeline.py          orchestrazione completa, OCR overlay
├── cli.py               entry point dewarp
├── _sidecar_entry.py    entry point del sidecar per PyInstaller
└── webapp/
    ├── server.py        FastAPI: jobs, anteprime, download
    └── static/          SPA (vanilla JS, no build)

desktop/                 wrapper Tauri 2 (Rust + WebView nativa)
├── src-tauri/
│   ├── Cargo.toml
│   ├── tauri.conf.json
│   ├── capabilities/
│   ├── src/lib.rs       avvia sidecar, attende, redirige webview
│   └── binaries/        sidecar PyInstaller (out di scripts/build_sidecar.sh)
└── frontend/index.html  splash mentre il sidecar parte

packaging/dewarp_server.spec  PyInstaller spec del sidecar
scripts/build_sidecar.sh      build solo del sidecar
scripts/build_desktop.sh      build end-to-end (sidecar + Tauri bundle)
```

L'engine adatta `poly_degree` e `max_displacement_frac` al numero di features (righe di testo + linee Hough orizzontali + bordi figure) rilevate sulla pagina, evitando distorsioni catastrofiche su pagine sparse.

## Limiti noti

- Scansioni con molte immagini grandi e poco testo: il rilevamento delle righe non ha abbastanza punti, fa solo deskew (atteso).
- Pagine con due colonne molto strette: il fit polinomiale può oscillare; provare a ridurre `poly_degree` a 1.
- Bordi: il margine destro può mostrare lettere tagliate, è quasi sempre già così nello scan originale.

## Test

```bash
PYTHONPATH=src pytest tests/
```
