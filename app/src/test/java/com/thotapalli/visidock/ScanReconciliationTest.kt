package com.thotapalli.visidock

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScanReconciliationTest {
    private val mira=Card(id="mira",name="Mira Sen",role="Designer",notes="Met at expo",isOwnCard=true,hasLocalFrontImage=true)
    private val dev=Card(id="dev",name="Dev Shah",role="Engineer")
    private fun reconcile(proposal:VisualProposal,current:Card=mira,pending:List<Card> = emptyList(),processed:List<Card> = emptyList(),protection:JSONObject?=null)=
        ScanReconciliation.reconcile(proposal,current,pending,(listOf(mira,dev)).associateBy {it.id},processed,emptyList(),0,protection,"scan")
    @Test fun secondPersonRereadDoesNotBecomeFirstPersonOrReintroduceSavedContact() {
        val proposal=VisualProposal(listOf(mira.copy(id=""),dev.copy(id="",role="Senior Engineer")),emptyList())
        val result=reconcile(proposal,dev.copy(role="My correction"),processed=listOf(mira),protection=JSONObject().put("role","My correction"))
        assertEquals(listOf("dev"),result.cards.map {it.id})
        assertEquals("Dev Shah",result.cards.single().name)
        assertEquals("My correction",result.cards.single().role)
        assertEquals("Senior Engineer",result.baselines.single().role)
        assertEquals("role",CorrectionPolicy.activity(result.baselines.single(),result.cards.single()).single().field)
    }
    @Test fun reorderedModelResultsKeepCurrentIdentityPendingIdsAndMetadata() {
        val result=reconcile(VisualProposal(listOf(dev.copy(id=""),mira.copy(id="",notes="",isOwnCard=false)),emptyList()),pending=listOf(dev))
        assertEquals(listOf("mira","dev"),result.cards.map {it.id})
        assertEquals("Met at expo",result.cards.first().notes)
        assertTrue(result.cards.first().isOwnCard)
        assertTrue(result.cards.first().hasLocalFrontImage)
    }
    @Test fun omittedPendingPersonIsRetainedInsteadOfDiscarded() {
        val result=reconcile(VisualProposal(listOf(mira.copy(id="")),emptyList()),pending=listOf(dev))
        assertEquals(listOf("mira","dev"),result.cards.map {it.id})
        assertTrue(result.warnings.any {it.contains("previous details were kept")})
    }
    @Test fun ambiguousIdentityDoesNotReceiveProtectedEditsByArrayPosition() {
        assertThrows(IllegalStateException::class.java) {
            reconcile(VisualProposal(listOf(Card(name="Unknown A"),Card(name="Unknown B")),emptyList()),pending=listOf(dev),protection=JSONObject().put("phone","123"))
        }
    }
    @Test fun sourcePositionCanMatchCorrectedSpellingWithoutFirstResultAssumption() {
        val region=OcrRegion("Mira Sen",.1f,.2f,.4f,.3f)
        val old=FieldSource(0,"name","Mira Sem",listOf("F1"),listOf(region),true)
        val next=old.copy(contactIndex=1,value="Mira Sen")
        val current=mira.copy(name="Mira Sem")
        val result=ScanReconciliation.reconcile(VisualProposal(listOf(dev,mira),emptyList(),listOf(next)),current,listOf(dev),
            mapOf(current.id to current,dev.id to dev),emptyList(),listOf(old),0,null,"scan")
        assertEquals("mira",result.cards.first().id)
        assertEquals("Mira Sen",result.cards.first().name)
        assertEquals(0,result.sources.single().contactIndex)
    }
    @Test fun protectedClearedFieldStaysEmptyRatherThanBeingRepopulated() {
        val result=reconcile(VisualProposal(listOf(mira),emptyList()),protection=JSONObject().put("role",""))
        assertEquals("",result.cards.single().role)
        assertEquals("Designer",result.baselines.single().role)
    }
    @Test fun jointOwnCardHasNoDefaultOwnerEvenWhenFirstResultLooksPlausible() {
        val current=Card(id="new-own",isOwnCard=true)
        val result=ScanReconciliation.reconcile(VisualProposal(listOf(dev,mira),emptyList()),current,emptyList(),emptyMap(),emptyList(),emptyList(),0,null,"scan")
        assertTrue(result.ownCardChoiceRequired)
        assertTrue(result.cards.none {it.isOwnCard});assertTrue(result.baselines.none {it.isOwnCard})
        assertEquals(listOf("Dev Shah","Mira Sen"),result.cards.map {it.name})
    }
    @Test fun singleOwnCardAndOrdinaryJointCardsKeepTheirExistingClassification() {
        fun initial(current:Card,contacts:List<Card>)=ScanReconciliation.reconcile(VisualProposal(contacts,emptyList()),current,emptyList(),emptyMap(),emptyList(),emptyList(),0,null,"scan")
        val own=initial(Card(id="mine",isOwnCard=true),listOf(mira))
        assertFalse(own.ownCardChoiceRequired);assertTrue(own.cards.single().isOwnCard)
        val ordinary=initial(Card(id="collected"),listOf(dev,mira))
        assertFalse(ordinary.ownCardChoiceRequired);assertTrue(ordinary.cards.none {it.isOwnCard})
    }
    @Test fun rescanOfIdentifiedOwnCardPreservesOwnerAcrossReordering() {
        val result=reconcile(VisualProposal(listOf(dev,mira),emptyList()),pending=listOf(dev))
        assertFalse(result.ownCardChoiceRequired)
        assertEquals(listOf("mira"),result.cards.filter {it.isOwnCard}.map {it.id})
    }
    @Test fun draftSourceReviewRoundTripPreservesSideOwnerAndWarnings() {
        val source=FieldSource(1,"emails.1","dev@example.com",listOf("B2"),listOf(OcrRegion("dev@example.com",.1f,.2f,.8f,.3f,1)),true)
        val issue=ExtractionReviewIssue(1,"email","Check punctuation",listOf("B2"))
        assertEquals(listOf(source),SourceReviewState.readSources(SourceReviewState.sources(listOf(source))))
        assertEquals(listOf(issue),SourceReviewState.readIssues(SourceReviewState.issues(listOf(issue))))
    }
}
