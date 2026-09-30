package com.thotapalli.visidock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class CropPresentationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

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
        var confirmed:List<Float>?=null
        try {
            compose.runOnUiThread {compose.activity.setContent {VisiDockTheme {
                CropScreen(fixture.path,false,false,null,{confirmed=it},{},{})
            }}}
            compose.waitUntil(10000) {compose.onAllNodesWithText("Use card").fetchSemanticsNodes().any {!it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)}}
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
                assertEquals(.085f,points[0],.0001f);assertEquals(.125f,points[1],.0001f)
                assertEquals(.915f,points[4],.0001f);assertEquals(.875f,points[5],.0001f)
            }
        } finally {fixture.delete()}
    }
}
