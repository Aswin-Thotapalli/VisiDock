package com.thotapalli.visidock

import android.app.Application
import android.graphics.Bitmap
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
import java.io.File

class MultiPersonScanTest {
    @Test fun restoredScanSavesTwoPeopleAndDeletionDoesNotRemoveOtherPersonsImages() = runBlocking {
        assumeTrue(BuildConfig.DEMO)
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val front=File(ImagePipeline.directory(app),"multi-front.jpg")
        val back=File(ImagePipeline.directory(app),"multi-back.jpg")
        val bitmap=Bitmap.createBitmap(240,140,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff10264b.toInt())
        listOf(front,back).forEach { file -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,90,it) } }
        bitmap.recycle()
        val first=Card(id="multi-one",name="Mira Sen",phone="9876543210",rawText="Mira Sen Dev Rao",backRawText="Northline Studio",sourceScanId="one-scan")
        val second=first.copy(id="multi-two",name="Dev Rao",phone="9123456780")
        fun json(card:Card)=JSONObject(card.record()+("id" to card.id))
        val handle=SavedStateHandle(mapOf("draftOwner" to "demo","draft" to json(first).toString(),
            "pendingPeople" to JSONArray(listOf(json(second))).toString(),
            "original" to front.path,"preview" to front.path,"mime" to "image/jpeg",
            "backOriginal" to back.path,"backPreview" to back.path,"backMime" to "image/jpeg"))
        val store=ViewModelStore()
        val vm=withContext(Dispatchers.Main) { VaultViewModel(app,handle).also {store.put("test",it)} }
        try {
            assertEquals(1,vm.state.value.remainingPeople)
            withContext(Dispatchers.Main) {vm.save()}
            withTimeout(10000) {vm.state.first {it.busy==null && it.draft?.id==second.id}}
            assertTrue(front.exists()); assertTrue(back.exists())
            withContext(Dispatchers.Main) {vm.save()}
            val saved=withTimeout(10000) {vm.state.first {it.busy==null && it.draft==null}}
            assertEquals(2,saved.cards.count {it.sourceScanId=="one-scan"})
            assertFalse(front.exists()); assertFalse(back.exists())
            val kept=saved.cards.single {it.id==second.id}
            val bytes=vm.photo(kept,true)
            assertNotNull(bytes)
            withContext(Dispatchers.Main) {vm.delete(saved.cards.single {it.id==first.id})}
            withTimeout(10000) {vm.state.first {it.busy==null && it.cards.none {card -> card.id==first.id}}}
            assertArrayEquals(bytes,vm.photo(kept,true))
            assertEquals("9123456780",vm.state.value.cards.single {it.id==second.id}.phone)
        } finally {
            withContext(Dispatchers.Main) {store.clear()}
            front.delete(); back.delete()
        }
    }
}
