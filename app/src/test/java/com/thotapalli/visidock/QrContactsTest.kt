package com.thotapalli.visidock

import org.junit.Assert.*
import org.junit.Test

class QrContactsTest {
    @Test fun labelledPhoneNumbersSurviveQrSharing() {
        val card=Card(name="Mira",phones=listOf(PhoneNumber("+919876543210","Mobile"),PhoneNumber("04023456789","Office")))
        val parsed=QrContacts.parse(ContactExport.vcard(card)) as QrPayload.Contact
        assertEquals(card.contactPhones.map {it.number},parsed.card.contactPhones.map {it.number})
    }
    @Test fun vcardPreservesRepeatedChannelsAndDoesNotImportPrivateNotes() {
        val card=Card(name="Mira Rao",phone="+91 9876543210",phones=listOf(PhoneNumber("+91 9876543210"),PhoneNumber("040 23456789")),
            email="mira@example.com",emails=listOf("mira@example.com","office@example.com"),notes="Private meeting notes")
        val parsed=QrContacts.parse(ContactExport.vcard(card)) as QrPayload.Contact
        assertEquals("Mira Rao",parsed.card.name)
        assertEquals(2,parsed.card.contactPhones.size)
        assertEquals(2,parsed.card.contactEmails.size)
        assertTrue(parsed.card.notes.isEmpty())
        assertFalse(parsed.card.rawText.contains("Private meeting notes"))
    }
    @Test fun escapedNameCannotInjectAContactField() {
        val parsed=QrContacts.parse(ContactExport.vcard(Card(name="Mira\nTEL:+12345678901"))) as QrPayload.Contact
        assertTrue(parsed.card.contactPhones.isEmpty())
        assertTrue(parsed.card.name.contains("TEL:"))
    }
    @Test fun executableAndCredentialBearingUrlsRemainPlainPreviewText() {
        assertTrue(QrContacts.parse("javascript:alert(1)") is QrPayload.Text)
        assertTrue(QrContacts.parse("https://trusted.example@evil.example/path") is QrPayload.Text)
        assertEquals(QrPayload.Link("https://example.com/team"),QrContacts.parse("https://example.com/team"))
    }
    @Test fun incomingAttachmentsCannotReadPrivateFilesOrOurOwnProvider() {
        val own="com.thotapalli.visidock.fileprovider"
        assertFalse(IncomingImportPolicy.allowsExternal("file",null,own))
        assertFalse(IncomingImportPolicy.allowsExternal("https","example.com",own))
        assertFalse(IncomingImportPolicy.allowsExternal("content",own,own))
        assertFalse(IncomingImportPolicy.allowsExternal("content",own.uppercase(),own))
        assertFalse(IncomingImportPolicy.allowsExternal("content","user@other.provider",own))
        assertTrue(IncomingImportPolicy.allowsExternal("content","com.android.providers.media.documents",own))
    }
    @Test fun unknownLaunchDestinationCannotReplaceKnownRequest() {
        DockLaunchRequests.pending.value?.let(DockLaunchRequests::consume)
        DockLaunchRequests.publish("scan")
        DockLaunchRequests.publish("contact:private-person")
        assertEquals("scan",DockLaunchRequests.pending.value)
        DockLaunchRequests.consume("scan")
        assertNull(DockLaunchRequests.pending.value)
    }
}
