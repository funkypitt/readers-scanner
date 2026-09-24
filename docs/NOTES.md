# Reader's Scanner — notes

## Layout

- `ScanActivity` + `ui/ScanUi.kt`: camera (CameraX preview + capture + analysis, all 4:3 so the
  live outline matches the photo), review pager, crop editor, filing sheet.
- `scan/Session.kt`: pages of one capture in `files/session/`, processed one at a time off
  the main thread: EXIF-upright source (≤ 3200 px) → detection → straightened page (≤ 3000 px).
- `scan/Detector.kt` (pure Kotlin, unit-tested): 5×5 blur, Sobel, Canny with thresholds from
  the picture, then two kinds of proposals — convex hull of each large edge component reduced
  to its biggest inscribed quadrilateral, and pairs of pairs of Hough lines — scored by how
  much of each side lies on edges (≥ 45 % per side, ≥ 65 % on average), times area. ~14 ms on
  320×240 on the JVM.
- `scan/Clean.kt` (pure, unit-tested): true aspect ratio of the sheet from the four corners
  (Zhang & He 2007; when two sides are parallel in the photo the focal length cannot be
  recovered, so 0.8 × the long side is assumed); background estimate (block max → widen →
  smooth → bilinear, floored at half the paper level so photos are not blown out) used to
  even out light for "clean", "grey", "b & w" (local-mean threshold on the flattened page).
- `data/Store.kt`: `files/scans/folders.json`, `files/scans/docs/<id>/doc.json` + `<page>.src.jpg`
  (photo, kept for re-cropping) + `<page>.jpg` + `text.txt` (pages split by form feed) + `doc.pdf`.
  `rev` bumps with every page change so an OCR run on older pages is thrown away.
- `data/Ocr.kt`: one document at a time, Tesseract4Android 4.8.0 (openmp), models copied from
  `assets/tessdata` to `files/ocr/tessdata` on first use. `TessPdfRenderer` writes the searchable
  PDF with the page JPEG untouched inside (`addPageToDocument(pix, jpegPath)`); `user_defined_dpi`
  = long side / 11.69 so every page is A4-sized. Pending documents resume at the next start.
- `data/Pdf.kt`: until the text is read (or if reading failed), a plain PDF of the page JPEGs
  (DCTDecode passthrough). Share / open / save always hand out the PDF.
- `sync/Sync.kt`: one-way mirror phone → WebDAV, state in `files/scans/sync.json`
  (id → path, etag, key). Waits for OCR so only searchable PDFs go up. A server file changed
  since we sent it is never deleted.

## Sharing and menus

- `ui/Share.kt`: `ShareMenu` (PDF / images / text) for one or several documents; long press in a
  list starts selecting (bar: share, move, delete, ⋯ when one). Text is reflowed (`Reflow.page`:
  full-width lines joined, hyphens mended, short lines kept) and sent as a message, or as a .txt
  above 60 000 characters.
- Work launched from a menu runs on `appScope`: a menu's own scope dies with the menu, and the
  share sheet never opened (seen on the emulator).
- `TextMenu` runs the chosen item *before* closing, so an item that opens a prompt (rename, move,
  delete, read again) is not dismissed with the menu.

## Naming

`Store.title` = `yyyy-MM-dd HH'h'mm` + name. The name is the user's (filing sheet or rename), or
`Naming.firstWords` of page 1: whole lines until 3 words (max 5 words / 40 characters), debris
lines skipped, never ending on a short lower-case word. Clearing the name in "rename" hands it
back to the OCR words at the next reading.

## Testing

- Unit tests: `./gradlew testDebugUnitTest` (synthetic scenes drawn in pure Kotlin: dark table,
  low contrast, shadow, thumb on the edge, clutter, strong perspective, rotation, empty table).
- Emulator: the emulated camera shows a test scene, so import a synthetic photo instead
  (generated with PIL, never a personal document). WebDAV: wsgidav on the host, port 8085,
  user test/x, server `http://10.0.2.2:8085` (cleartext allowed only for loopback/10.0.2.2).
