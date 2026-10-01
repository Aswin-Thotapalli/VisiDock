package com.thotapalli.visidock

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class CardMotionTest {
    @get:Rule(order=0) val animations=EnabledAnimationsRule()
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    private fun frame(name:String) {
        compose.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        val target=File(instrumentation.targetContext.getExternalFilesDir(null) ?: instrumentation.targetContext.filesDir,"ui-review/$name.png").apply {parentFile?.mkdirs()}
        try {target.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}} finally {bitmap.recycle()}
    }

    @Test fun fingerTrackedFlipSettlesAndCanBeInterrupted() {
        org.junit.Assume.assumeTrue(BuildConfig.DEMO)
        assertTrue("Motion coverage requires enabled system animations",android.animation.ValueAnimator.areAnimatorsEnabled())
        lateinit var vm:VaultViewModel
        compose.runOnUiThread {vm=ViewModelProvider(compose.activity)[VaultViewModel::class.java]}
        compose.waitUntil(15000) {vm.state.value.cards.any {it.backImagePath.isNotBlank()}}
        val card=vm.state.value.cards.first {it.backImagePath.isNotBlank()}
        compose.runOnIdle {vm.select(card)}
        compose.waitUntil(15000) {compose.onAllNodesWithContentDescription("Front of visiting card for ${card.displayLabel}").fetchSemanticsNodes().isNotEmpty()}
        compose.waitForIdle()
        val photo=compose.onNodeWithTag("card-flip-${card.id}")
        compose.mainClock.autoAdvance=false
        try {
            frame("motion-flip-front")
            photo.performTouchInput {
                down(Offset(width*.85f,centerY))
                moveTo(Offset(width*.62f,centerY),delayMillis=160)
            }
            compose.mainClock.advanceTimeByFrame()
            photo.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Showing front"))
            frame("motion-flip-finger-tracked")
            photo.performTouchInput {moveTo(Offset(width*.08f,centerY),delayMillis=240);up()}
            compose.mainClock.advanceTimeBy(1200)
            photo.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Showing back"))
            compose.onNodeWithContentDescription("Back of visiting card for ${card.displayLabel}").assertExists()
            frame("motion-flip-back")
            // A short drag settles back to its original face; touch cancel uses the same rule.
            photo.performTouchInput {down(center);moveTo(Offset(width*.60f,centerY),delayMillis=220);cancel()}
            compose.mainClock.advanceTimeBy(1200)
            photo.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Showing back"))
            compose.onNodeWithText("Turn to front").performClick()
            compose.mainClock.advanceTimeBy(16)
            compose.onNodeWithText("Turn to back").performClick()
            compose.mainClock.advanceTimeBy(1200)
            photo.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Showing back"))
            compose.onNodeWithText("Turn to front").performClick()
            compose.mainClock.advanceTimeBy(1200)
            photo.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Showing front"))
        } finally {compose.mainClock.autoAdvance=true}
    }
}
