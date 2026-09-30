package com.thotapalli.visidock

import org.junit.Assert.*
import org.junit.Test

class VisualExtractionTest {
    @Test fun twoPeopleKeepTheirOwnNumbersEvenWithSharedOfficeEmail() {
        val raw = "Mira Sen\n+91 98765 43210\nDev Rao\n+91 91234 56780\nhello@example.com\nNorthline Studio"
        val result = VisualExtraction.parse("""{"contacts":[
            {"name":"Mira Sen","phone":"+91 98765 43210","email":"hello@example.com","company":"Northline Studio"},
            {"name":"Dev Rao","phone":"+91 91234 56780","email":"hello@example.com","company":"Northline Studio"}],"warnings":[]}""", raw)
        assertEquals(2, result.contacts.size)
        assertEquals("+91 98765 43210", result.contacts[0].phone)
        assertEquals("+91 91234 56780", result.contacts[1].phone)
        assertNotEquals(result.contacts[0].id, result.contacts[1].id)
        assertEquals(raw, result.contacts[1].rawText)
        assertTrue(result.warnings.isEmpty())
    }
    @Test fun unconfirmedNameIsExplicitlyFlaggedAndNeverAppendedToOcr() {
        val raw = "Northline Studio\nhello@example.com"
        val result = VisualExtraction.parse("""{"contacts":[{"name":"Aswin Thotapalli"}]}""", raw)
        assertTrue(result.warnings.any { "name" in it && "OCR did not confirm" in it })
        assertEquals(raw, result.contacts.single().rawText)
    }
    @Test fun backIsValidEvidenceForSharedAddress() {
        val result = VisualExtraction.parse("""{"contacts":[{"name":"Mira Sen","address":"12 Lake Road"}]}""", "Mira Sen", "12 Lake Road")
        assertTrue(result.warnings.isEmpty())
        assertEquals("12 Lake Road", result.contacts.single().backRawText)
    }
    @Test fun businessOnlyCanStayUnnamedForReview() {
        val result = VisualExtraction.parse("""{"contacts":[{"name":null,"company":"Northline Studio"}]}""", "Northline Studio")
        assertEquals("", result.contacts.single().name)
        assertEquals("Northline Studio", result.contacts.single().company)
    }
    @Test fun doesNotGuessAssociationWhenFieldsAreEmpty() {
        val result = VisualExtraction.parse("""{"contacts":[{"name":"Mira Sen","phone":""},{"name":"Dev Rao","phone":""}],"warnings":["Phone association is unclear"]}""", "Mira Sen Dev Rao 9876543210")
        assertTrue(result.contacts.all { it.phone.isEmpty() })
        assertEquals("Phone association is unclear", result.warnings.first())
    }
    @Test fun partialNameAndEmailDoNotCountAsGroundedEvidence() {
        val result=VisualExtraction.parse("""{"contacts":[{"name":"Ann","email":"sam@example.com"}]}""","Joanne\nasam@example.com")
        assertTrue(result.warnings.any {"check name" in it})
        assertTrue(result.warnings.any {"check email" in it})
    }
    @Test fun separatelyPrintedPhoneNumbersCanBeGroundedWithoutInventingSemicolonEvidence() {
        val result=VisualExtraction.parse("""{"contacts":[{"phone":"+91 98765 43210; +91 91234 56780"}]}""","+91 98765 43210\n+91 91234 56780")
        assertTrue(result.warnings.isEmpty())
    }
    @Test(expected = Exception::class) fun rejectsNonStringFields() {
        VisualExtraction.parse("""{"contacts":[{"name":{"value":"Mira"}}]}""", "Mira")
    }
    @Test(expected = Exception::class) fun rejectsEmptyResults() {
        VisualExtraction.parse("""{"contacts":[]}""", "")
    }
    @Test(expected = Exception::class) fun rejectsNonJsonInsteadOfSavingIt() {
        VisualExtraction.parse("Sorry, I cannot read this.", "")
    }
}
