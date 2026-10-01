package com.thotapalli.visidock

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DraftStoreTest {
    @Test fun orderedRecoveryPreservesAccountDraftAndDurableCropButExcludesSecrets()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val owner="draft-test-${UUID.randomUUID()}"
        val store=DraftStore(context)
        val crop=File(ImageCropper.directory(context),"${UUID.randomUUID()}.jpg").apply {writeBytes(byteArrayOf(1,2,3))}
        var durable:File?=null
        try {
            store.queueSnapshot(owner,mapOf("draftOwner" to owner,"draft" to "first","cropSource" to crop.path,"password" to "never-store-this","protectedEdits" to "{}","fieldSourcesJson" to "[]"))
            store.queueSnapshot(owner,mapOf("draftOwner" to owner,"draft" to "second","cropSource" to crop.path,"extractionWarnings" to arrayListOf("Check this field"),"ownCardChoiceRequired" to true))
            store.flush()
            val restored=DraftStore(context).load(owner)
            assertEquals("second",restored["draft"]);assertFalse(restored.containsKey("password"))
            assertTrue(restored["extractionWarnings"] is ArrayList<*>)
            assertEquals(true,restored["ownCardChoiceRequired"])
            durable=File(restored["cropSource"] as String)
            assertEquals(ImagePipeline.directory(context).canonicalFile,durable.canonicalFile.parentFile)
            crop.delete();assertArrayEquals(byteArrayOf(1,2,3),durable.readBytes())
            assertTrue(store.protectedPaths().contains(durable.canonicalPath))
            assertTrue(store.load("other-${UUID.randomUUID()}").isEmpty())
            store.clear(owner);assertTrue(store.load(owner).isEmpty())
        } finally {store.clear(owner);crop.delete();durable?.delete()}
    }
    @Test fun draftRejectsUnownedPathsAndKeepsClearAfterOlderQueuedWrites()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val owner="draft-path-test-${UUID.randomUUID()}";val store=DraftStore(context)
        store.queueSnapshot(owner,mapOf("draftOwner" to owner,"draft" to "pending","original" to File(context.filesDir,"../outside").path))
        val recovered=store.load(owner)
        assertFalse(recovered.containsKey("original"))
        store.clear(owner)
        assertTrue(store.load(owner).isEmpty())
    }
}
