# Reader's Scanner

Scans documents with the camera, one page or many, and reads their text on the phone. Each
document becomes a searchable PDF, filed in big plain folders, and copied to your own WebDAV
folder (kDrive, Nextcloud…) if you want. No account, no cloud OCR, black and white.

## Key points

- **Capture**: the page is outlined live; touch anywhere or press a volume key. Take as many
  pages as you like, then review: crop (corners with a magnifier), turn, look (clean, grey,
  b & w, photo), reorder, retake. Photos can be imported too.
- **Straightening**: the sheet is found by the app itself (edges, outlines and straight lines),
  flattened in perspective with its true proportions, shadows evened out.
- **Text**: read on the phone by Tesseract 5 in the language chosen on top of the capture
  screen (English, French, German, Italian, Spanish, Portuguese, Russian), after the document
  is filed. The PDF carries the text invisibly over the page: search it, copy it.
- **Names**: every file starts with the date and hour of capture; then the name you type, or
  else the first words read on the page (`2026-09-24 11h32 Facture d'électricité.pdf`).
- **Sharing**: one document or several (long press to select): as PDF, as page images (JPEG)
  or as text. Also "open with…" and "save a copy".
- **Folders**: "all scans" holds everything; make your own with +. Search looks through names
  and the text of every page.
- **WebDAV**: a copy of each PDF goes to your server, in a subfolder named after its folder.
  Renames, moves and deletions follow. Credentials import from a Reader's credentials file,
  including one exported by Reader's Notes, Recorder, Tasks or Calendar.
- Optional "best" models per language, downloaded from the settings, for more accurate reading.
- Six languages for the app (en, fr, de, es, pt, ru). No analytics, backup off.

More detail: [docs/NOTES.md](docs/NOTES.md).

## Build

    export JAVA_HOME=/path/to/jdk-21
    ./gradlew assemblePubliqueDebug
    ./gradlew testDebugUnitTest

Strings for the six languages are generated from the table in `tools/strings.py`.

MIT licence. Tesseract, Leptonica and the tessdata_fast models are Apache 2.0.
