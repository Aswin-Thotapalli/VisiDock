package com.thotapalli.visidock

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
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
    @get:Rule(order=0) val animations=EnabledAnimationsRule()
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()

    private data class Fixture(val vm:VaultViewModel,val card:Card,val front:File,val back:File) {
        fun close() {front.delete();back.delete()}
    }
    private fun fixture():Fixture {
        org.junit.Assume.assumeTrue(BuildConfig.DEMO)
        lateinit var vm:VaultViewModel
        compose.runOnUiThread {vm=ViewModelProvider(compose.activity)[VaultViewModel::class.java]}
        compose.waitUntil(15000) {vm.state.value.cards.any {it.backImagePath.isNotBlank()}}
        val card=vm.state.value.cards.first {it.backImagePath.isNotBlank()}
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        return Fixture(vm,card,
            File(context.cacheDir,"reading-motion-front.jpg").apply {writeBytes(DemoCardImages.preview(card,false))},
            File(context.cacheDir,"reading-motion-back.jpg").apply {writeBytes(DemoCardImages.preview(card,true))})
    }
    private fun exists(description:String)=compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
    private fun advanceUntilVisible(timeoutMillis:Long,description:String,condition:()->Boolean) {
        val deadline=compose.mainClock.currentTime+timeoutMillis
        // Semantics fetching synchronizes with Compose and must stay on the test
        // thread. MainTestClock.advanceTimeUntil evaluates its predicate on main.
        while(!condition() && compose.mainClock.currentTime<deadline) {
            compose.mainClock.advanceTimeByFrame()
        }
        assertTrue("Timed out after $timeoutMillis virtual milliseconds: $description",condition())
    }
    private fun waitForFirstFront() {
        // Image decoding runs outside Compose's clock. Pump a single frame per poll,
        // stopping as soon as it appears; never auto-advance through the optical sweep.
        compose.waitUntil(15000) {
            compose.mainClock.advanceTimeByFrame()
            exists("Front of card being read")
        }
    }

    @Test fun alreadyCompletedBackendStillPresentsFrontThenBackThenReviewHandoff() {
        val f=fixture()
        var presentations=0
        val regions=listOf(OcrRegion("Mira Sen",.1f,.15f,.7f,.25f,0),
            OcrRegion("mira@example.com",.1f,.4f,.8f,.5f,1),
            OcrRegion("12 Lake Road",.1f,.6f,.8f,.7f,1))
        compose.mainClock.autoAdvance=false
        try {
            compose.runOnUiThread {compose.activity.setContent {VisiDockTheme {
                ReadingStage(f.card,f.vm,f.front.path,f.back.path,2,"Fixture reading",true,
                    regions=regions,analysisReady=true,onPresented={presentations++},onCancel={})
            }}}
            waitForFirstFront()
            compose.onNodeWithContentDescription("Back of card being read").assertDoesNotExist()
            compose.onNodeWithTag("reading-light").assertExists()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"1 regions on front"))
            assertEquals(0,presentations)
            compose.mainClock.advanceTimeBy(1000)
            frame("reading-front")
            compose.mainClock.advanceTimeBy(2500)
            compose.onNodeWithContentDescription("Front of card being read").assertExists()
            compose.onNodeWithContentDescription("Back of card being read").assertDoesNotExist()
            compose.onNodeWithText("Scans ready").assertDoesNotExist()
            // A backend already at stage2 cannot skip either physical side's sweep.
            advanceUntilVisible(1800,"back photograph") {exists("Back of card being read")}
            frame("reading-turn-midpoint")
            compose.mainClock.advanceTimeBy(1000)
            compose.onNodeWithContentDescription("Back of card being read").assertExists()
            compose.onNodeWithContentDescription("Front of card being read").assertDoesNotExist()
            compose.onNodeWithTag("reading-light").assertExists()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"2 regions on back"))
            compose.onNodeWithText("Scans ready").assertDoesNotExist()
            assertEquals(0,presentations)
            frame("reading-back")
            advanceUntilVisible(5000,"completed optical scans") {
                compose.onAllNodesWithText("Scans ready").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("reading-light").assertDoesNotExist()
            assertEquals("Presentation completes after the review handoff, not when the beam finishes",0,presentations)
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithText("Check the details, then save").assertExists()
            frame("reading-review-handoff")
            assertEquals(0,presentations)
            compose.mainClock.advanceTimeBy(600)
            assertEquals(1,presentations)
            compose.mainClock.advanceTimeBy(1000)
            assertEquals("Completion must be delivered once",1,presentations)
        } finally {compose.mainClock.autoAdvance=true;f.close()}
    }

    @Test fun cancellingSingleSideSweepRetainsPhotosAndDoesNotCompletePresentation() {
        val f=fixture()
        val reading=mutableStateOf(true)
        var cancels=0
        var presentations=0
        compose.mainClock.autoAdvance=false
        try {
            compose.runOnUiThread {compose.activity.setContent {VisiDockTheme {
                AnimatedContent(reading.value,label="Reading handoff fixture",transitionSpec={fadeIn(DockMotion.spec(180)) togetherWith fadeOut(DockMotion.spec(180))}) {visible->
                    if(visible) ReadingStage(f.card,f.vm,f.front.path,null,2,"Fixture reading",reading.value,
                        onPresented={presentations++},onCancel={cancels++;reading.value=false})
                    else Text("Review retained photos")
                }
            }}}
            waitForFirstFront()
            compose.mainClock.advanceTimeBy(1500)
            compose.onNodeWithContentDescription("Front of card being read").assertExists()
            compose.onNodeWithContentDescription("Back of card being read").assertDoesNotExist()
            compose.onNodeWithTag("reading-light").assertExists()
            compose.onNodeWithContentDescription("Cancel reading").performClick()
            compose.mainClock.advanceTimeBy(80)
            frame("reading-cancel-handoff")
            compose.mainClock.advanceTimeBy(1000)
            compose.onNodeWithTag("reading-stage").assertDoesNotExist()
            compose.onNodeWithText("Review retained photos").assertExists()
            assertEquals(1,cancels)
            assertEquals(0,presentations)
            assertTrue(f.front.isFile && f.back.isFile)
        } finally {compose.mainClock.autoAdvance=true;f.close()}
    }

    private fun frame(name:String) {
        compose.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        val target=File(instrumentation.targetContext.getExternalFilesDir(null) ?: instrumentation.targetContext.filesDir,"ui-review/$name.png").apply {parentFile?.mkdirs()}
        try {target.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}} finally {bitmap.recycle()}
    }
}
