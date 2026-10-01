package com.thotapalli.visidock

import org.junit.Assert.*
import org.junit.Test

class ContactExportTest {
    @Test fun sharingExcludesPrivateInformationAndPreservesMultipleChannels() {
        val card=Card(name="A; Person",address="Private place",notes="Private note",
            emails=listOf("a@example.org","b@example.org"),phones=listOf(PhoneNumber("1234567890"),PhoneNumber("9876543210")))
        val text=ContactExport.vcard(card)
        assertTrue(text.contains("FN:A\\; Person"))
        assertEquals(2,text.lines().count {it.startsWith("EMAIL:")})
        assertEquals(2,text.lines().count {it.startsWith("TEL:")})
        assertFalse(text.contains("Private"))
        assertTrue(ContactExport.vcard(card,ShareFields(address=true,notes=true)).contains("Private note"))
    }
    @Test fun foldingPreservesUnicodeAndOctetLimit() {
        val name="東京名刺".repeat(30)
        val text=ContactExport.vcard(Card(name=name))
        assertTrue(text.split("\r\n").all {it.toByteArray(Charsets.UTF_8).size<=75})
        assertTrue(text.replace("\r\n ","").contains("FN:$name"))
    }
    @Test fun maliciousSpreadsheetCellsAreQuotedAndNeutralized() {
        val text=ContactExport.csv(listOf(Card(name="=HYPERLINK(\"https://example.org\")",notes="secret")))
        assertTrue(text.contains("\"'=HYPERLINK("))
        assertFalse(text.contains("secret"))
    }
}
