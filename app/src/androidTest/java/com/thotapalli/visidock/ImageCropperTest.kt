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
