package com.thotapalli.visidock

import org.junit.Assert.*
import org.junit.Test

class OcrEvidenceTest {
    private fun line(text: String, top: Float = .2f, height: Float = .04f, score: Float? = .98f, pass: String = "full", side: Int = 0) =
        OcrObservation(OcrRegion(text, .1f, top, .8f, top+height, side), score, pass)

    @Test fun clearLargeTextDoesNotPayForAnotherRecognitionPass() {
        assertTrue(OcrDetailPlanner.plan(listOf(line("Mira Sen"), line("mira@example.com", .4f)), 2400, 1600).isEmpty())
    }

    @Test fun tinyPrintAndLowConfidenceHaveBoundedLocalRereads() {
        val lines = (0..15).map { line("address line $it", it*.05f, .007f, .6f) }
        val plan = OcrDetailPlanner.plan(lines, 2400, 1600)
        assertEquals(2, plan.size)
        assertTrue(plan.all { it.left >= 0 && it.right <= 1 && it.top >= 0 && it.bottom <= 1 })
        assertTrue(plan.all { (it.right-it.left)*(it.bottom-it.top) <= .35f })
    }

    @Test fun duplicateReadsAgreeWithoutDuplicatingTextOrCreatingPeople() {
        val full = line("Mira Sen")
        val evidence = OcrEvidence(listOf(full)).merge(listOf(full.copy(pass="detail", confidence=.99f)))
        assertEquals(1, evidence.lines.size)
        assertTrue(evidence.lines.single().agreed)
        assertTrue(evidence.disagreements.isEmpty())
        // Same printed company appears twice, in different people's layout regions.
        val separated = evidence.merge(listOf(line("Mira Sen", .7f, pass="detail")))
        assertEquals(2, separated.lines.size)
    }

    @Test fun differingPunctuationIsAlternativeNotAuthoritativeNewEmail() {
        val full = line("mira©example.com")
        val detail = line("mira@example.com", pass="detail")
        val result = OcrEvidence(listOf(full)).merge(listOf(detail))
        assertEquals(listOf(full), result.lines)
        assertEquals(listOf(OcrDisagreement(full, detail)), result.disagreements)
        assertTrue(ContactChannels.emails(result.lines.joinToString("\n") { it.region.text }).isEmpty())
        assertTrue(result.modelContext().contains("CONFLICT"))
        assertTrue(result.reviewWarnings.isNotEmpty())
    }

    @Test fun newFooterTextIsAddedWithSourceGeometryAndNoInventedPunctuation() {
        val result = OcrEvidence(listOf(line("Mira Sen"))).merge(listOf(line("12 Lake Road", .8f, pass="detail")))
        assertEquals(2, result.lines.size)
        assertEquals(.8f, result.lines.last().region.top, .00001f)
        assertEquals("detail", result.lines.last().pass)
        assertTrue(result.disagreements.isEmpty())
    }

    @Test fun sidesAndDisagreementsSurviveSavedStateRoundTrip() {
        val front = OcrEvidence(listOf(line("Mira"))).merge(listOf(line("Nira", pass="detail")))
        val combined = OcrEvidence.combine(front, OcrEvidence(listOf(line("12 Lake Road"))))
        assertEquals(combined, OcrEvidence.decode(combined.encode()))
        assertEquals(listOf(0,1), combined.lines.map { it.region.side })
        assertEquals(0, combined.disagreements.single().alternative.region.side)
        assertTrue(combined.modelContext(600).length <= 600)
        assertEquals(OcrEvidence(), OcrEvidence.decode("broken"))
    }

    @Test fun conflictCannotSilentlyRestoreWrongEmailOverVisualRead() {
        val first = line("mira@examp1e.com")
        val evidence = OcrEvidence(listOf(first)).merge(listOf(line("mira@example.com", pass="detail")))
        val read = VisualExtraction.parse("""{"contacts":[{"name":"Mira Sen","email":"mira@example.com"}]}""", "Mira Sen\nmira@examp1e.com", ocrEvidence=evidence)
        assertEquals("mira@example.com", read.contacts.single().email)
        assertTrue(read.warnings.any { "differed between OCR" in it })
        val blank = VisualExtraction.parse("""{"contacts":[{"name":"Mira Sen"}]}""", "Mira Sen\nmira@examp1e.com", ocrEvidence=evidence)
        assertEquals("", blank.contacts.single().email)
    }

    @Test fun disputedReadsDoNotAssignOnePersonsEmailToAnother() {
        val evidence = OcrEvidence(listOf(line("mira@examp1e.com"))).merge(listOf(line("mira@example.com", pass="detail")))
        val read = VisualExtraction.parse("""{"contacts":[{"name":"Mira Sen"},{"name":"Dev Shah"}]}""", "Mira Sen\nDev Shah\nmira@examp1e.com", ocrEvidence=evidence)
        assertTrue(read.contacts.all { it.email.isEmpty() })
    }
}
