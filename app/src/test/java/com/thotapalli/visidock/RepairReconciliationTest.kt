package com.thotapalli.visidock

import org.junit.Assert.*
import org.junit.Test

class RepairReconciliationTest {
    private fun source(person:Int,field:String,value:String,id:String="F1") = FieldSource(person,field,value,listOf(id),
        listOf(OcrRegion(value,.1f,.2f,.8f,.3f)),true)

    @Test fun reproducesWholeProposalRejectionWhenRepairRecoversNameButOmitsPhone() {
        val first=VisualProposal(listOf(Card(phone="9876543210")),emptyList(),
            reviewIssues=listOf(ExtractionReviewIssue(0,"name","Missing name")))
        val repair=VisualProposal(listOf(Card(name="Mira Sen")),emptyList(),listOf(source(0,"name","Mira Sen")))
        assertEquals("Mira Sen",SourceAssignments.preferRepair(first,repair).contacts.single().name)
        val merged=RepairReconciliation.merge(first,repair)
        assertEquals("Mira Sen",merged.contacts.single().name)
        assertEquals("9876543210",merged.contacts.single().phone)
    }
    @Test fun fillsGroundedRoleAndAddressWithoutDroppingUnrelatedChannelsOrUnresolvedIssue() {
        val first=VisualProposal(listOf(Card(name="Mira Sen",email="mira@example.com")),emptyList(),
            reviewIssues=listOf(ExtractionReviewIssue(0,"address","Missing address"),ExtractionReviewIssue(0,"company","Check company")))
        val repaired=VisualProposal(listOf(Card(name="Mira Sen",role="Designer",address="12 Lake Road")),emptyList(),
            listOf(source(0,"role","Designer","F2"),source(0,"address","12 Lake Road","F3")),
            listOf(ExtractionReviewIssue(0,"company","Check company")))
        val merged=RepairReconciliation.merge(first,repaired)
        assertEquals("Designer",merged.contacts.single().role)
        assertEquals("12 Lake Road",merged.contacts.single().address)
        assertEquals("mira@example.com",merged.contacts.single().email)
        assertTrue(merged.reviewIssues.any {it.field=="company"})
        assertFalse(merged.reviewIssues.any {it.field=="address"})
    }
    @Test fun reversedPeopleUseUniqueNamesRatherThanArrayPosition() {
        val first=VisualProposal(listOf(Card(name="Mira Sen",phone="1111111111"),Card(name="Dev Shah",phone="2222222222")),emptyList())
        val repaired=VisualProposal(listOf(Card(name="Dev Shah",role="Engineer"),Card(name="Mira Sen",role="Designer")),emptyList(),
            listOf(source(0,"role","Engineer","F2"),source(1,"role","Designer","F3")))
        val merged=RepairReconciliation.merge(first,repaired)
        assertEquals(listOf("Designer","Engineer"),merged.contacts.map {it.role})
        assertEquals(listOf("1111111111","2222222222"),merged.contacts.map {it.phone})
    }
    @Test fun anonymousMultiplePeopleNeverMatchByIndexOrSharedCompany() {
        val first=VisualProposal(listOf(Card(company="Studio",phone="1111111111"),Card(company="Studio",phone="2222222222")),emptyList())
        val repaired=VisualProposal(listOf(Card(name="Mira Sen",company="Studio"),Card(name="Dev Shah",company="Studio")),emptyList(),
            listOf(source(0,"name","Mira Sen"),source(1,"name","Dev Shah","F2")))
        assertEquals(first.contacts,RepairReconciliation.merge(first,repaired).contacts)
    }
    @Test fun unresolvedOwnershipOrUnsupportedTextCannotSupplyFields() {
        val first=VisualProposal(listOf(Card(name="Mira Sen")),emptyList())
        val ownership=VisualProposal(listOf(Card(name="Mira Sen",role="Designer")),emptyList(),listOf(source(0,"role","Designer")),
            listOf(ExtractionReviewIssue(0,"ownership","Ambiguous owner")))
        assertEquals("",RepairReconciliation.merge(first,ownership).contacts.single().role)
        val unsupported=VisualProposal(listOf(Card(name="Mira Sen",role="Designer")),emptyList(),listOf(source(0,"role","Designer").copy(regions=listOf(OcrRegion("Engineer",.1f,.2f,.8f,.3f)))))
        assertEquals("",RepairReconciliation.merge(first,unsupported).contacts.single().role)
    }
    @Test fun existingConfirmedScalarIsNotReplacedByDifferentRepairValue() {
        val first=VisualProposal(listOf(Card(name="Mira Sen",role="Director")),emptyList())
        val revised=VisualProposal(listOf(Card(name="Mira Sen",role="Designer")),emptyList(),listOf(source(0,"role","Designer")))
        assertEquals("Director",RepairReconciliation.merge(first,revised).contacts.single().role)
    }
    @Test fun mergedValuesReachFormAndPreserveFirstPassLearningBaseline() {
        val first=VisualProposal(listOf(Card(phone="9876543210",rawText="Mira Sen\n9876543210")),emptyList(),
            reviewIssues=listOf(ExtractionReviewIssue(0,"name","Missing name")))
        val repair=VisualProposal(listOf(Card(name="Mira Sen",role="Designer",address="12 Lake Road")),emptyList(),
            listOf(source(0,"name","Mira Sen"),source(0,"role","Designer","F2"),source(0,"address","12 Lake Road","F3")))
        val merged=SourceAssignments.preferRepair(first,repair)
        val form=ScanReconciliation.reconcile(merged,Card(id="draft"),emptyList(),emptyMap(),emptyList(),emptyList(),0,null,"scan")
        assertEquals("Mira Sen",form.cards.single().name)
        assertEquals("Designer",form.cards.single().role)
        assertEquals("12 Lake Road",form.cards.single().address)
        assertEquals("9876543210",form.cards.single().phone)
        assertEquals("draft",form.cards.single().id)
        assertEquals("Mira Sen\n9876543210",form.cards.single().rawText)
        assertEquals("Mira Sen",form.baselines.single().name)
    }
    @Test fun duplicateNamesDoNotPermitCrossPersonRepair() {
        val first=VisualProposal(listOf(Card(name="Alex",phone="1111111111"),Card(name="Alex",phone="2222222222")),emptyList())
        val revised=VisualProposal(listOf(Card(name="Alex",role="Designer"),Card(name="Alex",role="Engineer")),emptyList(),
            listOf(source(0,"role","Designer","F3"),source(1,"role","Engineer","F4")))
        assertEquals(first.contacts,RepairReconciliation.merge(first,revised).contacts)
    }
    @Test fun unnamedPeopleWithDistinctChannelsMatchEvenWhenRepairOmitsOtherData() {
        val first=VisualProposal(listOf(Card(email="mira@example.com",phone="1111111111"),Card(email="dev@example.com",phone="2222222222")),emptyList())
        val revised=VisualProposal(listOf(Card(name="Dev Shah",email="dev@example.com"),Card(name="Mira Sen",email="mira@example.com")),emptyList(),
            listOf(source(0,"name","Dev Shah","F2"),source(1,"name","Mira Sen","F1")))
        val result=SourceAssignments.preferRepair(first,revised)
        assertEquals(listOf("Mira Sen","Dev Shah"),result.contacts.map {it.name})
        assertEquals(listOf("1111111111","2222222222"),result.contacts.map {it.phone})
    }
    @Test fun sharedOfficeChannelAndConflictingNamesAreNotCorrespondenceEvidence() {
        val first=VisualProposal(listOf(Card(email="office@example.com"),Card(email="office@example.com")),emptyList())
        val revised=VisualProposal(listOf(Card(name="Mira Sen",email="office@example.com"),Card(name="Dev Shah",email="office@example.com")),emptyList(),
            listOf(source(0,"name","Mira Sen"),source(1,"name","Dev Shah","F2")))
        assertEquals(first.contacts,SourceAssignments.preferRepair(first,revised).contacts)
        val named=first.copy(contacts=listOf(Card(name="Mira Sen",email="mira@example.com")))
        val conflict=VisualProposal(listOf(Card(name="Dev Shah",email="mira@example.com",role="Engineer")),emptyList(),listOf(source(0,"role","Engineer")))
        assertEquals(named.contacts,SourceAssignments.preferRepair(named,conflict).contacts)
    }
    @Test fun wholeResponseCannotEraseFlaggedRoleWhenItRecoversName() {
        val first=VisualProposal(listOf(Card(role="Designer",phone="9876543210")),emptyList(),reviewIssues=listOf(
            ExtractionReviewIssue(0,"name","Missing name"),ExtractionReviewIssue(0,"role","Check role")))
        val revised=VisualProposal(listOf(Card(name="Mira Sen",phone="9876543210")),emptyList(),listOf(source(0,"name","Mira Sen")))
        val result=SourceAssignments.preferRepair(first,revised)
        assertEquals("Mira Sen",result.contacts.single().name)
        assertEquals("Designer",result.contacts.single().role)
        assertTrue(result.reviewIssues.any {it.field=="role"})
    }
    @Test fun nondestructivePureVisualRepairStillWorksWithoutOcrSources() {
        val first=VisualProposal(listOf(Card(role="Designer",phone="9876543210")),emptyList(),reviewIssues=listOf(ExtractionReviewIssue(0,"name","Missing name")))
        val revised=VisualProposal(listOf(Card(name="Mira Sen",role="Designer",phone="9876543210")),emptyList())
        assertEquals(revised,SourceAssignments.preferRepair(first,revised))
    }
}
