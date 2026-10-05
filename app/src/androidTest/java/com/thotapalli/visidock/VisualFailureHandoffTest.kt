package com.thotapalli.visidock

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class VisualFailureHandoffTest {
    @Test fun runtimeFailureKeepsOcrPeopleAndBaselineAndRequiresExplicitNextAction()=runBlocking {
        assumeTrue(BuildConfig.DEMO)
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val drafts=DraftStore(app)
        drafts.clear(DemoRepository().session().uid)
        val store=ViewModelStore()
        val first=Card(id="first",name="Mira Sen",address="12 Lake Road",rawText="Fresh OCR front",backRawText="Fresh OCR back")
        val second=Card(id="second",name="Dev Rao",email="dev@example.com")
        fun encoded(card:Card)=JSONObject(card.record()+mapOf("id" to card.id))
        val baseline=JSONObject().put(first.id,encoded(first)).put(second.id,encoded(second)).toString()
        val handle=SavedStateHandle(mapOf("draftOwner" to "demo","draft" to encoded(first).toString(),
            "pendingPeople" to JSONArray().put(encoded(second)).toString(),"scanProposals" to baseline))
        try {
            val vm=withContext(Dispatchers.Main) {VaultViewModel(app,handle).also {store.put("failure",it)}}
            withTimeout(10000) {vm.state.first {!it.loading && it.busy==null}}
            val before=vm.state.value.draft
            val pending=handle.get<String>("pendingPeople")
            val failure=withContext(Dispatchers.Main) {
                try {vm.failVisualRead(IllegalArgumentException("Private native payload must not be displayed"))}
                catch(e:IllegalStateException) {e}
            }
            assertFalse(failure.message.orEmpty().contains("Private native payload"))
            assertTrue(failure.message.orEmpty().contains("Retry reading"))
            assertTrue(failure.message.orEmpty().contains("Enter details instead"))
            assertEquals(before,vm.state.value.draft)
            assertEquals(first.rawText,vm.state.value.draft!!.rawText)
            assertEquals(first.backRawText,vm.state.value.draft!!.backRawText)
            assertEquals(pending,handle.get<String>("pendingPeople"))
            assertEquals(baseline,handle.get<String>("scanProposals"))
            assertTrue(vm.state.value.captureReview)
            assertEquals(true,handle.get<Boolean>("captureReview"))
            assertFalse(vm.state.value.scanAnalysisReady)
            assertTrue(vm.state.value.extractionWarnings.any {it.contains("existing details are kept")})
        } finally {
            withContext(Dispatchers.Main) {store.clear()}
            drafts.clear(DemoRepository().session().uid)
        }
    }
}
