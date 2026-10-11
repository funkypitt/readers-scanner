package com.freedomfighter.readersscanner

import com.freedomfighter.readersscanner.data.Credentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** The credentials file: named after the app, read back whatever it is called. */
class CredentialsTest {
    @Test fun exportIsNamedAfterTheApp() {
        assertEquals("readers-scanner-credentials.json", Credentials.FILE_NAME)
    }

    @Test fun ownSectionRoundTrips() {
        val got = Credentials.read(Credentials.build("https://dav.example.org", "Scans", "pierre", "s3cret"))
        assertNull(got.from)
        assertEquals("https://dav.example.org", got.account.server)
        assertEquals("Scans", got.account.folder)
        assertEquals("pierre", got.account.username)
        assertEquals("s3cret", got.account.password)
    }

    @Test fun anotherAppsServerAndLoginDoButNotItsFolder() {
        val text = """{"format":"readers-credentials","version":1,"readers-notes":{"server":"https://dav.example.org","folder":"Notes","username":"pierre","password":"s3cret"}}"""
        val got = Credentials.read(text)
        assertEquals("Reader's Notes", got.from)
        assertEquals("https://dav.example.org", got.account.server)
        assertNull(got.account.folder)
    }

    @Test fun otherFilesAreRefused() {
        assertThrows(Credentials.NotCredentials::class.java) { Credentials.read("not json") }
        assertThrows(Credentials.NotCredentials::class.java) { Credentials.read("""{"format":"something-else"}""") }
        assertThrows(Credentials.NothingForUs::class.java) { Credentials.read("""{"format":"readers-credentials","version":1,"readers-books":{"server":"x"}}""") }
    }
}
