package com.thotapalli.visidock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ImageCropperTest {
    @Test fun detectsLightAndDarkPerspectiveCardsWithoutSelectingPrintedText() {
        for ((background,card) in listOf(Color.rgb(22,45,80) to Color.WHITE,Color.rgb(225,225,220) to Color.rgb(30,45,70))) {
            val image=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888)
            val expected=listOf(.15f,.2f,.85f,.15f,.82f,.8f,.12f,.85f)
            Canvas(image).apply {
                drawColor(background)
                drawPath(Path().apply {moveTo(96f,96f);lineTo(544f,72f);lineTo(525f,384f);lineTo(77f,408f);close()},Paint().apply {color=card})
                drawText("Ananya Rao",180f,200f,Paint().apply {color=background;textSize=32f})
                drawText("hello@example.com",180f,250f,Paint().apply {color=background;textSize=20f})
            }
            try {
                val actual=ImageCropper.detect(image)
                assertNotNull("A high contrast card should have a proposal",actual)
                expected.zip(actual!!).forEach {(wanted,found)->assertEquals(wanted,found,.025f)}
            } finally {image.recycle()}
        }
    }
    @Test fun findsRotatedPerspectiveCardOnTexturedAndLowContrastSurfaces() {
        val expected=listOf(.20f,.16f,.88f,.29f,.76f,.84f,.10f,.66f)
        for(lowContrast in listOf(false,true)) {
            val image=Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(image)
            // Uneven illumination and wood-like stripes invalidate a single border-color assumption.
            for(y in 0 until image.height) {
                val base=if(lowContrast) 178 else 70
                val level=base+y*24/image.height
                canvas.drawLine(0f,y.toFloat(),800f,y.toFloat(),Paint().apply {color=Color.rgb(level,level-5,level-12)})
            }
            for(x in 0..800 step 13) canvas.drawLine(x.toFloat(),0f,x+70f,600f,
                Paint().apply {color=if(lowContrast) Color.rgb(158,156,150) else Color.rgb(110,89,62);strokeWidth=2f})
            val shape=Path().apply {moveTo(160f,96f);lineTo(704f,174f);lineTo(608f,504f);lineTo(80f,396f);close()}
            canvas.drawPath(shape,Paint().apply {color=if(lowContrast) Color.rgb(213,212,208) else Color.rgb(243,244,237)})
            canvas.save();canvas.clipPath(shape)
            canvas.rotate(10f,400f,300f)
            canvas.drawText("Asha Menon",230f,250f,Paint().apply {color=Color.rgb(25,43,72);textSize=39f})
            canvas.drawText("DESIGN DIRECTOR",230f,295f,Paint().apply {color=Color.rgb(65,76,85);textSize=20f})
            canvas.drawText("asha@example.test",230f,360f,Paint().apply {color=Color.rgb(25,43,72);textSize=22f})
            canvas.restore()
            try {
                val actual=ImageCropper.detect(image)
                assertNotNull("Card edges should survive textured background; lowContrast=$lowContrast",actual)
                expected.zip(actual!!).forEach {(wanted,found)->assertEquals(wanted,found,.035f)}
                val cropped=ImageCropper.warp(image,actual)
                try {assertTrue("Perspective result must be a landscape card",cropped.width>cropped.height)}
                finally {cropped.recycle()}
            } finally {image.recycle()}
        }
    }

    @Test fun openEdgesAndBackgroundTextureDoNotFormAnAutomaticCard() {
        val image=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(Color.rgb(191,177,153))
            for(x in 0..640 step 17) drawLine(x.toFloat(),0f,x+80f,480f,
                Paint().apply {color=Color.rgb(153,133,107);strokeWidth=3f})
            // Three disconnected edges must not be treated as a confirmed four-corner object.
            drawLine(100f,100f,520f,100f,Paint().apply {color=Color.WHITE;strokeWidth=3f})
            drawLine(100f,100f,100f,360f,Paint().apply {color=Color.WHITE;strokeWidth=3f})
        }
        try {assertNull(ImageCropper.detect(image))} finally {image.recycle()}
    }

    @Test fun blankAndAmbiguousFullFrameImagesDoNotInventACrop() {
        val image=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888)
        try {image.eraseColor(Color.WHITE);assertNull(ImageCropper.detect(image))
            Canvas(image).drawText("Just text",20f,100f,Paint().apply {color=Color.BLUE;textSize=24f})
            assertNull(ImageCropper.detect(image))
        } finally {image.recycle()}
    }
    @Test fun stagedCropRestoresWithoutGalleryPermissionAndCancelDeletesOnlyPrivateCopy()=runBlocking {
        org.junit.Assume.assumeTrue(BuildConfig.DEMO)
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
        val gallery=File(app.cacheDir,"crop-restore-gallery.jpg")
        val image=Bitmap.createBitmap(300,180,Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.WHITE)
        gallery.outputStream().use {image.compress(Bitmap.CompressFormat.JPEG,90,it)};image.recycle()
        val handle=androidx.lifecycle.SavedStateHandle()
        val firstStore=androidx.lifecycle.ViewModelStore();val secondStore=androidx.lifecycle.ViewModelStore()
        try {
            val vm=withContext(Dispatchers.Main) {VaultViewModel(app,handle).also {firstStore.put("crop",it)}}
            withContext(Dispatchers.Main) {vm.stageCrop(Uri.fromFile(gallery))}
            val staged=withTimeout(10000) {vm.state.first {it.cropPath!=null && it.busy==null}}
            val privateFile=File(checkNotNull(staged.cropPath))
            assertTrue(privateFile.exists());assertTrue(gallery.exists())
            val restored=withContext(Dispatchers.Main) {
                val snapshot=handle.keys().associateWith {handle.get<Any?>(it)}
                firstStore.clear()
                VaultViewModel(app,androidx.lifecycle.SavedStateHandle(snapshot)).also {secondStore.put("crop",it)}
            }
            assertEquals(privateFile.path,restored.state.value.cropPath)
            withContext(Dispatchers.Main) {restored.cancelCrop()}
            assertFalse(privateFile.exists());assertTrue(gallery.exists())
            assertNull(restored.state.value.cropPath)
        } finally {withContext(Dispatchers.Main) {firstStore.clear();secondStore.clear()};gallery.delete()}
    }
    @Test fun perspectiveWarpRemovesBackgroundAndRejectsCrossedCorners() {
        val image=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.BLUE)
        val points=listOf(.15f,.18f,.88f,.1f,.85f,.8f,.1f,.9f)
        val shape=Path().apply {moveTo(60f,54f);lineTo(352f,30f);lineTo(340f,240f);lineTo(40f,270f);close()}
        Canvas(image).drawPath(shape,Paint().apply {color=Color.RED})
        val output=ImageCropper.warp(image,points)
        try {
            assertTrue(output.width in 290..320);assertTrue(output.height in 205..225)
            listOf(10 to 10,output.width-11 to 10,10 to output.height-11,output.width-11 to output.height-11,output.width/2 to output.height/2).forEach {(x,y)->
                assertEquals("Background must not remain inside selected card",Color.RED,output.getPixel(x,y))
            }
            assertFalse(ImageCropper.valid(listOf(.1f,.1f,.9f,.9f,.9f,.1f,.1f,.9f)))
            assertFalse(ImageCropper.valid(listOf(Float.NaN,0f,1f,0f,1f,1f,0f,1f)))
            assertFalse(ImageCropper.valid(listOf(.1f,.1f,.11f,.1f,.11f,.11f,.1f,.11f)))
        } finally {output.recycle();image.recycle()}
    }

    @Test fun uprightDecodeAppliesExifBeforeCornerCoordinates() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"crop-exif-test.jpg")
        val source=Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(source);canvas.drawColor(Color.GREEN);canvas.drawRect(0f,0f,100f,100f,Paint().apply {color=Color.RED})
        file.outputStream().use {source.compress(Bitmap.CompressFormat.JPEG,100,it)};source.recycle()
        ExifInterface(file).apply {setAttribute(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_ROTATE_90.toString());saveAttributes()}
        val upright=ImageCropper.decode(file)
        try {
            assertEquals(100,upright.width);assertEquals(200,upright.height)
            assertTrue(Color.red(upright.getPixel(50,25))>240)
            assertTrue(Color.green(upright.getPixel(50,175))>240)
        } finally {upright.recycle();file.delete()}
    }

    @Test fun onlyCroppedPixelsReachOriginalAndPreviewWhileGallerySourceSurvives()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val gallery=File(context.cacheDir,"crop-gallery-test.jpg")
        val source=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888)
        Canvas(source).apply {drawColor(Color.BLUE);drawRect(100f,100f,500f,300f,Paint().apply {color=Color.WHITE})}
        gallery.outputStream().use {source.compress(Bitmap.CompressFormat.JPEG,100,it)};source.recycle()
        val staged=ImageCropper.stage(context,Uri.fromFile(gallery))
        val cropped=ImageCropper.crop(context,staged,listOf(1f/6,.25f,5f/6,.25f,5f/6,.75f,1f/6,.75f))
        val scan=ImagePipeline.scan(context,Uri.fromFile(cropped))
        try {
            assertTrue(gallery.exists())
            assertArrayEquals(cropped.readBytes(),scan.original.readBytes())
            val original=ImageCropper.decode(scan.original)
            val preview=ImageCropper.decode(scan.preview)
            try {
                assertEquals(400,original.width);assertEquals(200,original.height)
                assertEquals(400,preview.width);assertEquals(200,preview.height)
                assertTrue(Color.red(original.getPixel(10,10))>240)
                assertTrue(Color.green(preview.getPixel(10,10))>240)
            } finally {original.recycle();preview.recycle()}
        } finally {staged.delete();cropped.delete();scan.original.delete();scan.preview.delete();gallery.delete()}
    }
}
