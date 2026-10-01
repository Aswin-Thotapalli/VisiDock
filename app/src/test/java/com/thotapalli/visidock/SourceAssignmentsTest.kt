package com.thotapalli.visidock

import org.junit.Assert.*
import org.junit.Test

class SourceAssignmentsTest {
    private fun evidence(vararg texts: String) = OcrEvidence(texts.mapIndexed { i, text ->
        OcrObservation(OcrRegion(text, .1f, .05f+i*.08f, .8f, .1f+i*.08f), .98f)
    })
    private fun read(json: String, evidence: OcrEvidence): VisualProposal = VisualExtraction.parse(json,
        evidence.lines.filter { it.region.side == 0 }.joinToString("\n") { it.region.text },
        evidence.lines.filter { it.region.side == 1 }.joinToString("\n") { it.region.text }, evidence)

    @Test fun explicitNameAssignmentFillsOmittedNameWithoutGuessingFromLineOrder() {
        val result = read("""{"contacts":[{"role":"Design Director","sources":{"name":["F2"],"role":["F1"]}}]}""",
            evidence("Design Director", "Mira Sen", "Northline Studio"))
        assertEquals("Mira Sen", result.contacts.single().name)
        assertEquals("Design Director", result.contacts.single().role)
        assertTrue(result.reviewIssues.isEmpty())
        val source = result.sources.single { it.field == "name" }
        assertEquals(listOf("F2"), source.regionIds)
        assertTrue(source.modelAssigned)
    }

    @Test fun omissionsWithoutAssignmentRequestReviewInsteadOfPickingFirstHeading() {
        val result = read("""{"contacts":[{"company":"Northline Studio"}]}""",
            evidence("Northline Studio", "Mira Sen", "Design Director"))
        assertEquals("", result.contacts.single().name)
        assertTrue(result.reviewIssues.any { it.field == "name" })
        assertTrue(SourceAssignments.repairPrompt(result)!!.contains("F2"))
    }

    @Test fun designationAssignedAsBothNameAndRoleIsHeldForContextualReview() {
        val result = read("""{"contacts":[{"name":"Design Director","role":"Design Director","sources":{"name":["F1"],"role":["F1"]}}]}""",
            evidence("Design Director", "Mira Sen"))
        assertEquals("", result.contacts.single().name)
        assertEquals("Design Director", result.contacts.single().role)
        assertTrue(result.reviewIssues.any { it.reason.contains("both a name") })
        assertEquals("", result.sources.single { it.field == "name" }.value)
    }

    @Test fun twoPeopleKeepDistinctSourceOwnershipAndSharedCompany() {
        val result = read("""{"contacts":[
            {"sources":{"name":["F2"],"company":["F1"]}},
            {"sources":{"name":["F3"],"company":["F1"]}}]}""",
            evidence("Northline Studio", "Mira Sen", "Dev Shah"))
        assertEquals(listOf("Mira Sen", "Dev Shah"), result.contacts.map { it.name })
        assertTrue(result.contacts.all { it.company == "Northline Studio" })
        assertTrue(result.reviewIssues.isEmpty())
    }

    @Test fun sameNameRegionCannotSilentlyBecomeTwoPeople() {
        val result = read("""{"contacts":[{"sources":{"name":["F1"]}},{"sources":{"name":["F1"]}}]}""",
            evidence("Mira Sen"))
        assertTrue(result.contacts.all { it.name.isBlank() })
        assertEquals(2, result.reviewIssues.count { it.reason.contains("more than one person") })
    }

    @Test fun fakeSourceDoesNotRecoverNameFromSomeOtherRegion() {
        val result = read("""{"contacts":[{"sources":{"name":["F99"]}}]}""", evidence("Mira Sen"))
        assertEquals("", result.contacts.single().name)
        assertTrue(result.reviewIssues.any { it.reason.contains("not in this scan") })
    }

    @Test fun assignedBackAddressRetainsEveryLineAndSide() {
        val input = OcrEvidence.combine(evidence("Mira Sen"), evidence("Building 7", "12 Lake Road", "Bengaluru 560001"))
        val result = read("""{"contacts":[{"sources":{"name":["F1"],"address":["B1","B2","B3"]}}]}""", input)
        assertEquals("Building 7\n12 Lake Road\nBengaluru 560001", result.contacts.single().address)
        assertTrue(result.sources.single { it.field == "address" }.regions.all { it.side == 1 })
    }

    @Test fun companyOnlyCardDoesNotCauseUnnecessaryPersonRepair() {
        val result = read("""{"contacts":[{"kind":"company","sources":{"company":["F1"]}}]}""", evidence("Northline Studio", "Ideas for tomorrow"))
        assertTrue(result.reviewIssues.isEmpty())
        assertNull(SourceAssignments.repairPrompt(result))
    }

    @Test fun correctivePassCannotDiscardChannelsOrPeople() {
        val issue = ExtractionReviewIssue(0, "name", "Missing name")
        val first = VisualProposal(listOf(Card(phone="9876543210")), emptyList(), reviewIssues=listOf(issue))
        val destructive = VisualProposal(listOf(Card(name="Mira Sen")), emptyList())
        assertEquals(first.contacts, SourceAssignments.preferRepair(first, destructive).contacts)
        val correct = VisualProposal(listOf(Card(name="Mira Sen", phone="9876543210")), emptyList())
        assertEquals(correct, SourceAssignments.preferRepair(first, correct))
    }

    @Test fun regionIdsRemainStableAcrossConfidenceAndTwoSides() {
        val source = OcrEvidence.combine(evidence("Mira Sen", "Design Director"), evidence("12 Lake Road"))
        assertEquals(listOf("F1", "F2", "B1"), source.sourceRegions().keys.toList())
        assertEquals(source.sourceRegions(), OcrEvidence.decode(source.encode()).sourceRegions())
        assertTrue(source.modelContext().contains("\"id\":\"B1\""))
    }

    @Test fun omittedPostalBlockRequiresInterpretationInsteadOfSilentlyDisappearing() {
        val result = read("""{"contacts":[{"sources":{"name":["F1"]}}]}""", evidence("Mira Sen",
            "Building 7, Industrial Estate, East Entrance", "Lake Road, Bengaluru 560001"))
        assertEquals("", result.contacts.single().address)
        assertTrue(result.reviewIssues.any { it.field == "unassigned" && it.sourceIds == listOf("F2", "F3") })
    }

    @Test fun explicitNonContactRegionExplanationAvoidsUnnecessaryRepair() {
        val result = read("""{"contacts":[{"sources":{"name":["F1"]}}],"ignoredSources":{"F2":"slogan","F3":"legal footer"}}""",
            evidence("Mira Sen", "Creating possibilities for a better tomorrow", "A registered trademark of our parent organisation"))
        assertNull(SourceAssignments.repairPrompt(result))
    }

    @Test fun repeatableChannelsRecoverFromAssignedRegionsWithoutLosingOwners() {
        val result = read("""{"contacts":[
            {"sources":{"name":["F1"],"emails.0":["F2"],"emails.1":["F3"]}},
            {"sources":{"name":["F4"],"emails.0":["F5"]}}]}""",
            evidence("Mira Sen", "mira@example.com", "mira@studio.example", "Dev Shah", "dev@example.com"))
        assertEquals(listOf("mira@example.com", "mira@studio.example"), result.contacts[0].contactEmails)
        assertEquals(listOf("dev@example.com"), result.contacts[1].contactEmails)
        assertTrue(result.sources.any { it.field == "emails.1" && it.regionIds == listOf("F3") })
    }

    @Test fun singlePersonRetainsAllPrintedChannelsAndSeparatesMisclassifiedValues() {
        val result = VisualExtraction.parse("""{"contacts":[{"name":"Mira Sen","emails":["www.example.com"],"websites":["mira@example.com"]}]}""",
            "Mira Sen\nmira@example.com\nstudio@example.com\nwww.example.com\nwww.studio.example")
        assertEquals(setOf("mira@example.com", "studio@example.com"), result.contacts.single().contactEmails.toSet())
        assertEquals(setOf("www.example.com", "www.studio.example"), result.contacts.single().contactWebsites.toSet())
    }

    @Test fun conflictingOcrIsNotAutomaticallyUsedToFillAnOmittedField() {
        val source = evidence("Mira Sen")
        val disputed = source.merge(listOf(source.lines.single().copy(region=source.lines.single().region.copy(text="Nira Sen"),pass="detail")))
        val result = read("""{"contacts":[{"sources":{"name":["F1"]}}]}""", disputed)
        assertEquals("", result.contacts.single().name)
        assertTrue(result.reviewIssues.any { it.reason.contains("conflicting OCR") })
    }

    @Test fun swappedChannelArraysStillExposeCorrectBaseFieldSource() {
        val result = read("""{"contacts":[{"name":"Mira Sen","emails":["example.com"],"websites":["mira@example.com"],"sources":{"emails.0":["F2"],"websites.0":["F3"]}}]}""",
            evidence("Mira Sen", "example.com", "mira@example.com"))
        assertEquals(listOf("F3"), result.sources.single { it.field == "email" }.regionIds)
        assertEquals(listOf("F2"), result.sources.single { it.field == "website" }.regionIds)
    }

    @Test fun repairCannotErasePreviouslyReadAddress() {
        val first = VisualProposal(listOf(Card(address="12 Lake Road")), emptyList(),
            reviewIssues=listOf(ExtractionReviewIssue(0,"name","missing")))
        val repaired = VisualProposal(listOf(Card(name="Mira Sen")), emptyList())
        assertEquals(first.contacts, SourceAssignments.preferRepair(first,repaired).contacts)
    }
    @Test fun omittedSecondPersonChannelsRequestOwnershipReviewEvenWhenIgnoredAsFooter() {
        val input=evidence("Mira Sen","Design Lead","mira@x.co","9876543210","Dev Rao","Project Lead","dev@x.co","9123456780")
        val first=read("""{"contacts":[{"name":"Mira Sen","role":"Design Lead","emails":["mira@x.co"],"phones":[{"number":"9876543210"}],"sources":{"name":["F1"],"role":["F2"],"emails.0":["F3"]}}],"ignoredSources":{"F5":"footer","F6":"slogan","F7":"footer","F8":"footer"}}""",input)
        assertEquals(1,first.contacts.size)
        assertEquals(listOf("mira@x.co"),first.contacts.single().contactEmails)
        assertEquals(listOf("9876543210"),first.contacts.single().contactPhones.map {it.number})
        val ownership=first.reviewIssues.single {it.field=="ownership"}
        assertEquals(setOf("F7","F8"),ownership.sourceIds.toSet())
        assertTrue(SourceAssignments.repairPrompt(first)!!.contains("ownership"))
        val revised=read("""{"contacts":[{"name":"Mira Sen","role":"Design Lead","emails":["mira@x.co"],"phones":[{"number":"9876543210"}],"sources":{"name":["F1"],"role":["F2"],"emails.0":["F3"]}},{"name":"Dev Rao","role":"Project Lead","emails":["dev@x.co"],"phones":[{"number":"9123456780"}],"sources":{"name":["F5"],"role":["F6"],"emails.0":["F7"]}}]}""",input)
        assertTrue(revised.reviewIssues.isEmpty())
        val accepted=SourceAssignments.preferRepair(first,revised)
        assertEquals(2,accepted.contacts.size)
        assertEquals(listOf("mira@x.co"),accepted.contacts[0].contactEmails)
        assertEquals(listOf("dev@x.co"),accepted.contacts[1].contactEmails)
    }
    @Test fun loneMissingChannelCannotBeSilentlyAssignedToTheOnlyReturnedPerson() {
        val input=evidence("Mira","Dev","dev@x.co")
        val result=read("""{"contacts":[{"name":"Mira","sources":{"name":["F1"]}}]}""",input)
        assertTrue(result.reviewIssues.any {it.field=="ownership" && "F3" in it.sourceIds})
        assertEquals("",result.contacts.single().email)
    }
    @Test fun confirmedSinglePersonRetainsEveryDeclaredPhoneEmailAndWebsite() {
        val input=evidence("Mira Sen","9876543210","080 23456789","080 23456780","mira@x.co","office@x.co","www.x.co","www.studio.co")
        val result=read("""{"contacts":[{"name":"Mira Sen","phones":[{"number":"9876543210","label":""},{"number":"080 23456789","label":""},{"number":"080 23456780","label":""}],"emails":["mira@x.co","office@x.co"],"websites":["www.x.co","www.studio.co"]}]}""",input)
        assertFalse(result.reviewIssues.any {it.field=="ownership"})
        assertEquals(3,result.contacts.single().contactPhones.size)
        assertEquals(2,result.contacts.single().contactEmails.size)
        assertEquals(2,result.contacts.single().contactWebsites.size)
    }
    @Test fun ownershipReviewDoesNotPermitDroppingAlreadyConfirmedChannels() {
        val input=evidence("Mira Sen","mira@x.co","dev@x.co")
        val first=read("""{"contacts":[{"name":"Mira Sen","emails":["mira@x.co"]}]}""",input)
        assertTrue(first.reviewIssues.any {it.field=="ownership"})
        val destructive=VisualProposal(listOf(Card(name="Mira Sen"),Card(name="Dev Rao",email="dev@x.co")),emptyList())
        assertEquals(first.contacts,SourceAssignments.preferRepair(first,destructive).contacts)
    }
    @Test fun datesPostalCodesAndStreetNumbersAreNotUnassignedPhoneEvidence() {
        val input=evidence("Mira Sen","12 Lake Road","Bengaluru 560001","2026-10-01")
        val result=read("""{"contacts":[{"name":"Mira Sen","address":"12 Lake Road\nBengaluru 560001"}],"ignoredSources":{"F4":"date"}}""",input)
        assertFalse(result.reviewIssues.any {it.field=="ownership"})
    }
    @Test fun channelOmissionDoesNotInventSecondPersonForOnePersonWithTwoNumbers() {
        val input=evidence("Mira Sen","9876543210","080 23456789")
        val first=read("""{"contacts":[{"name":"Mira Sen","phones":[{"number":"9876543210"}]}]}""",input)
        assertEquals(1,first.contacts.size);assertTrue(first.reviewIssues.any {it.field=="ownership"})
        val revised=read("""{"contacts":[{"name":"Mira Sen","phones":[{"number":"9876543210"},{"number":"080 23456789"}]}]}""",input)
        val accepted=SourceAssignments.preferRepair(first,revised)
        assertEquals(1,accepted.contacts.size);assertEquals(2,accepted.contacts.single().contactPhones.size)
    }

    @Test fun twoDistinctLiteralNamesCanShareOneOcrLineWithoutLosingPeopleOrChannels() {
        val result=read("""{"contacts":[{"name":"Mira Sen","emails":["mira@x.co"],"sources":{"name":["F1"],"emails.0":["F2"]}},{"name":"Dev Rao","emails":["dev@x.co"],"sources":{"name":["F1"],"emails.0":["F3"]}}]}""",
            evidence("Mira Sen | Dev Rao","mira@x.co","dev@x.co"))
        assertEquals(listOf("Mira Sen","Dev Rao"),result.contacts.map {it.name})
        assertEquals(listOf(listOf("mira@x.co"),listOf("dev@x.co")),result.contacts.map {it.contactEmails})
        assertTrue(result.reviewIssues.isEmpty())
        assertTrue(result.sources.filter {it.field=="name"}.all {it.regionIds==listOf("F1")})
    }
    @Test fun overlappingOrIdenticalSharedLiteralNamesStillRequireReview() {
        listOf("Ann" to "Ann Lee","Ann Lee" to "Ann Lee","ANN LEE" to "Ann Lee").forEach {(a,b)->
            val result=read("""{"contacts":[{"name":"$a","sources":{"name":["F1"]}},{"name":"$b","sources":{"name":["F1"]}}]}""",evidence("Ann Lee"))
            assertTrue(result.contacts.all {it.name.isEmpty()})
            assertEquals(2,result.reviewIssues.count {it.reason.contains("more than one person")})
        }
    }
    @Test fun repeatedOrUngroundedSharedNameLiteralIsAmbiguous() {
        listOf("Mira Sen | Mira Sen | Dev Rao" to "Dev Rao","Mira Sen | Dev Rao" to "Invented Person").forEach {(line,second)->
            val result=read("""{"contacts":[{"name":"Mira Sen","sources":{"name":["F1"]}},{"name":"$second","sources":{"name":["F1"]}}]}""",evidence(line))
            assertTrue(result.contacts.all {it.name.isEmpty()})
            assertEquals(2,result.reviewIssues.count {it.reason.contains("more than one person")})
        }
    }
    @Test fun sharedNameSubstringMustRespectUnicodeWordBoundaries() {
        listOf("Miranda | Dev Rao","Mira\u0301 | Dev Rao").forEach {line->
            val result=read("""{"contacts":[{"name":"Mira","sources":{"name":["F1"]}},{"name":"Dev Rao","sources":{"name":["F1"]}}]}""",evidence(line))
            assertTrue(result.contacts.all {it.name.isEmpty()})
            assertEquals(2,result.reviewIssues.count {it.reason.contains("more than one person")})
        }
    }
    @Test fun sharedIdOnlyReconstructionCannotInventDistinctPeople() {
        val result=read("""{"contacts":[{"sources":{"name":["F1"]}},{"name":"Dev Rao","sources":{"name":["F1"]}}]}""",evidence("Mira Sen | Dev Rao"))
        assertTrue(result.contacts.all {it.name.isEmpty()})
        assertEquals(2,result.reviewIssues.count {it.reason.contains("more than one person")})
    }

    @Test fun overlappingRepeatedOccurrenceOfOneLiteralIsNotTreatedAsUnique() {
        val result=read("""{"contacts":[{"name":"Ali Ali","sources":{"name":["F1"]}},{"name":"Dev Rao","sources":{"name":["F1"]}}]}""",evidence("Ali Ali Ali | Dev Rao"))
        assertTrue(result.contacts.all {it.name.isEmpty()})
        assertEquals(2,result.reviewIssues.count {it.reason.contains("more than one person")})
    }

}
