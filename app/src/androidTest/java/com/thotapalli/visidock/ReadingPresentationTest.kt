package com.thotapalli.visidock

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReadingPresentationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @Test fun frontBackJointAndCancelledHandoffKeepTheActualPhotos() {
        org.junit.Assume.assumeTrue(BuildConfig.DEMO)
        lateinit var vm:VaultViewModel
        compose.runOnUiThread {vm=ViewModelProvider(compose.activity)[VaultViewModel::class.java]}
        compose.waitUntil(15000) {vm.state.value.cards.any {it.backImagePath.isNotBlank()}}
        val card=vm.state.value.cards.first {it.backImagePath.isNotBlank()}
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val front=File(context.cacheDir,"reading-motion-front.jpg").apply {writeBytes(DemoCardImages.preview(card,false))}
        val back=File(context.cacheDir,"reading-motion-back.jpg").apply {writeBytes(DemoCardImages.preview(card,true))}
        val stage=mutableIntStateOf(0)
        val hasBack=mutableStateOf(true)
        val reading=mutableStateOf(true)
        var cancels=0
        try {
            compose.runOnUiThread {compose.activity.setContent {VisiDockTheme {
                AnimatedContent(reading.value,label="Reading handoff fixture",transitionSpec={fadeIn(DockMotion.spec(180)) togetherWith fadeOut(DockMotion.spec(180))}) {visible->
                    if(visible) ReadingStage(card,vm,front.path,back.path.takeIf {hasBack.value},stage.intValue,"Fixture reading",reading.value) {cancels++;reading.value=false}
                    else Text("Review retained photos")
                }
            }}}
            compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("Front of card being read").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithContentDescription("Back of card being read").assertDoesNotExist()
            compose.onNodeWithTag("reading-light").assertExists()
            frame("reading-front")
            compose.mainClock.autoAdvance=false
            compose.runOnIdle {stage.intValue=1}
            compose.mainClock.advanceTimeBy(100)
            frame("reading-turn-midpoint")
            compose.mainClock.advanceTimeBy(1000)
            compose.onNodeWithContentDescription("Back of card being read").assertExists()
            compose.onNodeWithContentDescription("Front of card being read").assertDoesNotExist()
            compose.onNodeWithTag("reading-light").assertExists()
            frame("reading-back")
            compose.runOnIdle {stage.intValue=2}
            compose.mainClock.advanceTimeBy(1000)
            compose.onNodeWithContentDescription("Back of card being read").assertExists()
            compose.onNodeWithContentDescription("Front of card being read").assertDoesNotExist()
            compose.onNodeWithContentDescription("Front included in reading").assertDoesNotExist()
            compose.onNodeWithContentDescription("Back included in reading").assertDoesNotExist()
            compose.onNodeWithTag("reading-light").assertExists()
            compose.onNodeWithText("Organizing details from both sides").assertExists()
            frame("reading-joint")
            // A one-sided card never reveals an empty reverse during model processing.
            compose.runOnIdle {hasBack.value=false;stage.intValue=0}
            compose.mainClock.advanceTimeBy(1000)
            compose.onNodeWithContentDescription("Front of card being read").assertExists()
            compose.onNodeWithTag("reading-light").assertExists()
            compose.runOnIdle {stage.intValue=2}
            compose.mainClock.advanceTimeBy(1000)
            compose.onNodeWithContentDescription("Front of card being read").assertExists()
            compose.onNodeWithContentDescription("Back of card being read").assertDoesNotExist()
            compose.onNodeWithTag("reading-light").assertExists()
            compose.onNodeWithText("Organizing the details").assertExists()
            frame("reading-single-joint")
            compose.onNodeWithContentDescription("Cancel reading").performClick()
            compose.mainClock.advanceTimeBy(80)
            frame("reading-cancel-handoff")
            compose.mainClock.advanceTimeBy(1000)
            compose.onNodeWithTag("reading-stage").assertDoesNotExist()
            compose.onNodeWithText("Review retained photos").assertExists()
            assertEquals(1,cancels)
        } finally {compose.mainClock.autoAdvance=true;front.delete();back.delete()}
    }

    private fun frame(name:String) {
        compose.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        val target=File(instrumentation.targetContext.getExternalFilesDir(null) ?: instrumentation.targetContext.filesDir,"ui-review/$name.png").apply {parentFile?.mkdirs()}
        try {target.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}} finally {bitmap.recycle()}
    }
}
