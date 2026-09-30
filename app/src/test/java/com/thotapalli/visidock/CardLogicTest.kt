package com.thotapalli.visidock

import org.junit.Assert.*
import org.junit.Test

class CardLogicTest {
    @Test fun preservesThreeIndependentPhoneNumbers() {
        val card = CardLogic.extract("Mira Sen\nExample Labs\n040 23456789; 040 23456790\n+91 98765 43210")
        assertEquals(3, card.contactPhones.size)
        assertEquals(1, CardLogic.search(listOf(card), "23456790").size)
        assertEquals(1, CardLogic.duplicates(card.copy(id="new"), listOf(Card(id="old", phone="040 23456790"))).size)
        assertNotNull(CardLogic.validate(card.copy(phones=listOf(PhoneNumber("12345678; 87654321")))))
    }
    @Test fun secondaryPhoneCorrectionsUseOnlyPrintedEvidence() {
        val proposed = Card(name="Mira", rawText="Mira\n040 23456789\n040 23456790", phones=listOf(PhoneNumber("040 23456789")))
        val corrected = proposed.copy(phones=proposed.phones + PhoneNumber("040 23456790"))
        assertEquals(listOf("040 23456790"), CorrectionPolicy.learn(proposed, corrected).map { it.phrase })
        assertTrue(CorrectionPolicy.learn(proposed, proposed.copy(phones=listOf(PhoneNumber("99999999")))).isEmpty())
        assertTrue(CorrectionPolicy.learn(proposed, corrected.copy(name="040 23456790")).none { it.field=="name" })
        assertEquals(listOf(PhoneNumber("04023456789/90")), splitPhoneNumbers("04023456789/90"))
    }
    @Test fun companyOnlyCardKeepsPersonNameEmpty() {
        val card=Card(company="Northline Studio")
        assertNull(CardLogic.validate(card))
        assertEquals("",card.name)
        assertEquals("Northline Studio",card.displayLabel)
        assertNotNull(CardLogic.validate(Card()))
    }
    @Test fun extractsEmailAndPhone() {
        val card = CardLogic.extract("Ravi Kumar\nDirector\nExample Labs\nravi@example.com\n+91 98765 43210")
        assertEquals("ravi@example.com", card.email)
        assertEquals("Ravi Kumar", card.name)
        assertTrue(card.phone.contains("98765"))
    }
    @Test fun naturalSearchIncludesNotesAndRole() {
        val cards = listOf(Card(name="Ravi", role="Government Officer", notes="Met at conference"), Card(name="Priya", company="Retail"))
        assertEquals("Ravi", CardLogic.search(cards, "find the government official I met").first().name)
    }
}
