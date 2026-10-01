package com.thotapalli.visidock

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class CaptureSessionTest {
    @Test fun savedSingleSidedCardAcceptsBackAndCanRecropWithoutChangingIdentity()=runBlocking {
        assumeTrue(BuildConfig.DEMO)
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val store=ViewModelStore()
        val input=File(app.cacheDir,"new-card-back.jpg")
        try {
            val vm=withContext(Dispatchers.Main) {VaultViewModel(app,SavedStateHandle()).also {store.put("edit-sides",it)}}
            val state=withTimeout(10000) {vm.state.first {!it.loading && it.cards.isNotEmpty()}}
            val card=state.cards.first {it.imagePath.isNotBlank() && it.backImagePath.isBlank()}
            val originalFront=vm.photo(card)
            input.writeBytes(DemoCardImages.preview(card,true))
            withContext(Dispatchers.Main) {vm.prepareSideEdit(card,true)}
            assertEquals("Opening or cancelling a camera must not revise saved metadata",card.sourceScanId,vm.state.value.draft!!.sourceScanId)
            withContext(Dispatchers.Main) {vm.stageCrop(Uri.fromFile(input),true)}
            withTimeout(10000) {vm.state.first {it.cropPath!=null && it.busy==null}}
            withContext(Dispatchers.Main) {vm.useCrop(listOf(0f,0f,1f,0f,1f,1f,0f,1f))}
            val edited=withTimeout(10000) {vm.state.first {it.cropPath==null && it.busy==null}}
            assertFalse(edited.captureReview);assertEquals(card.id,edited.draft!!.id)
            assertEquals(card.name,edited.draft.name);assertEquals(card.email,edited.draft.email)
            withContext(Dispatchers.Main) {vm.save()}
            val saved=withTimeout(10000) {vm.state.first {it.draft==null && it.busy==null}}.cards.first {it.id==card.id}
            assertTrue(saved.backImagePath.isNotBlank());assertArrayEquals(originalFront,vm.photo(saved))
            withContext(Dispatchers.Main) {vm.recrop(saved,true)}
            withTimeout(10000) {vm.state.first {it.cropPath!=null && it.busy==null}}
            withContext(Dispatchers.Main) {vm.useCrop(listOf(.1f,.1f,.9f,.1f,.9f,.9f,.1f,.9f))}
            withTimeout(10000) {vm.state.first {it.cropPath==null && it.busy==null}}
            withContext(Dispatchers.Main) {vm.save()}
            val recropped=withTimeout(10000) {vm.state.first {it.draft==null && it.busy==null}}.cards.first {it.id==card.id}
            assertEquals(card.name,recropped.name);assertArrayEquals(originalFront,vm.photo(recropped))
            val bitmap=android.graphics.BitmapFactory.decodeByteArray(vm.photo(recropped,true)!!,0,vm.photo(recropped,true)!!.size)
            try {assertEquals(800,bitmap.width);assertEquals(480,bitmap.height)} finally {bitmap.recycle()}
        } finally {withContext(Dispatchers.Main) {store.clear()};input.delete()}
    }
    @Test fun bothCropsArePreparedBeforeAnyExtractionAndSurviveRestore()=runBlocking {
        assumeTrue(BuildConfig.DEMO)
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val gallery=File(app.cacheDir,"capture-session-fixture.jpg")
        val bitmap=Bitmap.createBitmap(400,240,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        gallery.outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)};bitmap.recycle()
        val handle=SavedStateHandle()
        val store=ViewModelStore();val restoredStore=ViewModelStore()
        try {
            val vm=withContext(Dispatchers.Main) {VaultViewModel(app,handle).also {store.put("session",it);it.beginCapture()}}
            val id=vm.state.value.draft!!.id
            for(back in listOf(false,true)) {
                withContext(Dispatchers.Main) {vm.stageCrop(Uri.fromFile(gallery),back)}
                withTimeout(10000) {vm.state.first {it.cropPath!=null && it.busy==null}}
                withContext(Dispatchers.Main) {vm.useCrop(listOf(0f,0f,1f,0f,1f,1f,0f,1f))}
                val staged=withTimeout(10000) {vm.state.first {it.cropPath==null && it.busy==null}}
                assertTrue(staged.captureReview)
                assertEquals(id,staged.draft!!.id)
                assertEquals("",staged.draft.rawText)
                assertEquals("",staged.draft.backRawText)
                assertNull(staged.scanSide)
                assertNotNull(staged.draftPreview)
                if(back) assertNotNull(staged.draftBackPreview)
            }
            val front=File(vm.state.value.draftPreview!!);val back=File(vm.state.value.draftBackPreview!!)
            val restored=withContext(Dispatchers.Main) {
                val snapshot=handle.keys().associateWith {handle.get<Any?>(it)}
                store.clear()
                VaultViewModel(app,SavedStateHandle(snapshot)).also {restoredStore.put("session",it)}
            }
            assertTrue(restored.state.value.captureReview)
            assertEquals(front.path,restored.state.value.draftPreview)
            assertEquals(back.path,restored.state.value.draftBackPreview)
            withContext(Dispatchers.Main) {restored.reviewCapturedManually()}
            assertFalse(restored.state.value.captureReview)
            assertEquals(front.path,restored.state.value.draftPreview)
            assertEquals(back.path,restored.state.value.draftBackPreview)
            assertTrue(restored.state.value.draft!!.sourceScanId.isNotBlank())
            withContext(Dispatchers.Main) {restored.cancelCapture()}
            assertFalse(front.exists());assertFalse(back.exists());assertTrue(gallery.exists())
        } finally {withContext(Dispatchers.Main) {store.clear();restoredStore.clear()};gallery.delete()}
    }
}
