package com.thotapalli.visidock

import org.junit.Assert.*
import org.junit.Test

class VisualPromptEvidenceTest {
    private fun line(text:String,side:Int=0)=OcrObservation(OcrRegion(text,.1f,.2f,.8f,.3f,side))
    @Test fun splitCompanyAndPersonStayInReadingOrderWithCompleteEmailAndAddress() {
        val lines=listOf("NORTHLINE TECHNOLOGIES PRIVATE", "LIMITED", "Arjun Mehta", "Regional Sales Manager",
            "arjun@northline.example", "Building 7", "Lake Road", "Hyderabad 500081")
        val raw=lines.joinToString("\n")
        val evidence=OcrEvidence(lines.mapIndexed {i,text->OcrObservation(OcrRegion(text,.1f,i*.1f,.9f,i*.1f+.05f))})
        val result=VisualPromptEvidence.build(raw,"",evidence)
        assertTrue(result.text.contains("FRONT OCR:\n$raw"))
        assertFalse(result.omittedByBudget)
        val spatial=evidence.modelContextResult(2400,false).text
        assertTrue(spatial.indexOf("\"id\":\"F1\"") < spatial.indexOf("\"id\":\"F2\""))
        assertTrue(spatial.indexOf("\"id\":\"F2\"") < spatial.indexOf("\"id\":\"F8\""))
    }

    @Test fun completeRawReadingRemainsContiguousBeforeSpatialEvidence() {
        val text="Mira Sen\nmira@example.com"
        val result=VisualPromptEvidence.build(text,"",OcrEvidence(text.lines().map {line(it)}))
        assertEquals(0,result.deduplicatedLines)
        assertTrue(result.text.contains("FRONT OCR:\n$text"))
        assertTrue(result.text.indexOf("FRONT OCR:") < result.text.indexOf("Source regions:"))
        assertEquals(2,Regex("mira@example.com").findAll(result.text).count())
        assertFalse(result.omittedByBudget)
    }
    @Test fun missingEvidenceFallsBackToFullRawTextOnBothSides() {
        val result=VisualPromptEvidence.build("Front name\nfront@example.com","Back address",OcrEvidence())
        assertTrue(result.text.contains("FRONT OCR:\nFront name\nfront@example.com"))
        assertTrue(result.text.contains("BACK OCR:\nBack address"))
        assertEquals(0,result.deduplicatedLines);assertFalse(result.omittedByBudget)
    }
    @Test fun uncertainWhitespaceCaseOrPunctuationIsNeverCalledCovered() {
        val result=VisualPromptEvidence.build("Mira  Sen\nMIRA SEN\nmira.example.com","",OcrEvidence(listOf(line("Mira Sen"),line("mira@example.com"))))
        assertEquals(0,result.deduplicatedLines)
        assertTrue(result.text.contains("Mira  Sen\nMIRA SEN\nmira.example.com"))
    }
    @Test fun frontCannotCoverBackAndOneOccurrenceCannotCoverTwo() {
        val result=VisualPromptEvidence.build("Mira Sen\nMira Sen","Mira Sen",OcrEvidence(listOf(line("Mira Sen"))))
        assertEquals(0,result.deduplicatedLines)
        assertTrue(result.text.contains("FRONT OCR:\nMira Sen\nMira Sen"))
        assertTrue(result.text.contains("BACK OCR:\nMira Sen"))
    }
    @Test fun sourceRowTruncationRetainsFullTextAndDisagreement() {
        val long="Address "+"building details ".repeat(20)+"Final postcode 560001"
        val primary=line(long);val alternate=line("Alternative postcode 560002")
        val result=VisualPromptEvidence.build(long,"",OcrEvidence(listOf(primary),listOf(OcrDisagreement(primary,alternate))))
        assertEquals(0,result.deduplicatedLines)
        assertTrue(result.text.contains("FRONT OCR:\n$long"))
        assertTrue(result.text.contains("Alternative postcode 560002"))
        assertTrue(result.text.contains("CONFLICT F1"));assertFalse(result.omittedByBudget)
    }
    @Test fun rowsExcludedBySourceBudgetRemainInRawFallback() {
        val lines=(1..12).map {"Distinct line $it"}
        val evidence=OcrEvidence(lines.map {line(it)})
        val context=evidence.modelContextResult(600,false)
        assertTrue(context.fullyRepresented.size<lines.size)
        val result=VisualPromptEvidence.build(lines.joinToString("\n"),"",evidence,1200)
        lines.forEach {assertTrue("Missing $it",result.text.contains(it))}
        assertTrue(result.text.length<=1200)
    }
    @Test fun omittedRegionStillAppearsWhenRawTextDoesNotContainIt() {
        val long="Detailed address "+"part ".repeat(55)+"footer"
        val result=VisualPromptEvidence.build("Mira","",OcrEvidence(listOf(line("Mira"),line(long))))
        assertTrue(result.text.contains(long));assertFalse(result.omittedByBudget)
    }
    @Test fun largeEvidenceIsBoundedWithExplicitOmissionAndOriginalInputsUntouched() {
        val front="Front identity\n"+"detail ".repeat(2000)+"\nFront footer"
        val back="Back identity\n"+"address ".repeat(2000)+"\nBack footer"
        val evidence=OcrEvidence(listOf(line(front),line(back,1)))
        val result=VisualPromptEvidence.build(front,back,evidence,1200)
        assertTrue(result.omittedByBudget);assertTrue(result.text.length<=1200)
        assertTrue(result.text.contains("[OCR middle omitted]"))
        assertTrue(result.text.contains("Front footer"));assertTrue(result.text.contains("Back footer"))
        assertEquals(front,evidence.lines[0].region.text);assertEquals(back,evidence.lines[1].region.text)
    }
}
