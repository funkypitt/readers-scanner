package com.freedomfighter.readersscanner

import com.freedomfighter.readersscanner.data.Doc
import com.freedomfighter.readersscanner.data.OcrState
import com.freedomfighter.readersscanner.sync.Meta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The description every device reads and writes (.readers-scanner/<id>.json). */
class MetaTest {
    private val d = Doc("abc123", 1790400000000, "Facture d'électricité", false, "f1", "fra", emptyList(), OcrState.DONE, 2, "tesseract-best", modified = 1790400999000)

    @Test fun roundTrip() {
        val m = Meta.parse(Meta.build(d, "Factures", "Factures/2026-09-26 13h53 Facture d'électricité.pdf", listOf("page un", "page deux"), 2))!!
        assertEquals("abc123", m.id); assertEquals(1790400999000, m.modified); assertEquals("Facture d'électricité", m.name)
        assertEquals("Factures", m.folder); assertEquals(2, m.pages); assertEquals(listOf("page un", "page deux"), m.text)
        assertEquals("Factures/2026-09-26 13h53 Facture d'électricité.pdf", m.pdf); assertEquals(OcrState.DONE, m.ocr)
    }

    @Test fun noNameStaysNull() {
        val m = Meta.parse(Meta.build(d.copy(name = null), "", "2026-09-26 13h53.pdf", emptyList(), 1))!!
        assertNull(m.name); assertEquals("", m.folder)
    }

    @Test fun otherJsonIsNotADescription() {
        assertNull(Meta.parse("""{"format":"readers-credentials","version":1}"""))
        assertNull(Meta.parse("not json"))
    }

    /** Written by the desktop (Python json.dump): extra fields ignored, missing ones defaulted. */
    @Test fun readsAnotherWriter() {
        val m = Meta.parse("""{"format":"readers-scanner","version":1,"id":"desk-1","created":1,"name":"Contrat","named":true,"folder":"Contrats","lang":"fra","pages":1,"text":["Contrat"],"pdf":"Contrats/x.pdf","future":42}""")!!
        assertEquals(1L, m.modified); assertEquals(OcrState.DONE, m.ocr); assertEquals("Contrats", m.folder)
    }
}
