# Reader's Scanner for the desktop — design (2026-09-26; built 2026-09-27 in readers-scanner-desktop)

A desktop twin of the Android app for a real scanner (flatbed and document feeder), sharing the
phone's folders through the same WebDAV folder, with NAPS2 doing the acquisition and the app
doing everything else the Reader's way.

## What the user sees

One window, the family's look (black and white, text, the phone's folder list on the left).

- **Left**: « all scans », the folders (small glyph, name, count), « + new folder » — the same list
  as the phone's home, as a list. Click → the folder's documents, newest first, « ← » to go back.
  Search (names and text) on top, as in Reader's Notes.
- **Right**: the open document — its pages one under the other, « text » to read it, ⋯ with
  share, open with, rename, move, read again, delete.
- **Bottom left**: one big « scan » button. Everything a scan needs is three choices on one line
  above it, remembered:
  - **source**: glass · feeder · both sides
  - **look**: clean · grey · b & w · as scanned
  - **language of the text**: EN FR DE IT ES PT RU (the phone's chips)
- A scan: the pages arrive in a review strip (turn, delete, reorder, « + page » for the glass),
  then the phone's filing sheet: an optional name, the folder. The text is read afterwards.

Nothing else. No resolution, paper size, colour depth, profiles, batch or naming settings: 300 dpi
colour, deskewed; the page size follows the « page format » setting shared with the phone
(automatic, A series, Letter); blank backs from « both sides » are dropped; the file name is the
phone's (`2026-09-26 14h05 First words.pdf`). The scanner is found once and remembered; a second
scanner is a choice in the settings, not in the scan.

## Acquisition: NAPS2 as a tool, not a framework

Measured here (NAPS2 8.2.1, HP ScanJet Pro 4500 fn1 on USB): `naps2 console --listdevices --driver
sane` answers in 11 s and sees the scanner by four routes (escl via ipp-usb, airscan, two hpaio);
`--driver escl` (NAPS2's own network discovery) finds nothing in 60 s. So: SANE on Linux, WIA on
Windows, Apple on macOS; discovery once, then `--device` directly.

One scan =

    naps2 console --noprofile --driver sane --device "<remembered>" \
      --source feeder|glass|duplex --dpi 300 --bitdepth color --pagesize a4|letter \
      --deskew --jpegquality 92 -o <work>/p$(nnnn).jpg -f -v

`-v` gives progress lines (a page counter on screen); the pages land as JPEGs; the rest is ours:
blank-page removal (ink coverage, NAPS2 has no option for it), the four looks (the phone's
`Clean` background division, ported), Tesseract (the `best` models: a desktop has the time) with its
PDF renderer → the same searchable PDF as the phone.

**Why the console and not NAPS2.Sdk.** The SDK (LGPL-2.1, NuGet) would mean a C#/.NET app
(Avalonia or Eto), away from the family's Python/PyQt5 twins (Notes, Tasks, Calendar, Podcasts:
one file, .deb + PKGBUILD + apt + PyInstaller builds already in place). Calling the console
keeps the app MIT and Python, and NAPS2 (GPL-2.0) stays a separate program the user installs
(.deb/.rpm/Flatpak on Linux, installer on Windows, .app on macOS); the app finds it (`naps2`,
`flatpak run com.naps2.Naps2 console`, `NAPS2.Console.exe`, `NAPS2.app/Contents/MacOS/NAPS2
console`) and says how to get it when absent. The SDK stays the fallback if the console proves too
coarse (no page-by-page events: a feeder batch arrives at the end).

### NAPS2 is required — said everywhere, for everyone (user's rule, 2026-09-26)

- **README, gallaz.ch/eink article, GitHub release notes, .deb/PKGBUILD descriptions**: first
  line after the summary: « Requires NAPS2 (free, naps2.com), installed separately: it talks to the
  scanner. » PKGBUILD `depends`/`optdepends` and the .deb `Depends:`/`Recommends:` name it where the
  distribution packages it; otherwise the description says where to get it.
- **First launch without NAPS2**: not an error message but a page — what NAPS2 is and why the app
  needs it, the download link for this system (naps2.com/download, or the Flatpak line on Linux),
  and « look again » once it is installed. The app never pretends to scan without it.
- **Settings**: « scanner program: NAPS2 x.y found at … » (or « not found — install it »), so
  the dependency stays visible.

## Sync with the phone: the part that decides everything

Today the phone only sends (one-way mirror, PDFs only), so a desktop cannot share its folders
yet. Both apps must become two-way on the same layout, which stays readable in kDrive:

    Scans/                          ← the account folder
      <folder>/                     ← a folder = a subfolder, one level (as in Reader's Notes 1.6.0)
        2026-09-26 14h05 Facture.pdf
      2026-09-26 15h10 Lettre.pdf   ← « all scans » only
      .readers-scanner/             ← hidden, one small JSON per document
        <docid>.json                ← id, created, name, named, folder, lang, page count,
                                       text of each page, the PDF's path and etag

- **Folders**: exactly Reader's Notes' rules (created, renamed, deleted on either side; a folder
  deleted there disappears here; one deleted here leaves the server once empty).
- **Documents**: each side uploads its own PDF + sidecar. The other side lists the sidecars: name,
  date, folder and the text (for search) come without downloading anything; the PDF is fetched when
  the document is opened (or on Wi-Fi, a setting), rendered page by page (`PdfRenderer` on Android,
  Qt PDF or pdftoppm on the desktop). Rename, move and delete travel through the sidecar, with
  Notes' etag checks (a document changed there since is not deleted, it comes back).
- **Page edits** (crop, turn, look) stay possible only where the pages' photos live (the side that
  scanned them); the other side can rename, move, delete, read again, share.

This is phase 0, on the phone, and useful without any desktop: a second phone, or a reinstall,
gets the documents back.

## Phases

0. **Phone**: two-way sync as above (sidecars, remote documents, folders from the server). The
   one real risk: tested like Notes against wsgidav, then kDrive.
1. **Desktop MVP**: find NAPS2 and the scanner; scan (glass, feeder, both sides); review; filing;
   Tesseract → PDF; upload + sidecar. Linux first (.deb, PKGBUILD, apt).
2. **Desktop reads the phone's documents**: list, open, search, rename, move, delete.
3. **Windows and macOS** builds (the family's GitHub Actions + PyInstaller recipe; NAPS2's WIA and
   Apple drivers behind the same console).

## Open questions

- Two-way sync means the phone downloads documents it did not scan: lazily (on open) by default?
- The desktop look default: « as scanned » (a flatbed scan is already clean) or « clean »?
- Duplex blank-page threshold: to be set on real pages (receipts, forms with little ink).
