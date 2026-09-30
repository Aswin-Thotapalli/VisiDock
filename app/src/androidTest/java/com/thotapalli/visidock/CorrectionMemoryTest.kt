package com.thotapalli.visidock

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CorrectionMemoryTest {
    @Test fun correctionHistoryIsEncryptedScopedDisableableAndDeletable() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val first=CorrectionMemory(context,"test-learning-one")
        val other=CorrectionMemory(context,"test-learning-two")
        first.clear();other.clear();first.setEnabled(true)
        try {
            val proposal=Card(id="test",name="Northline Studio",rawText="Northline Studio\nMira Sen")
            first.remember(proposal,proposal.copy(name="Mira Sen",company="Northline Studio"))
            assertEquals(2,first.labels().size)
            assertTrue(other.labels().isEmpty())
            assertTrue(first.hints("Northline Studio\nDev Rao").contains("Northline Studio"))
            assertFalse(first.hints("Northline Studio\nDev Rao").contains("Mira Sen"))
            File(context.filesDir,"learning").listFiles().orEmpty().forEach {
                assertFalse(it.readBytes().toString(Charsets.ISO_8859_1).contains("Northline Studio"))
            }
            File(context.noBackupFilesDir,"personal-learning").listFiles().orEmpty().forEach {
                assertFalse(it.readBytes().toString(Charsets.ISO_8859_1).contains("Northline Studio"))
            }
            first.setEnabled(false)
            assertEquals("",first.hints("Northline Studio"))
            first.setEnabled(true)
            first.forgetCard("test")
            assertTrue(first.labels().isEmpty())
        } finally {first.clear();other.clear()}
    }
}
