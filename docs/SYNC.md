# Sync protocol (1.1.0) — shared by every device running Reader's Scanner

The phone (`sync/Sync.kt`) and the future desktop app follow the same rules on one WebDAV folder
(the account's « folder on the server », default `Scans`).

## Layout

    Scans/
      <folder>/<yyyy-MM-dd HHhmm> <name>.pdf   a folder = a subfolder, one level deep
      <yyyy-MM-dd HHhmm> <name>.pdf            « all scans » only
      .readers-scanner/<id>.json               the document's description

A PDF without a description is not a document (someone's file: left alone). A description whose
PDF is missing is a document being moved or deleted (the next run sorts it out).

## Description (`Meta`, JSON, UTF-8)

    {"format": "readers-scanner", "version": 1, "id": "…", "created": ms, "modified": ms,
     "name": "…" | null, "named": bool, "folder": "Factures" | "", "lang": "fra",
     "pages": n, "text": ["page 1", …], "pdf": "Factures/2026-09-26 13h53 Facture.pdf",
     "readBy": "tesseract-best" | "mlkit" | …, "ocr": "DONE" | "FAILED"}

Readers ignore unknown fields and default missing ones. `created` gives the file name's date;
`modified` (name, folder or pages changed) settles conflicts: the latest change wins. Only
documents whose text has been read (`ocr` DONE or FAILED) are published.

## Each device keeps, per document, what was agreed at the last run

The PDF's path and etag, the description's etag, and its own signature of the document
(pages revision, text state, title, folder name, language). With that:

1. **Described there, unknown here**: known before → deleted here: deleted there too (PDF and
   description) unless its description changed there since, in which case it comes back. Never
   known → a document from elsewhere: its description is read; the PDF is NOT downloaded.
2. **Described there and here**: changed there only → apply theirs (name, folder… ; a document
   from elsewhere also gets its new text, and its cached PDF is dropped if the PDF's etag
   changed); changed here only → send ours; both → the later `modified` wins.
3. **Here, not described there**: it was described before → deleted there: deleted here unless
   changed here since (then sent again). Never described → sent (a 1.0.x document gets its
   description on the first run; its PDF is not sent again if unchanged).
4. **Sending**: the PDF goes up only when its pages or text changed; a new name or folder is a
   `MOVE` (never overwriting); then the description is `PUT`. A device never uploads the PDF of a
   document it did not scan.
5. **Folders**: server subfolders ↔ folders here, by name. Created here → `MKCOL`; seen there and
   gone → deleted there: gone here (its documents stay, in « all scans »); deleted or renamed here →
   `DELETE`d there once no file is left in it.

Tested 2026-09-26 against wsgidav with the phone and a script playing a second device: upgrade
from 1.0.3 (description added, PDF untouched), documents from elsewhere (folder created, text
searchable, PDF downloaded on open), rename + move and deletion made elsewhere, rename of a
document from elsewhere (a MOVE: same file on the server), deletion here, folder rename here.
