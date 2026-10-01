package com.thotapalli.visidock

import org.junit.Assert.*
import org.junit.Test

class QrReconciliationTest {
    @Test fun noSelectionIsAnExactNoOpEvenWithAnotherPersonsQr() {
        val current=Card(id="a",name="Asha",email="asha@example.com",notes="Private",sourceScanId="scan")
        assertSame(current,QrReconciliation.apply(current,Card(name="Mira",email="mira@example.com"),emptySet(),false))
    }
    @Test fun differentPersonCannotBeAppliedWithoutExplicitConfirmation() {
        assertThrows(IllegalArgumentException::class.java) {
            QrReconciliation.apply(Card(name="Asha"),Card(name="Mira"),setOf(QrField.NAME),false)
        }
    }
    @Test fun selectedFieldOnlyPreservesUserEditsAndCardIdentity() {
        val current=Card(id="card",name="Corrected name",role="Edited title",company="Company",notes="Private",imagePath="front",sourceScanId="scan",rawText="OCR",isOwnCard=true)
        val qr=Card(name="Other name",role="Wrong title",address="12 Main Road",notes="QR notes",rawText="QR text")
        assertEquals(current.copy(address="12 Main Road"),QrReconciliation.apply(current,qr,setOf(QrField.ADDRESS),true))
    }
    @Test fun repeatedChannelsAreExplicitlyUnionedWithoutChangingPrimaryOrExistingLabels() {
        val current=Card(name="Asha",email="asha@example.com",website="https://example.com",phone="+91 9876543210",phones=listOf(PhoneNumber("+91 9876543210","Mobile")))
        val qr=Card(emails=listOf("ASHA@example.com","office@example.com"),websites=listOf("https://example.com","https://office.example.com"),phones=listOf(PhoneNumber("+919876543210","QR"),PhoneNumber("080 23456789","Office")))
        val merged=QrReconciliation.apply(current,qr,setOf(QrField.EMAILS,QrField.WEBSITES,QrField.PHONES),true)
        assertEquals(current.name,merged.name);assertEquals(current.phone,merged.phone);assertEquals(current.email,merged.email);assertEquals(current.website,merged.website)
        assertEquals(listOf("asha@example.com","office@example.com"),merged.contactEmails)
        assertEquals(2,merged.contactWebsites.size);assertEquals(2,merged.contactPhones.size)
        assertEquals("Mobile",merged.contactPhones.first().label)
    }
    @Test fun channelOverflowIsRejectedWithoutTruncatingExistingData() {
        val current=Card(name="Asha",emails=(1..12).map {"person$it@example.com"})
        assertThrows(IllegalArgumentException::class.java) {
            QrReconciliation.apply(current,Card(email="new@example.com"),setOf(QrField.EMAILS),true)
        }
        assertEquals(12,current.contactEmails.size)
    }
    @Test fun invalidOrOversizedSelectedValuesAreRejected() {
        val current=Card(name="Asha")
        listOf(
            QrField.NAME to Card(name="x".repeat(201)),
            QrField.ROLE to Card(role="x".repeat(301)),
            QrField.ADDRESS to Card(address="x".repeat(1001)),
            QrField.EMAILS to Card(email="https://example.com"),
            QrField.WEBSITES to Card(website="asha@example.com"),
            QrField.PHONES to Card(phones=listOf(PhoneNumber("123")))
        ).forEach {(field,qr)->assertThrows(IllegalArgumentException::class.java) {QrReconciliation.apply(current,qr,setOf(field),true)}}
    }
    @Test fun emptyQrFieldsNeverSuggestErasingCurrentInformation() {
        val current=Card(name="Asha",role="Engineer",email="asha@example.com")
        val differences=QrReconciliation.differences(current,Card(name="Asha",company="Example"))
        assertEquals(listOf(QrField.COMPANY),differences.map {it.field})
    }
}
