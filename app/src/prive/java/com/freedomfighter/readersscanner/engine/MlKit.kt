package com.freedomfighter.readersscanner.engine

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/** Google ML Kit: the document scanner (through Play services) and Latin text recognition (bundled). */
object MlKit : GoogleEngines {
    override val available = true

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun read(bitmap: Bitmap): ReadPage {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val words = ArrayList<Word>()
        // Whole lines in the PDF's text layer: viewers then copy and search them in reading order
        // (word by word, pdftotext mixed neighbouring lines up).
        for (block in result.textBlocks) for (line in block.lines) {
            val b = line.boundingBox ?: continue
            words.add(Word(line.text, b.left, b.top, b.right, b.bottom))
        }
        // Blocks are paragraphs: a blank line between them, like Tesseract's text.
        val text = result.textBlocks.joinToString("\n\n") { b -> b.lines.joinToString("\n") { it.text } }
        return ReadPage(text, words)
    }

    override fun scanner(activity: Activity, onReady: (IntentSender) -> Unit, onError: (String) -> Unit) {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(100)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
            .addOnSuccessListener { onReady(it) }
            .addOnFailureListener { onError(it.message ?: it.javaClass.simpleName) }
    }

    override fun scannedPages(data: Intent?): List<Uri> =
        GmsDocumentScanningResult.fromActivityResultIntent(data)?.pages?.map { it.imageUri }.orEmpty()
}
