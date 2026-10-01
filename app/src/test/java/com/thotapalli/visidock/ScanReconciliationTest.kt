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
    @Test fun sparseSuccessfulRereadKeepsPriorScalarAndChannelValuesWithReview() {
        val current=mira.copy(company="Northline",address="12 Lake Road",phone="9876543210",phones=listOf(PhoneNumber("9876543210","Mobile")),
            email="mira@example.com",emails=listOf("mira@example.com","studio@example.com"),website="www.example.com")
        val result=ScanReconciliation.reconcile(VisualProposal(listOf(Card(name="Mira Sen")),emptyList()),current,emptyList(),
            mapOf(current.id to current),emptyList(),emptyList(),0,null,"next-scan")
        val retained=result.cards.single()
        assertEquals(current.role,retained.role);assertEquals(current.company,retained.company);assertEquals(current.address,retained.address)
        assertEquals(current.contactPhones,retained.contactPhones);assertEquals(current.contactEmails,retained.contactEmails)
        assertEquals(current.contactWebsites,retained.contactWebsites)
        assertTrue(result.issues.any {it.field=="role" && it.reason.contains("earlier value")})
        assertTrue(result.issues.any {it.field=="phones"})
        assertTrue(CorrectionPolicy.activity(result.baselines.single(),retained).isEmpty())
    }
    @Test fun entirelyEmptySuccessfulRereadDoesNotEraseExistingPerson() {
        val result=reconcile(VisualProposal(listOf(Card()),emptyList()))
        assertEquals(mira.name,result.cards.single().name);assertEquals(mira.role,result.cards.single().role)
        assertEquals(mira.id,result.cards.single().id);assertTrue(result.cards.single().isOwnCard)
        assertTrue(result.issues.any {it.field=="name"})
    }
    @Test fun explicitUserClearsRemainEmptyAfterSparseReread() {
        val current=mira.copy(role="",email="",emails=emptyList())
        val result=reconcile(VisualProposal(listOf(Card(name="Mira Sen")),emptyList()),current,
            protection=JSONObject().put("role","").put("email","").put("emails",org.json.JSONArray()))
        assertEquals("",result.cards.single().role);assertTrue(result.cards.single().contactEmails.isEmpty())
    }
    @Test fun reorderedSparsePeopleKeepTheirOwnValuesWithoutCrossFilling() {
        val current=mira.copy(email="mira@example.com",address="Mira office")
        val other=dev.copy(email="dev@example.com",address="Dev office")
        val result=ScanReconciliation.reconcile(VisualProposal(listOf(Card(name=other.name),Card(name=current.name)),emptyList()),current,listOf(other),
            mapOf(current.id to current,other.id to other),emptyList(),emptyList(),0,null,"scan")
        assertEquals(listOf(current.id,other.id),result.cards.map {it.id})
        assertEquals(listOf("mira@example.com","dev@example.com"),result.cards.map {it.email})
        assertEquals(listOf("Mira office","Dev office"),result.cards.map {it.address})
    }
    @Test fun aDifferentNamedPersonCannotBorrowTheSingleExistingPersonsDetails() {
        assertThrows(IllegalStateException::class.java) {
            reconcile(VisualProposal(listOf(Card(name="An unrelated person")),emptyList()))
        }
    }
    @Test fun newAdditionalPersonDoesNotInheritRetainedChannelsFromExistingPerson() {
        val current=mira.copy(email="mira@example.com")
        val result=ScanReconciliation.reconcile(VisualProposal(listOf(Card(name="Mira Sen"),Card(name="New person")),emptyList()),current,emptyList(),
            mapOf(current.id to current),emptyList(),emptyList(),0,null,"scan")
        assertEquals("mira@example.com",result.cards.first().email)
        assertEquals("",result.cards[1].email);assertEquals("",result.cards[1].role)
    }
    @Test fun explicitNewChannelsReplaceOldReadingsRatherThanUnioningAnEarlierMistake() {
        val current=mira.copy(email="wrong@example.com")
        val result=ScanReconciliation.reconcile(VisualProposal(listOf(Card(name="Mira Sen",email="correct@example.com")),emptyList()),current,emptyList(),
            mapOf(current.id to current),emptyList(),emptyList(),0,null,"scan")
        assertEquals(listOf("correct@example.com"),result.cards.single().contactEmails)
    }

    @Test fun literalExtractionWithUnusableSourcesSurvivesInitialDraftAndRecordRoundTrip() {
        val text="Mira Sen\nDesign Director\nNorthline Studio\nMobile +91 98765 43210\nOffice 080 23456789\nOffice 080 23456780\nmira@example.com\nstudio@example.com\nwww.example.com\nwww.studio.example\n12 Lake Road\nBengaluru 560001"
        val ocr=OcrEvidence(text.lines().mapIndexed {i,line->OcrObservation(OcrRegion(line,.1f,.02f+i*.06f,.9f,.06f+i*.06f))})
        val proposal=VisualExtraction.parse("""{"contacts":[{"name":"Mira Sen","role":"Design Director","company":"Northline Studio","address":"12 Lake Road\nBengaluru 560001","phones":[{"number":"+91 98765 43210","label":"Mobile"},{"number":"080 23456789","label":"Office"},{"number":"080 23456780","label":"Office"}],"emails":["mira@example.com","studio@example.com"],"websites":["www.example.com","www.studio.example"],"sources":{"name":["F99"],"role":["F98"],"address":["B77"]}}]}""",text,"",ocr)
        assertTrue(proposal.reviewIssues.any {it.reason.contains("not in this scan")})
        val result=ScanReconciliation.reconcile(proposal,Card(id="draft"),emptyList(),emptyMap(),emptyList(),emptyList(),0,null,"scan")
        val card=result.cards.single()
        val stored=cardFrom(card.id,card.record())
        assertEquals("draft",stored.id);assertEquals("Mira Sen",stored.name)
        assertEquals("Design Director",stored.role);assertEquals("Northline Studio",stored.company)
        assertEquals("12 Lake Road\nBengaluru 560001",stored.address)
        assertEquals(listOf(PhoneNumber("+91 98765 43210","Mobile"),PhoneNumber("080 23456789","Office"),PhoneNumber("080 23456780","Office")),stored.contactPhones)
        assertEquals(listOf("mira@example.com","studio@example.com"),stored.contactEmails)
        assertEquals(listOf("www.example.com","www.studio.example"),stored.contactWebsites)
        assertEquals(text,stored.rawText);assertEquals("scan",stored.sourceScanId)
    }

}
