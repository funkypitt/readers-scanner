package com.freedomfighter.readersscanner.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** How a page is cleaned up after it is straightened. */
enum class Filter { AUTO, GREY, BW, ORIGINAL }

enum class OcrState { PENDING, DONE, FAILED }

/** A folder; [onServer]: seen on the server or created there, so its absence there means it was deleted there. */
data class Folder(val id: String, val name: String, val onServer: Boolean = false)

/**
 * One page. `quad` = the four corners (top-left, top-right, bottom-right, bottom-left) on the
 * upright source photo, as fractions of its width and height; `rotation` is applied after the
 * page is straightened. Files: `<id>.src.jpg` (the photo) and `<id>.jpg` (the page as shown).
 */
data class Page(val id: String, val quad: FloatArray, val rotation: Int, val filter: Filter)

data class Doc(
    val id: String,
    /** Capture time, epoch milliseconds: it is always the start of the file name. */
    val created: Long,
    /** The user's name, or the first words the OCR read; null until either exists. */
    val name: String?,
    /** True when the user typed the name: the OCR then never replaces it. */
    val named: Boolean,
    val folder: String?,
    val lang: String,
    val pages: List<Page>,
    val ocr: OcrState,
    /** Bumped on every change of the pages, so an OCR run on older pages is thrown away. */
    val rev: Int,
    /** Which reader produced the text: "tesseract-fast", "tesseract-best" or "mlkit". */
    val readBy: String = "",
    /**
     * Scanned on another device (1.1.0, two-way sync): no page photos here, only its description
     * and text; the PDF is downloaded when the document is opened.
     */
    val remote: Boolean = false,
    /** Pages of a remote document (the local ones count [pages]). */
    val pageCount: Int = 0,
    /** Last change of its name, folder or pages, epoch ms: the newer side wins a conflict. */
    val modified: Long = created
)

object Store {
    private lateinit var root: File
    private val lock = Any()
    private var folders = mutableListOf<Folder>()
    private val docs = LinkedHashMap<String, Doc>()
    private val _version = MutableStateFlow(0)
    /** Bumped on every change, so screens read again. */
    val version: StateFlow<Int> = _version

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH'h'mm")

    fun init(context: Context) { synchronized(lock) {
        if (::root.isInitialized) return
        root = File(context.filesDir, "scans").apply { mkdirs() }
        readFolders()
        File(root, "docs").listFiles()?.forEach { dir -> readDoc(dir)?.let { docs[it.id] = it } }
    } }

    private fun changed() { _version.value++ }

    // --- folders ------------------------------------------------------------------------------

    private val foldersFile get() = File(root, "folders.json")

    private val goneFile get() = File(root, "folders-gone.json")
    /** Folders deleted or renamed here (their names), to remove from the server once empty there. */
    private var gone = mutableListOf<String>()

    private fun readFolders() {
        folders = runCatching {
            val a = JSONArray(foldersFile.readText())
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Folder(o.getString("id"), o.getString("name"), o.optBoolean("onServer")) } }.toMutableList()
        }.getOrDefault(mutableListOf())
        gone = runCatching { JSONArray(goneFile.readText()).let { a -> (0 until a.length()).map { a.getString(it) }.toMutableList() } }.getOrDefault(mutableListOf())
    }

    private fun writeFolders() {
        val a = JSONArray()
        folders.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("onServer", it.onServer)) }
        atomicWrite(foldersFile, a.toString(1))
        atomicWrite(goneFile, JSONArray().apply { gone.forEach { put(it) } }.toString())
    }

    fun folders(): List<Folder> = synchronized(lock) { folders.toList() }

    fun folder(id: String?): Folder? = synchronized(lock) { folders.firstOrNull { it.id == id } }

    fun folderByName(name: String): Folder? = synchronized(lock) { folders.firstOrNull { it.name.equals(name, ignoreCase = true) } }

    /** A folder of that name (made safe for a file system: it is a folder on the server too); the existing one if taken. */
    fun addFolder(name: String): Folder = synchronized(lock) {
        val n = folderNameOf(name) ?: "…"
        folderByName(n)?.let { return it }
        val f = Folder(UUID.randomUUID().toString().take(8), n)
        folders.add(f); gone.remove(n); writeFolders(); changed(); f
    }

    fun renameFolder(id: String, name: String) = synchronized(lock) {
        val n = folderNameOf(name) ?: return
        val i = folders.indexOfFirst { it.id == id }
        if (i < 0 || folders[i].name == n || folders.any { it.id != id && it.name.equals(n, ignoreCase = true) }) return
        if (folders[i].onServer) gone.add(folders[i].name)
        folders[i] = folders[i].copy(name = n, onServer = false); gone.remove(n)
        // its documents move with it on the server: their descriptions change
        val now = System.currentTimeMillis()
        docs.values.filter { it.folder == id }.forEach { writeDoc(it.copy(modified = now)) }
        writeFolders(); changed()
    }

    /** The documents stay: they fall back into "all scans" only. */
    fun deleteFolder(id: String) = synchronized(lock) {
        folders.firstOrNull { it.id == id }?.let { if (it.onServer) gone.add(it.name) }
        folders.removeAll { it.id == id }; writeFolders()
        val now = System.currentTimeMillis()
        docs.values.filter { it.folder == id }.forEach { writeDoc(it.copy(folder = null, modified = now)) }
        changed()
    }

    // folder side of the sync
    fun goneFolders(): List<String> = synchronized(lock) { gone.toList() }
    fun folderOnServer(name: String): Folder = synchronized(lock) {
        val f = folderByName(name)
        val out = if (f == null) Folder(UUID.randomUUID().toString().take(8), name, true).also { folders.add(it) }
            else f.copy(onServer = true).also { folders[folders.indexOf(f)] = it }
        writeFolders(); changed(); out
    }
    /** Deleted there: gone here too; its documents fall back into "all scans". */
    fun folderGoneThere(id: String) = synchronized(lock) {
        folders.removeAll { it.id == id }; writeFolders()
        docs.values.filter { it.folder == id }.forEach { writeDoc(it.copy(folder = null)) }
        changed()
    }
    fun folderRemovedThere(name: String) = synchronized(lock) { gone.remove(name); writeFolders() }

    // --- documents ----------------------------------------------------------------------------

    fun dir(id: String) = File(File(root, "docs"), id)
    fun pageFile(doc: Doc, p: Page) = File(dir(doc.id), p.id + ".jpg")
    fun srcFile(doc: Doc, p: Page) = File(dir(doc.id), p.id + ".src.jpg")
    fun pdfFile(doc: Doc) = File(dir(doc.id), "doc.pdf")
    private fun textFile(doc: Doc) = File(dir(doc.id), "text.txt")

    /** Newest first; `folder` null = every document. */
    fun docs(folder: String? = null): List<Doc> = synchronized(lock) {
        docs.values.filter { folder == null || it.folder == folder }.sortedByDescending { it.created }
    }

    fun count(folder: String?): Int = synchronized(lock) { docs.values.count { folder == null || it.folder == folder } }

    fun doc(id: String): Doc? = synchronized(lock) { docs[id] }

    /** The text read on each page (empty list until the OCR has run). */
    fun text(doc: Doc): List<String> = runCatching { textFile(doc).readText().split(PAGE_BREAK) }.getOrDefault(emptyList())

    /** "2026-09-24 11h32" followed by the name, when there is one. */
    fun title(doc: Doc): String {
        val when_ = stamp.format(Instant.ofEpochMilli(doc.created).atZone(ZoneId.systemDefault()))
        return if (doc.name.isNullOrBlank()) when_ else "$when_ ${doc.name}"
    }

    /** The same, safe for any file system. */
    fun fileName(doc: Doc, ext: String = "pdf"): String =
        title(doc).replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(120) + "." + ext

    fun newDocId(): String = UUID.randomUUID().toString().take(12)
    fun newPageId(): String = UUID.randomUUID().toString().take(8)

    /** Writes a new or changed document. The files of its pages must already be in its folder. */
    fun put(doc: Doc) = synchronized(lock) {
        dir(doc.id).mkdirs()
        writeDoc(doc)
        // New pages: the old text and PDF no longer match them.
        if (doc.ocr == OcrState.PENDING) { textFile(doc).delete(); pdfFile(doc).delete() }
        // Page files that no longer belong to the document go.
        val keep = doc.pages.flatMap { listOf(it.id + ".jpg", it.id + ".src.jpg") }.toSet() + setOf("doc.json", "doc.pdf", "text.txt", "render")
        dir(doc.id).listFiles()?.forEach { if (it.name !in keep && !it.name.startsWith("ocr-")) it.delete() }
        changed()
    }

    fun rename(id: String, name: String?) = update(id) { it.copy(name = name?.trim()?.takeIf { n -> n.isNotEmpty() }, named = !name.isNullOrBlank(), modified = System.currentTimeMillis()) }
    fun move(id: String, folder: String?) = update(id) { it.copy(folder = folder, modified = System.currentTimeMillis()) }

    /** Pages of a document, here or (remote) as described by its other device. */
    fun pageCount(d: Doc) = if (d.remote) d.pageCount else d.pages.size

    fun update(id: String, change: (Doc) -> Doc) = synchronized(lock) {
        val d = docs[id] ?: return
        writeDoc(change(d)); changed()
    }

    fun delete(id: String) = synchronized(lock) {
        docs.remove(id); dir(id).deleteRecursively(); changed()
    }

    /** Called by the OCR once it has read a revision of the document. */
    fun ocrDone(id: String, rev: Int, pages: List<String>, pdf: File?, readBy: String = "") = synchronized(lock) {
        val d = docs[id] ?: return
        if (d.rev != rev) { pdf?.delete(); return }
        atomicWrite(textFile(d), pages.joinToString(PAGE_BREAK))
        if (pdf != null) pdf.renameTo(pdfFile(d))
        val words = if (d.named) null else Naming.firstWords(pages.firstOrNull { it.isNotBlank() }.orEmpty())
        writeDoc(d.copy(ocr = OcrState.DONE, name = if (d.named) d.name else words ?: d.name, readBy = readBy))
        changed()
    }

    fun ocrFailed(id: String, rev: Int) = synchronized(lock) {
        val d = docs[id] ?: return
        if (d.rev == rev) { writeDoc(d.copy(ocr = OcrState.FAILED)); changed() }
    }

    /** Pages changed, or another language: the text and the PDF are made again. */
    fun readAgain(id: String, lang: String? = null) = synchronized(lock) {
        val d = docs[id] ?: return
        textFile(d).delete(); pdfFile(d).delete()
        writeDoc(d.copy(ocr = OcrState.PENDING, lang = lang ?: d.lang, rev = d.rev + 1, name = if (d.named) d.name else null, modified = System.currentTimeMillis()))
        changed()
    }

    fun pending(): List<String> = synchronized(lock) { docs.values.filter { it.ocr == OcrState.PENDING && !it.remote }.sortedBy { it.created }.map { it.id } }

    // --- documents from other devices (two-way sync, 1.1.0) -----------------------------------

    /** A document described by another device, new here or changed there; [text] = its pages' text. */
    fun putRemote(d: Doc, text: List<String>, dropPdf: Boolean) = synchronized(lock) {
        dir(d.id).mkdirs()
        writeDoc(d)
        atomicWrite(textFile(d), text.joinToString(PAGE_BREAK))
        if (dropPdf) pdfFile(d).delete()
        changed()
    }

    /** Its name, folder… changed on the other device (a document scanned here keeps its pages). */
    fun applyRemoteMeta(id: String, change: (Doc) -> Doc) = update(id, change)

    /** Title and text, matched without case or accents. */
    fun search(query: String): List<Pair<Doc, String?>> {
        val q = fold(query.trim())
        if (q.isEmpty()) return emptyList()
        return docs().mapNotNull { d ->
            val text = text(d).joinToString("\n")
            val folded = fold(text)
            val at = folded.indexOf(q)
            when {
                at >= 0 -> d to snippet(text, at, q.length)
                fold(title(d)).contains(q) -> d to null
                else -> null
            }
        }
    }

    private fun snippet(text: String, at: Int, len: Int): String {
        // Folding keeps one character per character for Latin and Cyrillic, so the offsets hold.
        val from = (at - 40).coerceAtLeast(0)
        val to = (at + len + 60).coerceAtMost(text.length)
        return (if (from > 0) "…" else "") + text.substring(from, to).replace(Regex("\\s+"), " ").trim() + (if (to < text.length) "…" else "")
    }

    fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
        .let { n -> buildString(s.length) { var i = 0; for (c in n) { if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) append(c.lowercaseChar()) } } }

    // --- disk ---------------------------------------------------------------------------------

    private fun writeDoc(d: Doc) {
        val pages = JSONArray()
        d.pages.forEach { p ->
            pages.put(JSONObject().put("id", p.id).put("rotation", p.rotation).put("filter", p.filter.name)
                .put("quad", JSONArray().apply { p.quad.forEach { put(it.toDouble()) } }))
        }
        val o = JSONObject().put("id", d.id).put("created", d.created).put("named", d.named)
            .put("lang", d.lang).put("ocr", d.ocr.name).put("rev", d.rev).put("readBy", d.readBy).put("pages", pages)
            .put("remote", d.remote).put("pageCount", d.pageCount).put("modified", d.modified)
        d.name?.let { o.put("name", it) }
        d.folder?.let { o.put("folder", it) }
        dir(d.id).mkdirs()
        atomicWrite(File(dir(d.id), "doc.json"), o.toString(1))
        docs[d.id] = d
    }

    private fun readDoc(dir: File): Doc? = runCatching {
        val o = JSONObject(File(dir, "doc.json").readText())
        val a = o.getJSONArray("pages")
        Doc(
            id = o.getString("id"),
            created = o.getLong("created"),
            name = o.optString("name").takeIf { o.has("name") && it.isNotBlank() },
            named = o.optBoolean("named"),
            folder = o.optString("folder").takeIf { o.has("folder") && it.isNotBlank() },
            lang = o.optString("lang", "eng"),
            pages = (0 until a.length()).map { i ->
                val p = a.getJSONObject(i)
                val q = p.getJSONArray("quad")
                Page(p.getString("id"), FloatArray(8) { q.getDouble(it).toFloat() }, p.optInt("rotation"), runCatching { Filter.valueOf(p.getString("filter")) }.getOrDefault(Filter.AUTO))
            },
            ocr = runCatching { OcrState.valueOf(o.getString("ocr")) }.getOrDefault(OcrState.PENDING),
            rev = o.optInt("rev"),
            readBy = o.optString("readBy"),
            remote = o.optBoolean("remote"),
            pageCount = o.optInt("pageCount"),
            modified = o.optLong("modified", o.getLong("created"))
        )
    }.getOrNull()

    private fun atomicWrite(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    const val PAGE_BREAK = "\u000C"
}

/**
 * The text as read, with the line breaks of the paper taken out: a line as long as the page is
 * wide goes on with the next one (a word cut by a hyphen is joined again); short lines stay as
 * they are (addresses, lists, amounts), and blank lines still separate paragraphs.
 */
object Reflow {
    fun page(text: String): String {
        val paragraphs = text.split(Regex("\\n\\s*\\n"))
        val widest = text.lineSequence().maxOfOrNull { it.trim().length } ?: 0
        return paragraphs.joinToString("\n\n") { para ->
            val lines = para.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val out = StringBuilder()
            lines.forEachIndexed { i, line ->
                out.append(line)
                if (i == lines.size - 1) return@forEachIndexed
                val next = lines[i + 1]
                val full = line.length >= widest * 0.72 && !line.endsWith(":") && !line.endsWith(".") || (line.length >= widest * 0.72 && next.firstOrNull()?.isLowerCase() == true)
                when {
                    full && line.endsWith("-") && next.firstOrNull()?.isLowerCase() == true -> out.setLength(out.length - 1)
                    full -> out.append(' ')
                    else -> out.append('\n')
                }
            }
            out.toString()
        }.trim()
    }
}

/** A folder name the server and a desktop will both accept (it is a folder on the server); null when nothing is left. */
fun folderNameOf(name: String): String? =
    name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trim('.').trim().takeIf { it.isNotEmpty() }?.take(60)

/** The name a document gets from its text when the user gave none. */
object Naming {
    private val word = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’.\\-]*")

    /**
     * The first few real words of the page: runs of letters with at least two letters, skipping
     * the debris OCR leaves around pictures and rules. Null when nothing reads as words.
     */
    fun firstWords(text: String, max: Int = 5, maxChars: Int = 40): String? {
        val words = mutableListOf<String>()
        var full = false
        for (line in text.lineSequence()) {
            val tokens = word.findAll(line).map { it.value.trim('.', '-', '\'', '’') }.toList()
            val good = tokens.filter { isWord(it) }
            // A line that is mostly debris is skipped; once words are found, it ends the title.
            if (good.isEmpty() || good.size * 2 < tokens.size) { if (words.isNotEmpty()) break else continue }
            for (t in good) {
                if (words.size >= max || (words.joinToString(" ").length + t.length + 1) > maxChars) { full = true; break }
                words.add(t)
            }
            // whole lines, until there are three words
            if (full || words.size >= 3) break
        }
        // never end on a little word ("de", "of", "и")
        while (words.size > 1 && words.last().length <= 3 && words.last().all { it.isLowerCase() }) words.removeAt(words.size - 1)
        return words.joinToString(" ").takeIf { it.isNotBlank() }
    }

    private fun isWord(t: String): Boolean {
        val letters = t.count { it.isLetter() }
        val digits = t.count { it.isDigit() }
        return (letters >= 2 && letters * 10 >= t.length * 6) || (digits >= 2 && letters == 0 && t.length <= 10)
    }
}
