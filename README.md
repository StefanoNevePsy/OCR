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

## Uso sul Galaxy Tab S11 (Android)

Tre opzioni in ordine di praticità:

1. **Server sul PC, browser sul tablet** (raccomandato). Lancia `dewarp --serve --host 0.0.0.0` sul PC, apri l'IP locale dal tablet. Velocità piena, nessun setup sul tablet.
2. **Termux nativo**: `pkg install python tesseract poppler libjpeg-turbo` poi `pip install opencv-python-headless pymupdf img2pdf fastapi uvicorn[standard] python-multipart pytesseract`, infine clona il repo e usa il CLI o `--serve` su `localhost`. È lento (alcuni minuti per libro) ma funziona offline.
3. **App Android nativa**: progetto Kotlin separato, non incluso qui.

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
├── engine.py        algoritmi: split 2-up, deskew, line tracing, fit, remap
├── pdf_io.py        rendering PDF -> ndarray, scrittura PDF da immagini
├── pipeline.py      orchestrazione completa, OCR overlay
├── cli.py           entry point dewarp
└── webapp/
    ├── server.py    FastAPI: jobs, anteprime, download
    └── static/      SPA (vanilla JS, no build)
```

## Limiti noti

- Scansioni con molte immagini grandi e poco testo: il rilevamento delle righe non ha abbastanza punti, fa solo deskew (atteso).
- Pagine con due colonne molto strette: il fit polinomiale può oscillare; provare a ridurre `poly_degree` a 1.
- Bordi: il margine destro può mostrare lettere tagliate, è quasi sempre già così nello scan originale.

## Test

```bash
PYTHONPATH=src pytest tests/
```
