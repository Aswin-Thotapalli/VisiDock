package com.thotapalli.visidock

import org.junit.Assert.*
import org.junit.Test

class CardLogicTest {
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
