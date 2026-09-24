package com.freedomfighter.readersscanner

import com.freedomfighter.readersscanner.data.Credentials
import com.freedomfighter.readersscanner.data.Naming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NamingTest {
    @Test fun firstWordsOfALetter() = assertEquals("Swisscom SA Facture septembre 2026", Naming.firstWords("Swisscom SA\nFacture septembre 2026\nMonsieur,"))
    @Test fun skipsDebrisLines() = assertEquals("Assurance maladie Décompte", Naming.firstWords("| ~ — ! . ,\n~~ i |\nAssurance maladie\nDécompte de prestations"))
    @Test fun stopsAtFiveWords() = assertEquals("The quick brown fox jumps", Naming.firstWords("The quick brown fox jumps over the lazy dog"))
    @Test fun keepsShortTotal() = assertEquals(true, (Naming.firstWords("Konsumentenkreditvertragsbestimmungen Allgemeine Geschäftsbedingungen der Bank")?.length ?: 0) <= 40)
    @Test fun nothingReadable() = assertNull(Naming.firstWords("| | ~ . , ; ' \n  \n"))
    @Test fun cyrillic() = assertEquals("Договор аренды квартиры", Naming.firstWords("Договор аренды квартиры\n№ 12"))
    @Test fun numbersAfterWords() = assertEquals("Rechnung Nr 2026-114", Naming.firstWords("Rechnung Nr. 2026-114\nBetrag"))
}

class CredentialsTest {
    @Test fun ownSection() {
        val t = Credentials.build("https://1.connect.kdrive.infomaniak.com", "Scans", "me", "pw")
        val a = Credentials.read(t)
        assertEquals(null, a.from); assertEquals("Scans", a.account.folder); assertEquals("pw", a.account.password)
    }
    @Test fun fromNotesKeepsOurFolder() {
        val a = Credentials.read("""{"format":"readers-credentials","version":1,"readers-notes":{"server":"https://s","folder":"Notes","username":"u","password":"p"}}""")
        assertEquals("Reader's Notes", a.from); assertEquals("https://s", a.account.server); assertNull(a.account.folder)
    }
    @Test fun fromTasksLoginOnly() {
        val a = Credentials.read("""{"format":"readers-credentials","version":1,"readers-tasks":{"url":"https://sync.infomaniak.com/calendars/x/","username":"u","password":"p"}}""")
        assertEquals("Reader's Tasks", a.from); assertNull(a.account.server); assertEquals("u", a.account.username)
    }
    @Test(expected = Credentials.NothingForUs::class) fun nothing() { Credentials.read("""{"format":"readers-credentials","version":1,"readers-feeds":{}}""") }
    @Test(expected = Credentials.NotCredentials::class) fun notAFile() { Credentials.read("hello") }
}

class ReflowTest {
    private val letter = """Nous vous remercions de votre confiance. Voici le
décompte de votre consommation pour la période du
ler juillet au 31 août 2026.

Consommation totale : 412 kWh
Montant hors taxes : 98,40 CHF

Le paiement est dû dans les trente jours. Pour toute
question, notre service clients répond du lundi au
vendredi, de 8 h à 17 h."""

    @Test fun joinsProseKeepsLists() {
        val r = com.freedomfighter.readersscanner.data.Reflow.page(letter)
        assertEquals("""Nous vous remercions de votre confiance. Voici le décompte de votre consommation pour la période du ler juillet au 31 août 2026.

Consommation totale : 412 kWh
Montant hors taxes : 98,40 CHF

Le paiement est dû dans les trente jours. Pour toute question, notre service clients répond du lundi au vendredi, de 8 h à 17 h.""", r)
    }

    @Test fun hyphenJoined() = assertEquals("une consommation électrique annuelle raisonnable pour la saison",
        com.freedomfighter.readersscanner.data.Reflow.page("une consommation électrique annuelle raison-\nnable pour la saison"))
}
