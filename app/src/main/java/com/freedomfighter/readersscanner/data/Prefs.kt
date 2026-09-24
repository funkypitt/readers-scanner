package com.freedomfighter.readersscanner.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { DARK, LIGHT, SYSTEM }
enum class FontChoice { SERIF, SANS, MONO }
enum class TextSize { SMALL, MEDIUM, LARGE }
enum class Align { LEFT, CENTER }

data class Settings(
    val theme: ThemeMode = ThemeMode.DARK,
    val font: FontChoice = FontChoice.SANS,
    val textSize: TextSize = TextSize.MEDIUM,
    val align: Align = Align.LEFT,
    val haptics: Boolean = true,
    /** The language the text of the next scan is read in (a Tesseract code). */
    val ocrLanguage: String = OcrLanguages.default(),
    /** What a new page looks like until changed in the review. */
    val filter: Filter = Filter.AUTO,
    /** WebDAV account: server root (kDrive: https://<id>.connect.kdrive.infomaniak.com), folder path, credentials. */
    val server: String = "",
    val folder: String = "Scans",
    val username: String = "",
    val password: String = "",
    val syncOnOpen: Boolean = true
) {
    val configured: Boolean get() = server.isNotBlank()
    /** The folder URL, always ending with "/". */
    val folderUrl: String get() = server.trim().trimEnd('/') + "/" + folder.trim().trim('/').split("/").joinToString("/") { encodeSegment(it) } + "/"
}

fun encodeSegment(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%2F", "/")

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _settings.value = read() }
    init { sp.registerOnSharedPreferenceChangeListener(listener) }

    private fun read() = Settings(
        theme = enumOr(sp.getString("theme", null), ThemeMode.DARK),
        font = enumOr(sp.getString("font", null), FontChoice.SANS),
        textSize = enumOr(sp.getString("text_size", null), TextSize.MEDIUM),
        align = enumOr(sp.getString("align", null), Align.LEFT),
        haptics = sp.getBoolean("haptics", true),
        ocrLanguage = sp.getString("ocr_language", null)?.takeIf { OcrLanguages.codes.contains(it) } ?: OcrLanguages.default(),
        filter = enumOr(sp.getString("filter", null), Filter.AUTO),
        server = sp.getString("server", "") ?: "",
        folder = sp.getString("folder", "Scans") ?: "Scans",
        username = sp.getString("username", "") ?: "",
        password = sp.getString("password", "") ?: "",
        syncOnOpen = sp.getBoolean("sync_on_open", true)
    )
    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    fun setTheme(m: ThemeMode) = sp.edit().putString("theme", m.name).apply()
    fun setFont(f: FontChoice) = sp.edit().putString("font", f.name).apply()
    fun setTextSize(t: TextSize) = sp.edit().putString("text_size", t.name).apply()
    fun setAlign(a: Align) = sp.edit().putString("align", a.name).apply()
    fun setHaptics(v: Boolean) = sp.edit().putBoolean("haptics", v).apply()
    fun setOcrLanguage(code: String) = sp.edit().putString("ocr_language", code).apply()
    fun setFilter(f: Filter) = sp.edit().putString("filter", f.name).apply()
    fun setAccount(server: String, folder: String, username: String, password: String) =
        sp.edit().putString("server", server.trim()).putString("folder", folder.trim().ifBlank { "Scans" }).putString("username", username.trim()).putString("password", password).apply()
    fun setSyncOnOpen(v: Boolean) = sp.edit().putBoolean("sync_on_open", v).apply()
    fun toggleTheme(systemIsDark: Boolean) {
        val dark = when (_settings.value.theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> systemIsDark }
        setTheme(if (dark) ThemeMode.LIGHT else ThemeMode.DARK)
    }
}

/** The languages whose Tesseract models ship inside the app (tessdata_fast). */
object OcrLanguages {
    val codes = listOf("eng", "fra", "deu", "ita", "spa", "por", "rus")
    /** Short label shown on the capture screen. */
    fun short(code: String) = when (code) {
        "eng" -> "EN"; "fra" -> "FR"; "deu" -> "DE"; "ita" -> "IT"; "spa" -> "ES"; "por" -> "PT"; "rus" -> "RU"; else -> code.uppercase()
    }
    /** Each language in its own words. */
    fun name(code: String) = when (code) {
        "eng" -> "English"; "fra" -> "français"; "deu" -> "Deutsch"; "ita" -> "italiano"; "spa" -> "español"; "por" -> "português"; "rus" -> "русский"; else -> code
    }
    fun default(): String = when (java.util.Locale.getDefault().language) {
        "fr" -> "fra"; "de" -> "deu"; "it" -> "ita"; "es" -> "spa"; "pt" -> "por"; "ru" -> "rus"; else -> "eng"
    }
}
