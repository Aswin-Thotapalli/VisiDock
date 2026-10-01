package com.thotapalli.visidock

import org.junit.Assert.*
import org.junit.Test

class CardHistoryTest {
    @Test fun timelineCodecPreservesLegacyRecordsAndFullLengthNotes() {
        val note=DatedNote("note:with-colon","x".repeat(4000),Long.MAX_VALUE)
        val card=Card(name="A",datedNotes=listOf(note))
        assertNull(CardLogic.validate(card))
        assertEquals(listOf(note),cardFrom("a",card.record()).datedNotes)
        val legacy=mapOf("datedNotes" to listOf(mapOf("id" to "old","text" to "Saved note","createdAt" to 12L)))
        assertEquals(listOf(DatedNote("old","Saved note",12L)),cardFrom("a",legacy).datedNotes)
        assertNotNull(CardLogic.validate(card.copy(datedNotes=listOf(note,note))))
        assertNotNull(CardLogic.validate(card.copy(datedNotes=listOf(note.copy(text="x".repeat(4001))))))
        assertNotNull(CardLogic.validate(card.copy(datedNotes=listOf(note.copy(text="unsupported\u001fseparator")))))
        assertNotNull(CardLogic.validate(card.copy(datedNotes=listOf(note.copy(id="invalid\nkey")))))
    }
    @Test fun mergePreservesChannelsAndSourceWithoutOverwritingChosenIdentity() {
        val target=Card(id="a",name="Chosen name",email="one@example.com",phones=listOf(PhoneNumber("1234567890","Mobile")),notes="Target note")
        val source=Card(id="b",name="Other name",company="Company",emails=listOf("ONE@example.com","two@example.com"),phones=listOf(PhoneNumber("1234567890"),PhoneNumber("9876543210","Office")),notes="Source note",tags=listOf("Meetup"))
        val result=CardMerge.propose(target,source)
        assertEquals("a",result.id);assertEquals("Chosen name",result.name);assertEquals("Company",result.company)
        assertEquals(2,result.contactEmails.size);assertEquals(2,result.contactPhones.size)
        assertTrue(result.notes.contains("Source note"));assertEquals(listOf("Meetup"),result.tags)
        assertEquals("Other name",source.name);assertEquals(0L,source.deletedAt)
    }
    @Test fun duplicateCandidatesIncludeSecondaryEmailButNeverSelf() {
        val a=Card(id="a",name="A",emails=listOf("secondary@example.com"))
        val b=Card(id="b",name="B",email="primary@example.com",emails=listOf("SECONDARY@example.com"))
        assertEquals(listOf(b),CardLogic.duplicates(a,listOf(a,b,Card(id="c",name="C"))))
    }
    @Test fun legacyAndExtendedMetadataRoundTrip() {
        val card=Card(id="id",name="Person",emails=listOf("one@example.com","two@example.com"),websites=listOf("example.com"),
            tags=listOf("Tag"),collections=listOf("Collection"),meetingDate="2026-10-01",event="Event",location="City",
            datedNotes=listOf(DatedNote("note","Note",12)),isOwnCard=true,updatedAt=20,revision=3,deletedAt=21,transliteratedName="Person transliteration")
        val read=cardFrom(card.id,card.record())
        assertEquals(card.contactEmails,read.contactEmails);assertEquals(card.datedNotes,read.datedNotes)
        assertEquals(card.tags,read.tags);assertEquals(card.deletedAt,read.deletedAt);assertEquals(3L,read.revision)
        assertEquals(card.transliteratedName,read.transliteratedName)
        val old=cardFrom("old",mapOf("name" to "Old","email" to "old@example.com"))
        assertEquals(listOf("old@example.com"),old.contactEmails);assertEquals(0L,old.revision)
    }
    @Test fun metadataValidationRejectsInvalidDatesAndOverflowWithoutTruncation() {
        assertNotNull(CardLogic.validate(Card(name="A",meetingDate="2026-02-30")))
        assertNotNull(CardLogic.validate(Card(name="A",tags=List(25) {"tag"})))
        assertNull(CardLogic.validate(Card(name="A",meetingDate="2026-10-01",datedNotes=listOf(DatedNote("note","A note",1)))))
    }
}
