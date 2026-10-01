package com.thotapalli.visidock

import org.junit.Assert.*
import org.junit.Test

class ContactChannelsTest {
    @Test fun completeOcrEmailSurvivesBlankSwappedAndConflictingSinglePersonResponses() {
        val evidence="Mira Sen\nEmail: mira @ example . com\nwww.example.com"
        val responses=listOf("" to "", "" to "mira@example.com", "mira@example.co" to "www.example.com")
        responses.forEach { (mail, web) ->
            val result=ContactChannels.resolve(mail,web,evidence,true)
            assertEquals("mira@example.com",result.email)
            assertEquals("www.example.com",result.website)
        }
        assertTrue(ContactChannels.resolve("mira@example.co","",evidence,true).warnings.any {"readings differed" in it})
    }
    @Test fun punctuationAndInvisibleOcrSeparatorsDoNotHidePrintedEmail() {
        assertEquals(listOf("mira@example.com"),ContactChannels.emails("Email: mira\u200B@\u200Bexample.com."))
        assertEquals(listOf("mira@example.com"),ContactChannels.emails("<mira@example.com>"))
        assertEquals("mira@example.com",ContactChannels.email("<mira@example.com>"))
        assertEquals(listOf("mira@example.com.org"),ContactChannels.emails("mira@example.com.org"))
        assertTrue(ContactChannels.emails("mira\n@example.com").isEmpty())
    }
    @Test fun conflictingOcrAddressNeverReplacesAnotherPersonsVisualAssignment() {
        val result=ContactChannels.resolve("mira@example.com","","dev@example.com",false)
        assertEquals("mira@example.com",result.email)
        val unassigned=ContactChannels.resolve("","","dev@example.com",false)
        assertEquals("",unassigned.email)
        assertTrue(unassigned.warnings.any {"owner was not confirmed" in it})
    }
    @Test fun neighbouringInitialsNeverBecomePartOfAnEmailAddress() {
        assertEquals(listOf("mira@example.com"),ContactChannels.emails("M. mira @ example . com"))
        assertEquals(listOf("www.example.com"),ContactChannels.websites("Email: mira@example.com www.example.com"))
    }
    @Test fun matchingMissingAtDoesNotBecomeAWebsiteWhenAnotherEmailWasMisplaced() {
        val result=ContactChannels.resolve("miraexample.com","dev@example.com","mira@example.com\ndev@example.com",false)
        assertEquals("mira@example.com",result.email)
        assertEquals("",result.website)
        assertTrue(result.warnings.any {"Another email" in it})
    }
    @Test fun recognisesPrintedAtVariantsAndSpacingWithoutInventingAnAt() {
        assertEquals("mira@example.com",ContactChannels.email("mira ＠ example．com"))
        assertEquals("mira@example.com",ContactChannels.emails("Email: mira @ example . com").single())
        assertNull(ContactChannels.email("miraexample.com"))
        assertNull(ContactChannels.email("mira©example.com"))
        assertNull(ContactChannels.email("mira..sen@example.com"))
    }
    @Test fun emailDomainDoesNotBecomeAWebsite() {
        assertTrue(ContactChannels.websites("Mira Sen\nmira @ example . com").isEmpty())
        assertTrue(ContactChannels.websites("Email: miraexample.com").isEmpty())
        assertTrue(ContactChannels.websites("mira©example.com\nM. K. Sharma").isEmpty())
        assertEquals(listOf("www.example.com"),ContactChannels.websites("mira@example.com\nwww.example.com"))
        assertNull(ContactChannels.website("mira@example.com"))
        assertNull(ContactChannels.website("javascript:example.com"))
    }
    @Test fun swappedChannelsAreRoutedByTheirActualFormat() {
        val result=ContactChannels.resolve("https://example.com","mira@example.com","mira@example.com\nhttps://example.com",false)
        assertEquals("mira@example.com",result.email)
        assertEquals("https://example.com",result.website)
        assertEquals(2,result.warnings.size)
    }
    @Test fun missingAtRequiresAnActualMatchingPrintedAddress() {
        val result=ContactChannels.resolve("miraexample.com","","mira@example.com",false)
        assertEquals("mira@example.com",result.email)
        assertEquals("",result.website)
        val ambiguous=ContactChannels.resolve("mira©example.com","","mira©example.com",false)
        assertEquals("",ambiguous.email)
        assertTrue(ambiguous.warnings.isNotEmpty())
    }
    @Test fun globalOcrRecoveryNeverAssignsAnotherPersonsAddress() {
        val multi=VisualExtraction.parse("""{"contacts":[{"name":"Mira"},{"name":"Dev"}]}""","Mira\nDev\ndev@example.com")
        assertTrue(multi.contacts.all {it.email.isEmpty()})
        val single=VisualExtraction.parse("""{"contacts":[{"name":"Mira"}]}""","Mira\nmira ＠ example.com")
        assertEquals("mira@example.com",single.contacts.single().email)
        val ambiguous=VisualExtraction.parse("""{"contacts":[{"name":"Mira"}]}""","Mira\nmira@example.com\nother@example.com")
        assertEquals("",ambiguous.contacts.single().email)
    }
    @Test fun fallbackAndSavedCardValidationUseSameChannelRules() {
        val card=CardLogic.extract("Mira Sen\nEmail: mira @ example.com\nWebsite: www.example.com")
        assertEquals("mira@example.com",card.email)
        assertEquals("www.example.com",card.website)
        assertNull(CardLogic.validate(card))
        assertNotNull(CardLogic.validate(card.copy(website="other@example.com")))
    }
}
