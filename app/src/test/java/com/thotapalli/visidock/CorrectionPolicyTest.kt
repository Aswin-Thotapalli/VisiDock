package com.thotapalli.visidock

import org.junit.Assert.*
import org.junit.Test

class CorrectionPolicyTest {
    @Test fun companyMistakenForNameCanBecomeALocalLabelHint() {
        val proposed=Card(id="one",name="Northline Studio",rawText="Northline Studio\nMira Sen")
        val corrections=CorrectionPolicy.learn(proposed,proposed.copy(name="Mira Sen",company="Northline Studio"))
        assertTrue(corrections.any {it.phrase=="Northline Studio" && it.field=="company"})
        val next=CorrectionPolicy.relevant(corrections,"Northline Studio\nDev Rao")
        assertEquals(listOf("Northline Studio"),next.map {it.phrase})
        assertFalse(next.any {it.phrase=="Mira Sen"})
    }
    @Test fun userAddedInformationAbsentFromCardIsNotTrainingEvidence() {
        val proposed=Card(id="one",rawText="Northline Studio")
        assertTrue(CorrectionPolicy.learn(proposed,proposed.copy(name="Aswin Thotapalli",phone="9999999999")).isEmpty())
    }
    @Test fun unchangedAiOutputDoesNotTeachItself() {
        val proposed=Card(id="one",name="Mira Sen",rawText="Mira Sen")
        assertTrue(CorrectionPolicy.learn(proposed,proposed.copy(notes="Met at lunch")).isEmpty())
    }
    @Test fun partialWordMatchesCannotLeakNames() {
        assertFalse(CorrectionPolicy.appears("Sam","Samsung Studio"))
        assertTrue(CorrectionPolicy.appears("Sam","Name: Sam\nStudio"))
    }
    @Test fun conflictingLabelsAreWithheld() {
        val values=listOf(LearnedLabel("Northline","name","one"),LearnedLabel("Northline","company","two"))
        assertTrue(CorrectionPolicy.relevant(values,"Northline").isEmpty())
    }
    @Test fun trainedHintsCannotReintroduceAConflictingPhraseOrOverrideAnExplicitCorrection() {
        val values=listOf(LearnedLabel("Northline","name","one"),LearnedLabel("Northline","company","two"),LearnedLabel("Mira Sen","name","three"))
        val result=CorrectionPolicy.mergeSuggestions(values,listOf("Northline" to "company","Mira Sen" to "role","Managing Director" to "role"),"Northline\nMira Sen\nManaging Director")
        assertFalse(result.any {it.phrase=="Northline"})
        assertEquals("name",result.single {it.phrase=="Mira Sen"}.field)
        assertEquals("role",result.single {it.phrase=="Managing Director"}.field)
    }
}
