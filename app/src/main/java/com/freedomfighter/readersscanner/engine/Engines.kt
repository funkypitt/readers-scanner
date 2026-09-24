package com.freedomfighter.readersscanner.engine

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri

/** A piece of text read on a page (a word or a line) and its box, in the page's pixels: for the PDF's text layer. */
class Word(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

/** What a reader found on one page. */
class ReadPage(val text: String, val words: List<Word>)

/**
 * Google's engines, present only in the private build (`src/prive`); the public build's
 * stand-in (`src/publique`) says they are not there.
 */
interface GoogleEngines {
    val available: Boolean
    /** Text recognition (Latin script), on the phone. */
    suspend fun read(bitmap: Bitmap): ReadPage
    /** Google's document scanner screen; [onReady] gets what to launch. */
    fun scanner(activity: Activity, onReady: (IntentSender) -> Unit, onError: (String) -> Unit)
    /** The page pictures it returned. */
    fun scannedPages(data: Intent?): List<Uri>
}
