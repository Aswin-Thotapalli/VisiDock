package com.thotapalli.visidock

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class CardPresentationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @Test fun twoSidedDraftCanTurnBothWaysWithoutLosingEditedIdentity() {
        assumeTrue(BuildConfig.DEMO)
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val front=File(ImagePipeline.directory(app),"presentation-front.jpg")
        val back=File(ImagePipeline.directory(app),"presentation-back.jpg")
        val image=Bitmap.createBitmap(240,140,Bitmap.Config.ARGB_8888)
        image.eraseColor(0xff10264b.toInt())
        front.outputStream().use {image.compress(Bitmap.CompressFormat.JPEG,90,it)}
        image.eraseColor(0xff087c65.toInt())
        back.outputStream().use {image.compress(Bitmap.CompressFormat.JPEG,90,it)}
        image.recycle()
        val draft=Card(id="presentation-card",name="Mira Sen",rawText="Mira Sen",backRawText="Northline Studio")
        val handle=SavedStateHandle(mapOf("draftOwner" to "demo","draft" to JSONObject(draft.record()+("id" to draft.id)).toString(),
            "original" to front.path,"preview" to front.path,"mime" to "image/jpeg",
            "backOriginal" to back.path,"backPreview" to back.path,"backMime" to "image/jpeg"))
        val store=ViewModelStore()
        try {
            compose.runOnUiThread {
                val vm=VaultViewModel(app,handle).also {store.put("presentation",it)}
                compose.activity.setContent {VisiDockTheme {VaultScreen(vm)}}
            }
            compose.onNodeWithText("Turn to back").performScrollTo().performClick()
            compose.onNodeWithText("Turn to front").assertExists()
            compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("Back of visiting card for Mira Sen").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithContentDescription("Back of visiting card for Mira Sen").assertExists()
            compose.onNodeWithText("Turn to front").performClick()
            compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("Front of visiting card for Mira Sen").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithContentDescription("Front of visiting card for Mira Sen").assertExists()
            compose.onNodeWithText("Full name").performScrollTo().performTextReplacement("Mira Shah")
            compose.onNodeWithText("Turn to back").performScrollTo().performClick()
            compose.onNodeWithText("Full name").performScrollTo()
            compose.onNodeWithText("Mira Shah").assertExists()
            assertTrue(front.exists());assertTrue(back.exists())
        } finally {
            compose.runOnUiThread {store.clear()}
            front.delete();back.delete()
        }
    }

    @Test fun cameraOpensInsideAppAndCanClose() {
        assumeTrue(BuildConfig.DEMO)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("pm grant ${instrumentation.targetContext.packageName} android.permission.CAMERA")).use {it.readBytes()}
        compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("Add card").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("Add card").performClick()
        compose.onNodeWithText("Take a photo").performClick()
        compose.onNodeWithContentDescription("Capture card").assertExists()
        compose.onNodeWithText("Front of card · keep all edges in view").assertExists()
        compose.onNodeWithContentDescription("Close camera").performClick()
        compose.onNodeWithContentDescription("Add card").assertExists()
    }
}
