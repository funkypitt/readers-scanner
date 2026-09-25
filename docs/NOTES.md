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

## Blurred pages (1.0.1, user report: "sometimes blurry, hard to spot before all pages are scanned")

- Shutter (`ScanActivity.shoot`): the touch counts as a movement; waits until the gyroscope
  (accelerometer without one) has been quiet 300 ms, at most 1.5 s ("ne bougez plus…"), then
  `startFocusAndMetering` AF+AE on the page's centre (at most 1.2 s), then takes the photo.
- `scan/Sharpness.kt`: Crete et al. 2007 no-reference blur score on the straightened page before
  its clean-up, at 1400 px across, after a σ≈1 denoise and on edge pixels only (30 % of the
  99.5th percentile). Threshold 0.45. JVM test on the rendered letter: sharp 0.24, sparse page of
  big letters 0.40, gauss 1 → 0.34 (kept), gauss 1.7 → 0.50, 2.2 → 0.59 (dim 0.59), motion 11 →
  0.62. A page that is mostly a photograph may be flagged: "garder" is there for that.
- Capture screen: "la page N est floue — reprendre / garder" at once; review: "· floue" in the
  title, a count of blurred pages, and a label under the page. "reprendre" puts the new photo
  in the old one's place (`Session.retakeAt`).

## Page format (1.0.2, user: "force A4 / Letter to help the border detection?")

- Setting AUTO / A (1:√2, the whole A series) / LETTER. Used in `Clean.outputSize`: the page is
  straightened to the exact ratio, orientation from the photo; kept as estimated when more than
  8 % off (receipt, card, Letter page with A set). PDF page and Tesseract dpi follow the page's
  proportions (`Clean.longSideInches`: 11 in for Letter's ratio, else 11.69).
- NOT used by the detector, after measuring: at 240×320 a 1-px corner error moves the estimated
  proportions of a real A4 between 0.62 and 0.79, so a format prior could not tell outlines
  apart; tried as a scoring bell + relaxed support + side completion: +2/−1 on 56 synthetic
  scenes, and it lost a page auto found. Removed.
- What did help detection (format-independent): a second Canny pass with low thresholds when the
  first finds nothing — pale sheet on pale table at 10 grey levels: 0/8 → 2/8. The real limit
  stays: edges never seen cannot be chosen; Google's learned detector is better there.

## Publishing

- Public: `./gradlew -Pabis=arm64-v8a,armeabi-v7a assemblePubliqueRelease` then
  `gallaz-sign app-publique-release-unsigned.apk ReadersScanner-vX.Y.Z.apk --release-only` (a package
  born after 2026-09-11: gallaz key alone). 1.0.0 in the F-Droid repo on 2026-09-24 (commit a3094b1),
  category Office, no SourceCode line yet (no public GitHub repo), no screenshots (awaiting approval).
- Tesseract4Android is the single-threaded build: the openmp one aborted in `__kmp_fatal` on arm64
  (seen under the emulator's arm translation). All native libraries are 16 KB aligned.
- Private: `-Pabis=arm64-v8a assemblePriveDebug` (38 MB, native libraries compressed for this
  flavour only, so it goes through the Telegram bot).
- Emulator trap: `-no-snapshot-save` boots from the old quick-boot snapshot, so pushed photos and
  app data vanish at every restart; push the test photo again each time.

## ML Kit in the public app (1.0.3, user: "we keep ML for the public version, it's just too good")

- Both flavours carry ML Kit (`engine/MlKit.kt` moved to main; the publique stub is gone).
  First start (`EngineChoiceScreen`, until `engine_chosen`): Google ML Kit (recommended; disabled
  with a note when `GoogleApiAvailability` says Play services are missing) or the free engines;
  sets both steps, changeable per step in the settings. Upgraders from 1.0.2 get the question too.
- Russian is always read by Tesseract (ML Kit: Latin only). The comparison screen is `prive` only.
- ML Kit adds `ACCESS_NETWORK_STATE` and sends usage statistics to Google (its data disclosure);
  the F-Droid entry declares NonFreeDep, UpstreamNonFree and Tracking.

## Two builds (history)

- `publique` (F-Droid): free software only. `prive` (`applicationIdSuffix .prive`, name
  "Reader's Scanner (privé)"): adds Google ML Kit to compare — document scanner (needs Play
  services; its own screen, so the language row moves to the review) and Latin text
  recognition (bundled). `engine/Engines.kt` is the interface; `src/prive/.../MlKit.kt` the real
  one, `src/publique/.../MlKit.kt` a stub. Settings: "trouver la page" / "lire le texte". The public
  APK has no Google classes (checked with `unzip -l | grep -c gms`).
- ML Kit gives lines and boxes: `Pdf.write(..., layers)` writes the invisible text layer itself
  (Helvetica WinAnsi, stretched with Tz). Word by word mixed up the copy order in pdftotext;
  lines keep it.
- "Best" Tesseract models: `data/Models.kt` downloads tessdata_best per language into
  `files/ocr/best/tessdata`; `Ocr.tesseractFor` picks it when present. The document's info line
  says which reader read it (`Doc.readBy`).
- Comparison screen (private build, document ⋯): every reader on the same pages, time and word
  count, nothing saved. First sample (synthetic letter, emulator): fast 3 errors, best 0, ML Kit 1
  + lost spaces before colons; all ~0.8–0.9 s a page.
- Build for the phone only: `./gradlew -Pabis=arm64-v8a assemblePriveDebug` (49 MB, under the
  Telegram bot's 50 MB). On the emulator (no Google account) Play services cannot fetch the
  scanner module ("Something went wrong"); cancelling returns to the app.

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
