package com.freedomfighter.readersscanner.sync

import android.content.Context
import com.freedomfighter.readersscanner.data.Doc
import com.freedomfighter.readersscanner.data.OcrState
import com.freedomfighter.readersscanner.data.Pdf
import com.freedomfighter.readersscanner.data.Settings
import com.freedomfighter.readersscanner.data.Store
import com.freedomfighter.readersscanner.data.encodeSegment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Two-way sync with the WebDAV folder (1.1.0), shared with any other device running Reader's
 * Scanner (a second phone, the desktop app):
 *
 *     Scans/                              the account's folder
 *       <folder>/2026-09-26 14h05 Name.pdf  a folder = a subfolder, one level deep
 *       2026-09-26 15h10 Name.pdf           « all scans » only
 *       .readers-scanner/<id>.json          one description per document ([Meta])
 *
 * The description carries everything but the pages: name, date, folder, language, and the text
 * of each page — enough to list and search a document from elsewhere without downloading it; its
 * PDF comes down when the document is opened ([fetchPdf]). A document scanned here goes up as its
 * searchable PDF (once its text is read) and its description; renames and moves travel as a
 * MOVE of the file, never as a new upload. Changed on both sides: the latest change wins.
 * Deleted on one side: deleted on the other, unless it changed there since. Folders follow Reader's
 * Notes' rules: created either side; deleted there → gone here (its documents stay, in « all
 * scans »); deleted or renamed here → removed there once empty.
 */
object Sync {
    const val META_DIR = ".readers-scanner"

    class Result(val uploaded: Int, val deleted: Int, val downloaded: Int = 0)

    /**
     * What both sides agreed on at the last sync: the PDF's path (under the account folder) and
     * etag, the description's etag (null: sent by 1.0.x, which had none), the document's
     * signature here then ([key]) and that of its pages ([pdfKey]).
     */
    private data class Sent(val path: String, val etag: String?, val key: String, val pdfKey: String = "", val metaEtag: String? = null)

    private fun stateFile(context: Context) = File(context.filesDir, "scans/sync.json")
    private val lock = Any()

    private fun readState(context: Context): MutableMap<String, Sent> = runCatching {
        val o = JSONObject(stateFile(context).readText())
        o.keys().asSequence().associateWith { k ->
            val e = o.getJSONObject(k)
            Sent(e.getString("path"), e.optString("etag").takeIf { e.has("etag") && it.isNotEmpty() }, e.getString("key"),
                e.optString("pdfKey"), e.optString("metaEtag").takeIf { e.has("metaEtag") && it.isNotEmpty() })
        }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun writeState(context: Context, m: Map<String, Sent>) {
        val o = JSONObject()
        m.forEach { (id, s) ->
            o.put(id, JSONObject().put("path", s.path).put("key", s.key).put("pdfKey", s.pdfKey).apply {
                s.etag?.let { put("etag", it) }; s.metaEtag?.let { put("metaEtag", it) }
            })
        }
        val f = stateFile(context); val tmp = File(f.parentFile, "sync.json.tmp")
        tmp.writeText(o.toString(1)); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun folderName(d: Doc) = Store.folder(d.folder)?.name.orEmpty()
    /** What the description depends on here. */
    private fun key(d: Doc) = "${d.rev}|${d.ocr}|${Store.title(d)}|${folderName(d)}|${d.lang}|${d.remote}|${d.named}"
    /** What the PDF depends on. */
    private fun pdfKey(d: Doc) = "${d.rev}|${d.ocr}"

    private fun isPdf(name: String) = name.endsWith(".pdf", ignoreCase = true)

    fun run(context: Context, settings: Settings): Result = synchronized(lock) {
        val dav = WebDav(settings.username, settings.password)
        val root = settings.folderUrl
        dav.mkdirs(root)
        fun url(path: String) = root + path.split('/').joinToString("/") { encodeSegment(it) }
        fun dirUrl(folder: String) = root + encodeSegment(folder) + "/"
        val metaDir = root + encodeSegment(META_DIR) + "/"
        fun metaUrl(id: String) = metaDir + encodeSegment("$id.json")

        val entries = dav.list(root)
        val dirs = entries.filter { it.isDir && !it.name.startsWith(".") }.map { it.name }.toSet()
        val pdfs = HashMap<String, RemoteFile>()   // path under the account folder → file
        entries.filter { !it.isDir && isPdf(it.name) }.forEach { pdfs[it.name] = it }
        for (d in dirs) dav.list(dirUrl(d)).filter { !it.isDir && isPdf(it.name) }.forEach { pdfs["$d/${it.name}"] = it }
        val metas: Map<String, RemoteFile> =
            if (entries.any { it.isDir && it.name == META_DIR }) dav.list(metaDir).filter { !it.isDir && it.name.endsWith(".json") }.associateBy { it.name.removeSuffix(".json") }
            else { dav.mkcol(metaDir); emptyMap() }
        val state = readState(context)
        val gone = Store.goneFolders().toSet()
        var up = 0; var del = 0; var down = 0

        // --- folders ---------------------------------------------------------------------------
        val present = dirs.toMutableSet()
        for (d in dirs) if (d !in gone) Store.folderOnServer(d)
        for (f in Store.folders()) {
            if (f.name in dirs) continue
            if (f.onServer) Store.folderGoneThere(f.id)
            else { dav.mkcol(dirUrl(f.name)); Store.folderOnServer(f.name); present += f.name }
        }
        fun ensureDir(path: String) {
            val folder = path.substringBefore('/', "")
            if (folder.isNotEmpty() && folder !in present) { dav.mkcol(dirUrl(folder)); Store.folderOnServer(folder); present += folder }
        }

        // --- sending a document ----------------------------------------------------------------
        val taken = HashSet<String>()
        fun upload(d: Doc, sent: Sent?) {
            val folder = folderName(d)
            fun at(name: String) = if (folder.isEmpty()) name else "$folder/$name"
            var name = Store.fileName(d)
            var path = at(name)
            var i = 2
            // another document, or someone's file, already has that name
            while (path != sent?.path && (path in taken || pdfs.containsKey(path) || state.any { (id, s) -> id != d.id && s.path == path })) {
                name = Store.fileName(d).removeSuffix(".pdf") + " ($i).pdf"; path = at(name); i++
            }
            var etag: String?
            if (!d.remote && (sent == null || sent.pdfKey != pdfKey(d) || !pdfs.containsKey(sent.path))) {
                val pdf = Pdf.ensure(context, d) ?: return
                ensureDir(path)
                etag = dav.putFile(url(path), pdf, "application/pdf") ?: runCatching { dav.etagOf(url(path)) }.getOrNull()
                if (sent != null && sent.path != path && pdfs.containsKey(sent.path)) dav.delete(url(sent.path))
            } else if (sent != null && sent.path != path && pdfs.containsKey(sent.path)) {
                ensureDir(path)
                dav.move(url(sent.path), url(path))
                etag = runCatching { dav.etagOf(url(path)) }.getOrNull()
            } else {
                if (sent == null) return   // a remote document never goes up as a new file
                path = sent.path; etag = sent.etag
            }
            taken += path
            val metaEtag = dav.putText(metaUrl(d.id), Meta.build(d, folder, path, Store.text(d), Store.pageCount(d))) ?: runCatching { dav.etagOf(metaUrl(d.id)) }.getOrNull()
            state[d.id] = Sent(path, etag, key(d), pdfKey(d), metaEtag ?: "?")
            writeState(context, state)
            up++
        }

        // --- 1. documents described on the server ----------------------------------------------
        for ((id, mf) in metas) {
            val local = Store.doc(id)
            var sent = state[id]
            if (local == null) {
                if (sent != null && (sent.metaEtag == null || sent.metaEtag == mf.etag)) {
                    // deleted here, unchanged there: deleted there too
                    if (pdfs.containsKey(sent.path)) dav.delete(url(sent.path))
                    dav.delete(metaUrl(id))
                    state.remove(id); writeState(context, state); del++
                    continue
                }
                // new from elsewhere (or deleted here but changed there since: it comes back)
                val m = Meta.parse(dav.getOrNull(metaUrl(id)) ?: continue) ?: continue
                val folderId = if (m.folder.isEmpty()) null else (Store.folderByName(m.folder) ?: Store.folderOnServer(m.folder)).id
                val doc = Doc(m.id, m.created, m.name, m.named, folderId, m.lang, emptyList(), m.ocr, 0, m.readBy,
                    remote = true, pageCount = m.pages, modified = m.modified)
                Store.putRemote(doc, m.text, dropPdf = true)
                state[id] = Sent(m.pdf, pdfs[m.pdf]?.etag, key(Store.doc(id) ?: doc), pdfKey(doc), mf.etag)
                writeState(context, state); down++
                continue
            }
            val changedThere = sent == null || (sent.metaEtag != null && sent.metaEtag != mf.etag)
            val changedHere = sent == null || sent.key != key(local)
            if (changedThere) {
                val m = Meta.parse(dav.getOrNull(metaUrl(id)) ?: continue) ?: continue
                if (!changedHere || m.modified > local.modified) {
                    // theirs is the latest
                    val folderId = if (m.folder.isEmpty()) null else (Store.folderByName(m.folder) ?: Store.folderOnServer(m.folder)).id
                    if (local.remote) {
                        val pdfChanged = sent == null || sent.etag != pdfs[m.pdf]?.etag
                        Store.putRemote(local.copy(name = m.name, named = m.named, folder = folderId, lang = m.lang, ocr = m.ocr,
                            readBy = m.readBy, pageCount = m.pages, modified = m.modified), m.text, dropPdf = pdfChanged)
                    } else {
                        Store.applyRemoteMeta(id) { it.copy(name = m.name, named = m.named, folder = folderId, modified = m.modified) }
                    }
                    val now = Store.doc(id) ?: continue
                    state[id] = Sent(m.pdf, pdfs[m.pdf]?.etag, key(now), pdfKey(now), mf.etag)
                    writeState(context, state); down++
                    continue
                }
                // ours is the latest; its file is where they left it (renamed there meanwhile, it
                // would otherwise stay behind under their name)
                sent = sent?.copy(path = m.pdf, etag = pdfs[m.pdf]?.etag) ?: Sent(m.pdf, pdfs[m.pdf]?.etag, "")
            }
            // ours is the latest, or sent by 1.0.x without a description yet
            if ((changedHere || sent?.metaEtag == null) && local.ocr != OcrState.PENDING) upload(local, sent)
            else sent?.let { taken += it.path }
        }

        // --- 2. documents here without a description there -------------------------------------
        for (d in Store.docs().sortedBy { it.created }) {
            if (metas.containsKey(d.id)) continue
            val sent = state[d.id]
            if (sent?.metaEtag != null) {
                // its description went: deleted there — here too, unless it changed here since
                if (sent.key == key(d)) { Store.delete(d.id); state.remove(d.id); writeState(context, state); del++; continue }
            }
            if (d.remote || d.ocr == OcrState.PENDING) continue
            upload(d, sent)
        }

        // --- 3. deleted here, described nowhere (sent by 1.0.x): its PDF goes if untouched -----
        for ((id, s) in state.toList()) {
            if (Store.doc(id) != null || metas.containsKey(id)) continue
            val there = pdfs[s.path]
            if (there != null && (s.etag == null || there.etag == null || there.etag == s.etag)) { dav.delete(url(s.path)); del++ }
            state.remove(id); writeState(context, state)
        }

        // --- 4. folders deleted or renamed here: removed there once empty ----------------------
        for (g in gone) {
            if (g in dirs && dav.list(dirUrl(g)).none { !it.isDir }) dav.delete(dirUrl(g))
            Store.folderRemovedThere(g)
        }
        return Result(up, del, down)
    }

    /**
     * Brings down the PDF of a document from elsewhere, into its folder here. Blocking; null when
     * the server does not have it (moved or deleted meanwhile: the next sync sorts it out).
     */
    fun fetchPdf(context: Context, settings: Settings, doc: Doc, progress: ((Long, Long) -> Unit)? = null): File? {
        val path = synchronized(lock) { readState(context)[doc.id]?.path } ?: return null
        val out = Store.pdfFile(doc).apply { parentFile?.mkdirs() }
        val dav = WebDav(settings.username, settings.password)
        val url = settings.folderUrl + path.split('/').joinToString("/") { encodeSegment(it) }
        return if (dav.download(url, out, progress)) out else null
    }
}

/**
 * A document's description on the server, `.readers-scanner/<id>.json`: the same for every
 * device running Reader's Scanner (the desktop app reads and writes it too).
 */
object Meta {
    const val FORMAT = "readers-scanner"

    class Parsed(
        val id: String, val created: Long, val modified: Long, val name: String?, val named: Boolean,
        val folder: String, val lang: String, val pages: Int, val text: List<String>, val pdf: String,
        val readBy: String, val ocr: OcrState
    )

    fun build(d: Doc, folder: String, pdf: String, text: List<String>, pages: Int): String = JSONObject()
        .put("format", FORMAT).put("version", 1)
        .put("id", d.id).put("created", d.created).put("modified", d.modified)
        .put("name", d.name ?: JSONObject.NULL).put("named", d.named)
        .put("folder", folder).put("lang", d.lang).put("pages", pages)
        .put("text", JSONArray().apply { text.forEach { put(it) } })
        .put("pdf", pdf).put("readBy", d.readBy).put("ocr", d.ocr.name)
        .toString(1)

    fun parse(s: String): Parsed? = runCatching {
        val o = JSONObject(s)
        if (o.optString("format") != FORMAT) return null
        val t = o.optJSONArray("text") ?: JSONArray()
        Parsed(
            id = o.getString("id"), created = o.getLong("created"), modified = o.optLong("modified", o.getLong("created")),
            name = if (o.isNull("name")) null else o.optString("name").takeIf { it.isNotBlank() }, named = o.optBoolean("named"),
            folder = o.optString("folder"), lang = o.optString("lang", "eng"), pages = o.optInt("pages"),
            text = (0 until t.length()).map { t.getString(it) }, pdf = o.getString("pdf"),
            readBy = o.optString("readBy"), ocr = runCatching { OcrState.valueOf(o.optString("ocr")) }.getOrDefault(OcrState.DONE)
        )
    }.getOrNull()
}
