package com.thotapalli.visidock

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher

class CorrectionMemoryTest {
    @Test fun deferredTrainingCannotResurrectClearedHistoryOrOverwriteANewerEdit() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val uid="test-history-deferred"
        val memory=CorrectionMemory(context,uid)
        memory.clear();memory.setEnabled(true)
        val scope=MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString("") {"%02x".format(it)}
        val classifier=File(context.noBackupFilesDir,"personal-learning/$scope.bin")
        try {
            val before=Card(id="one",name="Studio",rawText="Studio\nMira Sen\nDev Rao")
            val oldWork=checkNotNull(memory.record(before,before.copy(name="Mira Sen")))
            val currentWork=checkNotNull(memory.record(before,before.copy(name="Dev Rao")))
            assertEquals(2,memory.history().size)
            oldWork()
            assertFalse("An obsolete correction must not start training",classifier.exists())
            memory.clear()
            currentWork()
            assertTrue(memory.history().isEmpty())
            assertFalse("Reset must invalidate queued embedding work",classifier.exists())
        } finally {memory.clear()}
    }
    @Test fun unsupportedCorrectionsAreRecordedPrivatelyAndSurviveReopening() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val uid="test-history-only"
        val memory=CorrectionMemory(context,uid)
        val other=CorrectionMemory(context,"test-history-other")
        memory.clear();other.clear();memory.setEnabled(true)
        try {
            val before=Card(id="one",name="Mlra Sen",email="typo@example.com",rawText="Mlra Sen\ntypo@example.com")
            memory.remember(before,before.copy(name="Mira Sen",email=""))
            assertTrue(memory.labels().isEmpty())
            val history=CorrectionMemory(context,uid).history()
            assertEquals(listOf("name","email"),history.map {it.field})
            assertEquals("Mira Sen",history.first().after)
            assertTrue(history.all {!it.eligibleForLearning})
            assertTrue(other.history().isEmpty())
            val scope=MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString("") {"%02x".format(it)}
            assertFalse(File(context.filesDir,"learning/$scope.bin").readBytes().toString(Charsets.ISO_8859_1).contains("Mira Sen"))
            memory.setEnabled(false)
            memory.remember(before,before.copy(name="Different Person"))
            assertEquals(2,memory.history().size)
            memory.setEnabled(true)
            memory.forgetCard("one")
            assertTrue(memory.history().isEmpty())
        } finally {memory.clear();other.clear()}
    }

    @Test fun oldEncryptedLabelArrayMigratesWithoutLosingExistingLabels() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val uid="test-history-migration"
        val memory=CorrectionMemory(context,uid)
        memory.clear();memory.setEnabled(true)
        try {
            val before=Card(id="new",name="Mlra",rawText="Mlra")
            // Creates the account encryption key without requiring any learned embedding.
            memory.remember(before,before.copy(name="Mira"))
            val scope=MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString("") {"%02x".format(it)}
            val key=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.getKey("visidock-learning-$scope",null)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key)}
            val legacy="""[{"phrase":"Northline","field":"company","cardId":"old"}]""".toByteArray()
            File(context.filesDir,"learning/$scope.bin").writeBytes(cipher.iv+cipher.doFinal(legacy))
            assertEquals("Northline",memory.labels().single().phrase)
            assertTrue(memory.history().isEmpty())
            memory.remember(before,before.copy(name="Mira"))
            assertEquals("Northline",CorrectionMemory(context,uid).labels().single().phrase)
            assertEquals("Mira",CorrectionMemory(context,uid).history().single().after)
            memory.clear()
            assertTrue(memory.labels().isEmpty());assertTrue(memory.history().isEmpty())
        } finally {memory.clear()}
    }
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
