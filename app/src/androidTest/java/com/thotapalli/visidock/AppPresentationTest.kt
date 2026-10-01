package com.thotapalli.visidock

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.window.DialogWindowProvider
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real Android renders, with explicitly fictional demo card images. No model/network required. */
class AppPresentationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    private fun screenshot(name:String,dialogText:String?=null) {
        compose.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val suffix=InstrumentationRegistry.getArguments().getString("evidenceSuffix", "light").replace(Regex("[^a-zA-Z0-9_-]"), "")
        val file=File(instrumentation.targetContext.getExternalFilesDir(null) ?: instrumentation.targetContext.filesDir,"ui-review/$name-$suffix.png").apply {parentFile?.mkdirs()}
        // Compose's node capture can resolve the activity window even when the node
        // belongs to a modal root. Copy that dialog's actual Window explicitly.
        val bitmap=if(dialogText==null) compose.onRoot().captureToImage().asAndroidBitmap() else {
            compose.onNodeWithText(dialogText).assertIsDisplayed()
            captureDialogWindow()
        }
        try {file.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}} finally {bitmap.recycle()}
    }

    private fun captureDialogWindow():Bitmap {
        val copied=CountDownLatch(1)
        val result=AtomicInteger(-1)
        lateinit var bitmap:Bitmap
        fun windowOf(view:View):Window? {
            if(view is DialogWindowProvider) return view.window
            if(view is ViewGroup) for(index in 0 until view.childCount) windowOf(view.getChildAt(index))?.let {return it}
            return null
        }
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(object:androidx.test.espresso.ViewAction {
                override fun getDescription()="Copy the visible Compose modal window"
                override fun getConstraints():org.hamcrest.Matcher<View> = androidx.test.espresso.matcher.ViewMatchers.isRoot()
                override fun perform(uiController:androidx.test.espresso.UiController,view:View) {
                    uiController.loopMainThreadUntilIdle()
                    val window=checkNotNull(windowOf(view)) {"The modal root did not expose its own Window"}
                    bitmap=Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888)
                    PixelCopy.request(window,bitmap,{status->result.set(status);copied.countDown()},Handler(Looper.getMainLooper()))
                }
            })
        assertTrue("The modal frame was not delivered",copied.await(10,TimeUnit.SECONDS))
        assertEquals("Copying the modal window failed",PixelCopy.SUCCESS,result.get())
        return bitmap
    }

    @Test fun collectionDetailBackSettingsEditorAndCaptureReviewRender() {
        org.junit.Assume.assumeTrue(BuildConfig.DEMO)
        lateinit var vm:VaultViewModel
        compose.runOnUiThread {vm=ViewModelProvider(compose.activity)[VaultViewModel::class.java]}
        compose.waitUntil(15000) {vm.state.value.cards.isNotEmpty() && !vm.state.value.loading}
        val card=vm.state.value.cards.first {it.imagePath.isNotBlank() && it.backImagePath.isNotBlank()}
        compose.waitUntil(15000) {compose.onAllNodesWithContentDescription("Card photo for ${card.displayLabel}").fetchSemanticsNodes().isNotEmpty()}
        screenshot("phone-collection")
        compose.runOnIdle {vm.select(card)}
        compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("Front of visiting card for ${card.displayLabel}").fetchSemanticsNodes().isNotEmpty()}
        screenshot("phone-detail-front")
        compose.onNodeWithText("Turn to back").performScrollTo().performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("Back of visiting card for ${card.displayLabel}").fetchSemanticsNodes().isNotEmpty()}
        screenshot("phone-detail-back")
        compose.onNodeWithText("Manage card photos").performScrollTo().performClick()
        compose.onNodeWithText("Crop back").assertExists()
        screenshot("phone-photo-actions",dialogText="Crop back")
        compose.activityRule.scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
        compose.runOnIdle {vm.select(null)}
        compose.onNode(hasText("Settings") and hasClickAction()).performClick()
        screenshot("phone-settings")
        compose.onNode(hasText("Collection") and hasClickAction()).performClick()
        compose.runOnIdle {vm.edit(card)}
        compose.waitUntil(10000) {compose.onAllNodesWithText("Update card details").fetchSemanticsNodes().isNotEmpty()}
        screenshot("phone-editor")
        compose.runOnIdle {vm.discard();vm.beginCapture()}
        val source=File(compose.activity.cacheDir,"presentation-source.jpg").apply {writeBytes(DemoCardImages.preview(card))}
        try {
            compose.runOnIdle {vm.stageCrop(Uri.fromFile(source))}
            compose.waitUntil(10000) {vm.state.value.cropPath!=null && vm.state.value.busy==null}
            compose.runOnIdle {vm.useCrop(listOf(0f,0f,1f,0f,1f,1f,0f,1f))}
            compose.waitUntil(10000) {vm.state.value.captureReview && vm.state.value.busy==null}
            compose.onNodeWithText("Anything on the back?").assertExists()
            compose.onNodeWithText("Continue with front").assertIsEnabled()
            screenshot("phone-capture-review")
            val capturedPreview=checkNotNull(vm.state.value.draftPreview)
            compose.onNodeWithText("Enter details instead").assertIsEnabled().performClick()
            compose.waitUntil(10000) {!vm.state.value.captureReview && vm.state.value.draft!=null}
            compose.onNodeWithText("Full name").performScrollTo().assertExists()
            assertEquals("Manual entry must retain the cropped photo",capturedPreview,vm.state.value.draftPreview)
            assertTrue(File(capturedPreview).isFile)
        } finally {source.delete()}
    }
}
