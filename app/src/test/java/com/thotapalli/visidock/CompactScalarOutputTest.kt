package com.thotapalli.visidock

import org.junit.Assert.*
import org.junit.Test

class CompactScalarOutputTest {
    private fun evidence(vararg text:String)=OcrEvidence(text.mapIndexed {index,value->
        OcrObservation(OcrRegion(value,.1f,.04f+index*.06f,.8f,.08f+index*.06f))
    })
    private fun read(json:String,ocr:OcrEvidence)=VisualExtraction.parse(json,
        ocr.lines.filter {it.region.side==0}.joinToString("\n") {it.region.text},
        ocr.lines.filter {it.region.side==1}.joinToString("\n") {it.region.text},ocr)

    @Test fun scalarSourceOnlyOutputReconstructsAllFieldsAndTwoRegionName() {
        val ocr=OcrEvidence.combine(evidence("Mira","Sen","Design Director","Northline Studio"),
            evidence("Building 7","12 Lake Road","Bengaluru 560001"))
        val result=read("""{"contacts":[{"kind":"person","sources":{"name":["F1","F2"],"role":["F3"],"company":["F4"],"address":["B1","B2","B3"]}}]}""",ocr)
        val card=result.contacts.single()
        assertEquals("Mira Sen",card.name);assertEquals("Design Director",card.role)
        assertEquals("Northline Studio",card.company)
        assertEquals("Building 7\n12 Lake Road\nBengaluru 560001",card.address)
        assertTrue(result.reviewIssues.isEmpty())
        assertEquals(listOf("B1","B2","B3"),result.sources.single {it.field=="address"}.regionIds)
    }
    @Test fun mixedNameAndTitleRegionUsesDistinctLiteralSubstringsWithoutOverwritingEither() {
        val result=read("""{"contacts":[{"name":"Mira Sen","role":"Design Director","sources":{"name":["F1"],"role":["F1"]}}]}""",
            evidence("Mira Sen Design Director"))
        assertEquals("Mira Sen",result.contacts.single().name)
        assertEquals("Design Director",result.contacts.single().role)
        assertTrue(result.reviewIssues.isEmpty())
    }
    @Test fun disputedVisualLiteralCorrectionSurvivesAndRemainsMarkedForReview() {
        val primary=evidence("Mira Sem")
        val ocr=primary.merge(listOf(primary.lines.single().copy(region=primary.lines.single().region.copy(text="Mira Sen"),pass="detail")))
        val result=read("""{"contacts":[{"name":"Mira Sen","sources":{"name":["F1"]}}]}""",ocr)
        assertEquals("Mira Sen",result.contacts.single().name)
        assertTrue(result.reviewIssues.any {it.field=="name" && it.reason.contains("differs")})
        assertEquals(listOf("F1"),result.sources.single {it.field=="name"}.regionIds)
    }
    @Test fun ambiguousNameWithoutSourceAssignmentStaysEmptyRatherThanBorrowingHeading() {
        val result=read("""{"contacts":[{"sources":{"company":["F1"]}}],"warnings":["Person ownership is unclear"]}""",
            evidence("Northline Studio","Mira Sen","Dev Rao"))
        assertEquals("",result.contacts.single().name)
        assertEquals("Northline Studio",result.contacts.single().company)
        assertTrue(result.reviewIssues.any {it.field=="name"})
        assertTrue(result.warnings.contains("Person ownership is unclear"))
    }
    @Test fun threeRegionIdentityRequiresLiteralRatherThanBroadImplicitRecovery() {
        val ocr=evidence("Mira","Kumari","Sen")
        val sourceOnly=read("""{"contacts":[{"sources":{"name":["F1","F2","F3"]}}]}""",ocr)
        assertEquals("",sourceOnly.contacts.single().name)
        assertTrue(sourceOnly.reviewIssues.any {it.reason.contains("Too many regions")})
        val explicit=read("""{"contacts":[{"name":"Mira Kumari Sen","sources":{"name":["F1","F2","F3"]}}]}""",ocr)
        assertEquals("Mira Kumari Sen",explicit.contacts.single().name)
        assertTrue(explicit.reviewIssues.isEmpty())
    }
    @Test fun imageOnlyOutputStillAcceptsLiteralsWithoutSourceIds() {
        val result=VisualExtraction.parse("""{"contacts":[{"name":"Mira Sen","role":"Director","company":"Northline","address":"12 Lake Road","phones":[{"number":"9876543210"}],"emails":["mira@example.com"]}]}""","")
        val card=result.contacts.single()
        assertEquals("Mira Sen",card.name);assertEquals("Director",card.role)
        assertEquals("Northline",card.company);assertEquals("12 Lake Road",card.address)
        assertEquals(listOf("mira@example.com"),card.contactEmails)
        assertEquals("9876543210",card.contactPhones.single().number)
    }
}
