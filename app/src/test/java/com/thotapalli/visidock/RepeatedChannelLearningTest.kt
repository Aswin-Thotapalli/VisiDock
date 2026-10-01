package com.thotapalli.visidock

import org.junit.Assert.*
import org.junit.Test

class RepeatedChannelLearningTest {
    @Test fun secondEmailCorrectionAppearsInActivityAndLearnsOnlyPrintedValue() {
        val before = Card(id="one", email="mira@example.com", emails=listOf("mira@example.com"),
            rawText="Mira Sen\nmira@example.com\nteam@example.com")
        val after = before.copy(emails=listOf("mira@example.com", "team@example.com"))
        val activity = CorrectionPolicy.activity(before, after)
        assertEquals("email", activity.single().field)
        assertTrue(activity.single().after.contains("team@example.com"))
        assertEquals(listOf("team@example.com"), CorrectionPolicy.learn(before, after).map { it.phrase })
    }
    @Test fun addedContactInformationIsRecordedButNotTreatedAsRecognitionTraining() {
        val before = Card(id="one", rawText="Mira Sen\nmira@example.com", email="mira@example.com")
        val after = before.copy(emails=listOf("mira@example.com", "personal@example.net"))
        assertTrue(CorrectionPolicy.activity(before, after).isNotEmpty())
        assertTrue(CorrectionPolicy.learn(before, after).isEmpty())
    }
}
