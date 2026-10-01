package com.thotapalli.visidock

import org.junit.Assert.*
import org.junit.Test

class VisualExtractionTest {
    @Test fun printedDetailAlternativePreventsAddressSuffixDeletionWithoutInventingAnAddress() {
        val street="12 Lake Road, Bengaluru 560001"
        val primary=OcrObservation(OcrRegion(street,.1f,.7f,.9f,.8f))
        val alternative=primary.copy(region=primary.region.copy(text="$street, India"),pass="detail")
        val evidence=OcrEvidence(listOf(primary),listOf(OcrDisagreement(primary,alternative)))
        fun read(address:String,ocrEvidence:OcrEvidence)=VisualExtraction.parse(org.json.JSONObject().put("contacts",
            org.json.JSONArray().put(org.json.JSONObject().put("name","Mira Sen").put("address",address))).toString(),
            "Mira Sen\n$street",ocrEvidence=ocrEvidence)
        assertEquals("$street, India",read("$street, India",evidence).contacts.single().address)
        assertTrue(read("$street, India",evidence).warnings.any {"differed between OCR" in it})
        // Without the printed alternative, the original hallucinated-country guard still applies.
        assertEquals(street,read("$street, India",OcrEvidence()).contacts.single().address)
        // Alternatives never fill a blank or silently append a suffix themselves.
        assertEquals("",read("",evidence).contacts.single().address)
        assertEquals(street,read(street,evidence).contacts.single().address)
        assertEquals(street,read("$street, Atlantis",evidence).contacts.single().address)
    }
    @Test fun unprintedCountryCompletionIsRemovedButPrintedMultilineAddressIsPreserved() {
        fun read(address:String,ocr:String)=VisualExtraction.parse(org.json.JSONObject().put("contacts",
            org.json.JSONArray().put(org.json.JSONObject().put("name","Mira Sen").put("address",address))).toString(),"Mira Sen",ocr)
        val street="12 Lake Road, Bengaluru 560001"
        val completed=read("$street, India",street)
        assertEquals(street,completed.contacts.single().address)
        assertTrue(completed.warnings.any {"unprinted address ending" in it})
        val multiline="Building 7\n12 Lake Road\nBengaluru 560001\nIndia"
        assertEquals(multiline,read(multiline,multiline).contacts.single().address)
        assertEquals("$street, India",read("$street, India","12 Lake Road\nBengaluru 560001\nIndia").contacts.single().address)
        // Missing OCR lines must not cause us to trim printed trailing components.
        val partialOcr="12 Lake Road\nIndia"
        assertEquals("$street, India",read("$street, India",partialOcr).contacts.single().address)
        // Never join scattered fragments to claim that an address is grounded.
        assertEquals("$street, India",read("$street, India","12 Lake Road\nother office\nBengaluru 560001").contacts.single().address)
    }
    @Test fun retainsThreeSeparatelyLabelledNumbersAndOnlyOneLegacyPrimary() {
        val raw="Mira Sen\nOffice: 040 2345 6789\nDirect: 040 2345 6790\nMobile: +91 98765 43210"
        val result=VisualExtraction.parse("""{"contacts":[{"name":"Mira Sen","phones":[{"number":"040 2345 6789","label":"Office"},{"number":"040 2345 6790","label":"Direct"},{"number":"+91 98765 43210","label":"Mobile"}]}]}""",raw)
        assertEquals(listOf("040 2345 6789","040 2345 6790","+91 98765 43210"),result.contacts.single().phones.map {it.number})
        assertEquals(listOf("Office","Direct","Mobile"),result.contacts.single().phones.map {it.label})
        assertEquals("040 2345 6789",result.contacts.single().phone)
        assertTrue(result.warnings.isEmpty())
    }
    @Test fun unprintedPhoneTypeIsRemovedAndLegacyDuplicateDoesNotLosePrintedLabel() {
        val result=VisualExtraction.parse("""{"contacts":[{"phones":[{"number":"9876543210","label":"Mobile"},{"number":"04023456789","label":"Office"}],"phone":"04023456789"}]}""","9876543210\nOffice: 04023456789")
        assertEquals(2,result.contacts.single().phones.size)
        assertEquals("",result.contacts.single().phones.first().label)
        assertEquals("Office",result.contacts.single().phones.last().label)
        assertTrue(result.warnings.any {"label" in it})
    }
    @Test fun structuredPhoneListsDoNotLeakBetweenPeople() {
        val result=VisualExtraction.parse("""{"contacts":[{"name":"Mira Sen","phones":[{"number":"04023456789"},{"number":"9876543210"}]},{"name":"Dev Rao","phones":[{"number":"9123456780"}]}]}""","Mira Sen\n04023456789\n9876543210\nDev Rao\n9123456780")
        assertEquals(listOf("04023456789","9876543210"),result.contacts[0].phones.map {it.number})
        assertEquals(listOf("9123456780"),result.contacts[1].phones.map {it.number})
    }
    @Test fun retainsCompleteMultilinePostalAddressWithoutCompletingMissingDetails() {
        val address="Building 7\nLake Road\nPO Box 123\nMadhapur\nHyderabad 500081"
        val json=org.json.JSONObject().put("contacts",org.json.JSONArray().put(org.json.JSONObject().put("name","Mira Sen").put("address",address)))
        val result=VisualExtraction.parse(json.toString(),"Mira Sen",address)
        assertEquals(address,result.contacts.single().address)
        assertTrue(result.warnings.isEmpty())
        val empty=VisualExtraction.parse("""{"contacts":[{"name":"Mira Sen"},{"name":"Dev Rao"}]}""","Mira Sen\nDev Rao\n$address")
        assertTrue(empty.contacts.all {it.address.isEmpty()})
    }
    @Test fun boundedOcrKeepsFooterAndHeaderWithoutExceedingBudget() {
        val raw="Mira Sen\n"+"middle ".repeat(500)+"\nPO Box 123\nHyderabad 500081"
        val result=VisualExtraction.boundedOcr(raw,1000)
        assertEquals(1000,result.length)
        assertTrue(result.startsWith("Mira Sen"))
        assertTrue(result.endsWith("Hyderabad 500081"))
        assertEquals("short",VisualExtraction.boundedOcr("short",1000))
    }
    @Test fun completionWaitsForOuterObjectAcrossEveryChunkBoundary() {
        val json="""{"contacts":[{"name":"Mira {Sen}","address":"Suite \\\"A\\\" [East]"},{"name":"Dev Rao"}],"warnings":["review"]}"""
        for(length in 0 until json.length) assertNull("Incomplete at $length",VisualExtraction.completedJson(json.take(length)))
        assertEquals(json,VisualExtraction.completedJson("```json\n$json\n``` extra"))
        assertNull(VisualExtraction.completedJson("{\"other\":[]}"))
    }
    @Test(expected=Exception::class) fun rejectsOversizedPhoneLabel() {
        VisualExtraction.parse("""{"contacts":[{"phones":[{"number":"9876543210","label":"${"x".repeat(41)}"}]}]}""","9876543210")
    }
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
