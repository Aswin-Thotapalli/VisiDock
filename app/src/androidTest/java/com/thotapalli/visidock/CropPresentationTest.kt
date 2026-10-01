package com.thotapalli.visidock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class CropPresentationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    private fun cardFixture(name:String):File {
        val file=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,name)
        val image=Bitmap.createBitmap(1000,700,Bitmap.Config.ARGB_8888)
        Canvas(image).apply {drawColor(Color.rgb(25,48,85));drawRect(140f,140f,860f,560f,Paint().apply {color=Color.WHITE})}
        file.outputStream().use {image.compress(Bitmap.CompressFormat.JPEG,95,it)};image.recycle()
        return file
    }

    @Test fun busyCropDisablesEveryMutationAndKeepsCallbacksImmediateAfterRecovery() {
        org.junit.Assume.assumeTrue(BuildConfig.DEMO)
        val fixture=cardFixture("crop-busy-controls.jpg")
        val busy=mutableStateOf(false)
        var used=0;var retaken=0;var cancelled=0
        try {
            compose.runOnUiThread {compose.activity.setContent {VisiDockTheme {
                CropScreen(fixture.path,false,busy.value,null,{used++},{cancelled++},{retaken++})
            }}}
            compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("Cropped card preview").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Adjust corners").performClick()
            compose.runOnIdle {busy.value=true}
            compose.onNodeWithText("Cropping…").assertIsNotEnabled()
            compose.onNodeWithText("Cropped preview").assertIsNotEnabled()
            compose.onNodeWithText("Adjust corners").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Cancel crop").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Select Top left corner").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Move corner right").assertIsNotEnabled()
            compose.onNodeWithText("Reset to detected edges").assertIsNotEnabled()
            compose.onNodeWithText("Retake photo").assertIsNotEnabled()
            compose.runOnIdle {assertEquals(0,used+retaken+cancelled);busy.value=false}
            compose.onNodeWithText("Retake photo").performClick()
            compose.onNodeWithContentDescription("Cancel crop").performClick()
            compose.runOnIdle {assertEquals(1,retaken);assertEquals(1,cancelled);assertEquals(0,used)}
        } finally {fixture.delete()}
    }

    @Test fun draggingAndResetPreserveExactDetectedGeometryOnConfirmation() {
        org.junit.Assume.assumeTrue(BuildConfig.DEMO)
        val fixture=cardFixture("crop-drag-reset.jpg")
        val decoded=ImageCropper.decode(fixture,1600)
        val baseline=try {checkNotNull(ImageCropper.detect(decoded))} finally {decoded.recycle()}
        var confirmed:List<Float>?=null
        try {
            compose.runOnUiThread {compose.activity.setContent {VisiDockTheme {
                CropScreen(fixture.path,true,false,null,{confirmed=it},{},{})
            }}}
            compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("Cropped card preview").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Adjust corners").performClick()
            compose.onNodeWithContentDescription("Crop image. Four adjustable corners; use the corner controls below as an alternative to dragging.").performTouchInput {
                val scale=minOf(width/1000f,height/700f)
                val origin=Offset((width-1000*scale)/2,(height-700*scale)/2)
                val corner=origin+Offset(baseline[0]*1000*scale,baseline[1]*700*scale)
                swipe(corner,corner+Offset(44f,32f),300)
            }
            compose.onNodeWithText("Use card").performClick()
            compose.runOnIdle {assertTrue(ImageCropper.valid(checkNotNull(confirmed)));assertTrue(confirmed!![0]>baseline[0]);assertTrue(confirmed!![1]>baseline[1])}
            compose.onNodeWithText("Reset to detected edges").performClick()
            compose.onNodeWithText("Cropped preview").performClick()
            compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("Cropped card preview").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Use card").performClick()
            compose.runOnIdle {baseline.zip(checkNotNull(confirmed)).forEach {(expected,actual)->assertEquals(expected,actual,.00001f)}}
        } finally {fixture.delete()}
    }

    @Test fun accessibleCornerAdjustmentProducesValidCropAndScreenshot() {
        org.junit.Assume.assumeTrue(BuildConfig.DEMO)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val fixture=File(context.cacheDir,"crop-ui-fixture.jpg")
        val source=Bitmap.createBitmap(1000,700,Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(Color.rgb(44,68,110))
            drawRect(130f,135f,870f,565f,Paint().apply {color=Color.rgb(246,250,255)})
            drawText("MIRA SEN",195f,250f,Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(16,38,75);textSize=52f})
            drawText("Northline Studio",195f,320f,Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(16,38,75);textSize=32f})
            drawText("mira@example.test",195f,420f,Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(16,38,75);textSize=28f})
        }
        fixture.outputStream().use {source.compress(Bitmap.CompressFormat.JPEG,95,it)};source.recycle()
        val decoded=ImageCropper.decode(fixture,1600)
        val baseline=try {ImageCropper.detect(decoded) ?: listOf(0f,0f,1f,0f,1f,1f,0f,1f)} finally {decoded.recycle()}
        var confirmed:List<Float>?=null
        try {
            compose.runOnUiThread {compose.activity.setContent {VisiDockTheme {
                CropScreen(fixture.path,false,false,null,{confirmed=it},{},{})
            }}}
            compose.waitUntil(10000) {compose.onAllNodesWithText("Use card").fetchSemanticsNodes().any {!it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)}}
            compose.onNodeWithText("Adjust corners").performClick()
            compose.onNodeWithContentDescription("Select Top left corner").performClick()
            compose.onNodeWithContentDescription("Move corner right").performClick()
            compose.onNodeWithContentDescription("Move corner down").performClick()
            compose.onNodeWithContentDescription("Select Bottom right corner").performClick()
            compose.onNodeWithContentDescription("Move corner left").performClick()
            compose.onNodeWithContentDescription("Move corner up").performClick()
            compose.onNodeWithText("Use card").assertIsEnabled()
            compose.waitForIdle()
            val screenshot=instrumentation.uiAutomation.takeScreenshot()
            assertNotNull(screenshot)
            val evidence=File(context.filesDir,"crop-review-ui.png")
            screenshot!!.let {image->try {evidence.outputStream().use {assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it))}} finally {image.recycle()}}
            compose.onNodeWithText("Use card").performClick()
            compose.runOnIdle {
                val points=checkNotNull(confirmed)
                assertTrue(ImageCropper.valid(points))
                assertEquals(baseline[0]+.005f,points[0],.0001f);assertEquals(baseline[1]+.005f,points[1],.0001f)
                assertEquals(baseline[4]-.005f,points[4],.0001f);assertEquals(baseline[5]-.005f,points[5],.0001f)
            }
        } finally {fixture.delete()}
    }
}
