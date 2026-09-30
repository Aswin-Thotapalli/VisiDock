package com.thotapalli.visidock

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class PersonalLearningLifecycleTest {
    @Test fun resetDuringTrainingCannotResurrectTheDeletedModel() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val uid="training-lifecycle-test"
        val scope=MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString(""){"%02x".format(it)}
        val store=PersonalLearning(context,uid)
        val memory=CorrectionMemory(context,uid)
        memory.clear();memory.setEnabled(true)
        try {
            repeat(6) {i ->
                val before=Card(id="card-$i",sourceScanId="scan-$i",name="Managing Director",role="Mira Sen",rawText="Mira Sen\nManaging Director\nStudio $i")
                store.remember(before,before.copy(name="Mira Sen",role="Managing Director"),emptyList())
            }
            val file=File(context.noBackupFilesDir,"personal-learning/$scope.bin")
            assertTrue(file.exists())
            var cleared=false
            store.trainIfEligible {
                if(!cleared) {store.clear();cleared=true}
                true
            }
            assertTrue("The training callback must run",cleared)
            assertFalse("A stale training snapshot must never recreate a cleared file",file.exists())
        } finally {memory.clear()}
    }
}
