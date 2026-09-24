package com.freedomfighter.readersscanner.engine

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri

/** The public build carries no Google code. */
object MlKit : GoogleEngines {
    override val available = false
    override suspend fun read(bitmap: Bitmap): ReadPage = error("not in this build")
    override fun scanner(activity: Activity, onReady: (IntentSender) -> Unit, onError: (String) -> Unit) = onError("not in this build")
    override fun scannedPages(data: Intent?): List<Uri> = emptyList()
}
