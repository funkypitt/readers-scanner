package com.freedomfighter.readersscanner.sync

import android.content.Context
import com.freedomfighter.readersscanner.data.Doc
import com.freedomfighter.readersscanner.data.OcrState
import com.freedomfighter.readersscanner.data.Pdf
import com.freedomfighter.readersscanner.data.Settings
import com.freedomfighter.readersscanner.data.Store
import com.freedomfighter.readersscanner.data.encodeSegment
import org.json.JSONObject
import java.io.File

/**
 * The phone is the reference; the server gets a copy. Each document goes up as its PDF, into
 * the account's folder, or a subfolder named after the app folder it is filed in. A new text
 * (OCR done), a new name or another folder uploads it again under its new path and removes
 * the old one; a document deleted here is deleted there, unless someone changed it there since.
 * Documents whose text is still being read wait, so the server only ever gets searchable PDFs.
 * Nothing is downloaded.
 */
object Sync {
    class Result(val uploaded: Int, val deleted: Int)

    /** What reached the server: its path under the account folder, its etag, and what it was. */
    private data class Sent(val path: String, val etag: String?, val key: String)

    private fun stateFile(context: Context) = File(context.filesDir, "scans/sync.json")

    private fun readState(context: Context): MutableMap<String, Sent> = runCatching {
        val o = JSONObject(stateFile(context).readText())
        o.keys().asSequence().associateWith { k ->
            val e = o.getJSONObject(k)
            Sent(e.getString("path"), e.optString("etag").takeIf { e.has("etag") && it.isNotEmpty() }, e.getString("key"))
        }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun writeState(context: Context, m: Map<String, Sent>) {
        val o = JSONObject()
        m.forEach { (id, s) -> o.put(id, JSONObject().put("path", s.path).put("key", s.key).apply { s.etag?.let { put("etag", it) } }) }
        val f = stateFile(context); val tmp = File(f.parentFile, "sync.json.tmp")
        tmp.writeText(o.toString(1)); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** What the server copy depends on: the pages (rev), the text (ocr), the name and the folder. */
    private fun key(d: Doc) = "${d.rev}|${d.ocr}|${Store.title(d)}|${d.folder.orEmpty()}"

    private fun folderSegment(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.').ifBlank { "_" }

    fun run(context: Context, settings: Settings): Result {
        val dav = WebDav(settings.username, settings.password)
        val root = settings.folderUrl
        dav.mkdirs(root)
        val state = readState(context)
        var up = 0; var del = 0
        val listed = HashMap<String, Map<String, RemoteFile>>()   // subfolder → its files, read once per run
        fun listing(sub: String): Map<String, RemoteFile> = listed.getOrPut(sub) {
            val url = root + (if (sub.isEmpty()) "" else encodeSegment(sub) + "/")
            if (sub.isNotEmpty()) dav.mkdirs(url)
            dav.list(url).filter { !it.isDir }.associateBy { it.name }
        }
        fun url(path: String) = root + path.split('/').joinToString("/") { encodeSegment(it) }
        fun remove(s: Sent) {
            val sub = s.path.substringBeforeLast('/', "")
            val there = listing(sub)[s.path.substringAfterLast('/')]
            // changed on the server since we sent it: somebody's work, left alone
            if (there != null && (s.etag == null || there.etag == null || there.etag == s.etag)) { dav.delete(url(s.path)); del++ }
        }

        // Deleted here.
        for (id in state.keys.toList()) if (Store.doc(id) == null) { remove(state.getValue(id)); state.remove(id); writeState(context, state) }

        val taken = state.values.map { it.path }.toMutableSet()
        for (d in Store.docs().sortedBy { it.created }) {
            if (d.ocr == OcrState.PENDING) continue
            val sent = state[d.id]
            val k = key(d)
            if (sent != null && sent.key == k) continue
            val sub = Store.folder(d.folder)?.name?.let { folderSegment(it) }.orEmpty()
            val there = listing(sub)
            var name = Store.fileName(d)
            var path = if (sub.isEmpty()) name else "$sub/$name"
            // another document, or someone's file, already has that name
            var i = 2
            while (path != sent?.path && (path in taken || there.containsKey(name))) {
                name = Store.fileName(d).removeSuffix(".pdf") + " ($i).pdf"; i++
                path = if (sub.isEmpty()) name else "$sub/$name"
            }
            val pdf = Pdf.ensure(context, d) ?: continue
            val etag = dav.putFile(url(path), pdf, "application/pdf") ?: runCatching { dav.etagOf(url(path)) }.getOrNull()
            if (sent != null && sent.path != path) { remove(sent); taken.remove(sent.path) }
            taken.add(path)
            state[d.id] = Sent(path, etag, k)
            writeState(context, state)
            up++
        }
        return Result(up, del)
    }
}
