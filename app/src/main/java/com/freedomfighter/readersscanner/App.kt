package com.freedomfighter.readersscanner

import android.app.Application
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.freedomfighter.readersscanner.data.CredentialsShare
import com.freedomfighter.readersscanner.data.Ocr
import com.freedomfighter.readersscanner.data.Prefs
import com.freedomfighter.readersscanner.data.Store
import com.freedomfighter.readersscanner.sync.Sync
import com.freedomfighter.readersscanner.sync.WebDavException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class App : Application() {
    val prefs: Prefs by lazy { Prefs(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow("")
    /** The last sync, for the settings screen: "synced 11:32 · 2 sent" or the error. */
    val status: StateFlow<String> = _status
    /** Where to go once a scan is filed: the folder (or "" for all scans) and the document. */
    val reveal = mutableStateOf<Pair<String, String>?>(null)
    val resumes = mutableIntStateOf(0)
    private var syncJob: Job? = null
    private var again = false

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        CredentialsShare.cleanUp(this)
        Ocr.onDone = { sync() }
        Ocr.engine = { prefs.settings.value.reader }
        Ocr.start(this)
    }

    /** The words for a failed sync: our own for what we know, the message (an HTTP code) otherwise. */
    private fun reason(e: Exception): String = when {
        e is WebDavException && e.reason == WebDavException.Reason.LOGIN -> getString(R.string.error_login)
        e is WebDavException && e.reason == WebDavException.Reason.UNSUPPORTED -> getString(R.string.error_unsupported)
        e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketTimeoutException -> getString(R.string.error_no_connection)
        else -> e.message ?: e.javaClass.simpleName
    }

    /** Sends what changed to the WebDAV folder, if one is set. Calls during a run make one more run. */
    fun sync(delayMs: Long = 0) {
        val s = prefs.settings.value
        if (!s.configured) return
        synchronized(this) {
            if (syncJob?.isActive == true) { again = true; return }
            syncJob = scope.launch {
                if (delayMs > 0) delay(delayMs)
                do {
                    synchronized(this@App) { again = false }
                    _status.value = getString(R.string.syncing)
                    _status.value = try {
                        val r = Sync.run(this@App, prefs.settings.value)
                        val t = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
                        listOfNotNull(getString(R.string.synced_at, t), if (r.uploaded > 0) getString(R.string.n_sent, r.uploaded) else null, if (r.deleted > 0) getString(R.string.n_removed, r.deleted) else null, if (r.downloaded > 0) getString(R.string.n_received, r.downloaded) else null).joinToString(" · ")
                    } catch (e: Exception) { getString(R.string.sync_failed, reason(e)) }
                } while (synchronized(this@App) { again })
            }
        }
    }
}
