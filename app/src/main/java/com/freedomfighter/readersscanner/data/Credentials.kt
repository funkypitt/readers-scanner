package com.freedomfighter.readersscanner.data

import org.json.JSONObject

/**
 * The Reader's credentials file, the same on the phone and on the desktop: one JSON file,
 * `{"format": "readers-credentials", "version": 1, "<app>": {…}}`, one section per app. Carrying it
 * to another phone or to another Reader's app sets the WebDAV folder up in one step. Only
 * our section and only the keys we know are read; the look is never in it. It holds the password.
 */
object Credentials {
    const val FORMAT = "readers-credentials"
    const val SECTION = "readers-scanner"
    /** Apps whose WebDAV server and login do for us too (never their folder: that one is theirs). */
    val SAME_KIND = listOf("readers-notes" to "Reader's Notes", "readers-recorder" to "Reader's Recorder")
    /** Apps with the same login but a CalDAV address, useless here: only the login is taken. */
    val LOGIN_ONLY = listOf("readers-tasks" to "Reader's Tasks", "readers-calendar" to "Reader's Calendar")
    const val FILE_NAME = "readers-credentials-scanner.json"

    /** What a file brings; null = not in the file, keep what is set. */
    data class Account(val server: String?, val folder: String?, val username: String?, val password: String?)
    /** [from]: the other app it was taken from, null when it is our own section. */
    data class Imported(val account: Account, val from: String?) { val fromFallback get() = from != null }

    class NotCredentials : Exception("not a Reader's credentials file")
    class NothingForUs : Exception("this file holds nothing for $SECTION")

    fun build(server: String, folder: String, username: String, password: String): String {
        val section = JSONObject()
        if (server.isNotBlank()) section.put("server", server)
        if (folder.isNotBlank()) section.put("folder", folder)
        if (username.isNotBlank()) section.put("username", username)
        if (password.isNotEmpty()) section.put("password", password)
        return JSONObject().put("format", FORMAT).put("version", 1).put(SECTION, section).toString(2)
    }

    fun read(text: String): Imported {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: throw NotCredentials()
        if (root.optString("format") != FORMAT) throw NotCredentials()
        root.optJSONObject(SECTION)?.let { s ->
            val a = Account(s.str("server"), s.str("folder"), s.str("username"), s.str("password"))
            if (a != Account(null, null, null, null)) return Imported(a, null)
        }
        for ((key, name) in SAME_KIND) root.optJSONObject(key)?.let { s ->
            val a = Account(s.str("server"), null, s.str("username"), s.str("password"))
            if (a.server != null) return Imported(a, name)
        }
        for ((key, name) in LOGIN_ONLY) root.optJSONObject(key)?.let { s ->
            val a = Account(null, null, s.str("username"), s.str("password"))
            if (a.username != null) return Imported(a, name)
        }
        throw NothingForUs()
    }

    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) (opt(key) as? String) else null
}
