package com.thotapalli.visidock

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SearchAndValidationTest {
    @Test fun companyFirstDoesNotBecomePerson() {
        val card=CardLogic.extract("Example Labs\nRavi Kumar\nDirector\nravi@example.com\nwww.example.com")
        assertEquals("Ravi Kumar",card.name); assertEquals("Example Labs",card.company); assertEquals("www.example.com",card.website)
    }
    @Test fun everyMeaningfulTermMustMatch() {
        val people=listOf(Card(name="Ravi",role="Blockchain consultant",notes="Conference"),Card(name="Priya",notes="Conference"))
        assertEquals(listOf("Ravi"),CardLogic.search(people,"blockchain consultant conference").map {it.name})
    }
    @Test fun synonymsWorkInBothDirections() {
        assertEquals(1,CardLogic.search(listOf(Card(name="Ravi",address="Bengaluru")),"Bangalore").size)
        assertEquals(1,CardLogic.search(listOf(Card(name="Ravi",role="Officer")),"official").size)
    }
    @Test fun monthFilterCrossesYearBoundary() {
        fun millis(date:String)=LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val cards=listOf(Card(name="December",createdAt=millis("2025-12-10")),Card(name="January",createdAt=millis("2026-01-05")))
        assertEquals("December",CardLogic.search(cards,"last month",millis("2026-01-20")).single().name)
    }
    @Test fun duplicateEmailAndInternationalPhoneAreDetected() {
        val saved=Card(id="a",name="Ravi",email="RAVI@example.com",phone="+91 98765 43210")
        assertEquals(1,CardLogic.duplicates(Card(id="b",email="ravi@example.com"),listOf(saved)).size)
        assertEquals(1,CardLogic.duplicates(Card(id="b",phone="9876543210"),listOf(saved)).size)
        assertEquals(0,CardLogic.duplicates(saved,listOf(saved)).size)
    }
    @Test fun fieldLimitsAndEmailValidation() {
        assertNotNull(CardLogic.validate(Card(name="")))
        assertNotNull(CardLogic.validate(Card(name="Ravi",email="bad@")))
        assertNotNull(CardLogic.validate(Card(name="Ravi",notes="a".repeat(4001))))
        assertNull(CardLogic.validate(Card(name="Ravi",email="ravi@example.com")))
    }
    @Test fun tokenizerHandlesSubwordsAndTruncation() {
        val tokenizer=WordPieceTokenizer(listOf("[PAD]","[UNK]","[CLS]","[SEP]","hello","world","##s","!"))
        assertArrayEquals(longArrayOf(2,4,5,6,7,3),tokenizer.encode("Héllo worlds!"))
        assertArrayEquals(longArrayOf(2,4,5,3),tokenizer.encode("hello worlds!",4))
        assertArrayEquals(longArrayOf(2,1,3),tokenizer.encode("unknown"))
    }
}
