# Privacy policy — Reader's Scanner

*Last updated: 8 November 2026*

Reader's Scanner (`com.freedomfighter.readersscanner`) is published by Pierre Gallaz. It scans documents with the phone's camera, reads their text on the phone and files them as searchable PDFs. It is free, has no ads, no account and no tracking of its own.

## Data the app collects

The app itself does not collect, store or transmit any personal data to the publisher, and it contains no analytics, advertising or crash-reporting library.

## Text recognition engines

At first start you choose how pages are found and read:

- **the free engines**: the app's own page finder and Tesseract. Everything runs on the phone; nothing leaves it.
- **Google ML Kit** (document scanner and text recognition, both running on the phone). This closed-source component of Google Play services may send usage statistics to Google; it never sends your documents. Its use is governed by Google's privacy policy. You can switch engines at any time in the settings.

Russian text is always read by Tesseract on the phone.

## Network use

The network is used only at your request:

- **WebDAV folder**, if you set one up in the settings: your scans (PDFs and small description files) are copied to and from the server you name (for instance kDrive or Nextcloud), with the login you give. Nothing else is sent, and the app never contacts a server you have not configured.
- **Better language models**, if you download them in the settings: the files are fetched from github.com (the tesseract-ocr project). This happens only when you tap the download button.

Connections are over HTTPS, except to a machine's own loopback address for testing.

## Data kept on the phone

Your documents, their text, your folders, your settings and, if you entered them, your WebDAV address and login stay in the app's private storage on the phone. They are deleted when the app is uninstalled. Android backup is disabled for the app, so nothing is copied to a Google account by the system.

The settings can export the WebDAV login to a file you choose, and import one made by another Reader's app, only when you ask.

## Permissions

- **Camera**: to photograph the pages. Pictures are taken only while you are on the capture screen.
- **Internet**: only for the WebDAV folder and the optional model download described above.

## Children

The app is not directed at children and collects no data from anyone.

## Contact

Pierre Gallaz — pierregallaz@gmail.com

Source code: https://github.com/funkypitt/readers-scanner
