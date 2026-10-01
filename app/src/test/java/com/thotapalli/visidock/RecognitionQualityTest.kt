package com.thotapalli.visidock

import org.junit.Assert.*
import org.junit.Test

class RecognitionQualityTest {
    @Test fun normalReadsRetainFullQualityWhileHotDevicesLimitExtraWork() {
        val normal = RecognitionQuality.budget(OcrDeviceBudget(1024L*1024*1024))
        val hot = RecognitionQuality.budget(OcrDeviceBudget(1024L*1024*1024, thermalStatus=3))
        assertEquals(2400, normal.baseEdge)
        assertEquals(normal.baseEdge, hot.baseEdge)
        assertTrue(hot.detailPasses < normal.detailPasses)
        assertTrue(hot.detailPixels < normal.detailPixels)
        assertEquals(0, RecognitionQuality.budget(OcrDeviceBudget(100_000_000, lowMemory=true)).detailPasses)
    }
    @Test fun RereadMustBringMoreNativeDetailInsteadOfRepeatingDownscaledPixels() {
        val region = OcrRegion("tiny text", 0f, 0f, 1f, .1f)
        assertFalse(RecognitionQuality.addsDetail(2400, 1600, 1200, 80, region))
        assertTrue(RecognitionQuality.addsDetail(2400, 1600, 3600, 240, region))
    }
    @Test fun supportedLanguageHintsDoNotPretendEveryIndianScriptIsDevanagari() {
        assertEquals(OcrScript.Devanagari, OcrScript.forLanguage("hi"))
        assertEquals(OcrScript.Japanese, OcrScript.forLanguage("ja"))
        assertEquals(OcrScript.Latin, OcrScript.forLanguage("te"))
        assertEquals(OcrScript.Latin, OcrScript.forLanguage("en"))
    }
    @Test fun timingSchemaContainsNoTextPhotosPathsOrContactIdentifiers() {
        val json = RecognitionTiming(RecognitionStage.OcrFull, 320, true, 2400, 1600, 15).json()
        assertEquals(setOf("stage", "elapsedMs", "success", "width", "height", "regions"), json.keys().asSequence().toSet())
        assertEquals(320, json.getInt("elapsedMs"))
    }
    @Test fun QualityWarningsSurviveBothSidesAndPersistence() {
        val evidence = OcrEvidence(qualityWarnings=listOf("Memory limited this read"))
        val combined = OcrEvidence.combine(evidence, evidence)
        assertEquals(listOf("Memory limited this read"), OcrEvidence.decode(combined.encode()).reviewWarnings)
    }
}
