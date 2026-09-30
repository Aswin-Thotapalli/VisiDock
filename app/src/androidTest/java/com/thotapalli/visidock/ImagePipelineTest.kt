package com.thotapalli.visidock

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ImagePipelineTest {
    @Test fun originalIsPreservedAndExifRotationIsApplied() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"rotation-test.jpg")
        val bitmap=Bitmap.createBitmap(800,400,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)}
        bitmap.recycle()
        ExifInterface(file).apply {setAttribute(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_ROTATE_90.toString());saveAttributes()}
        val original=file.readBytes()
        val scan=ImagePipeline.scan(context,Uri.fromFile(file))
        try {
            assertArrayEquals(original,scan.original.readBytes())
            val preview=BitmapFactory.decodeFile(scan.preview.path)
            assertEquals(400,preview.width);assertEquals(800,preview.height)
            preview.recycle()
        } finally {scan.original.delete();scan.preview.delete();file.delete()}
    }
    @Test fun invalidFileDoesNotLeaveDrafts() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val original=ImagePipeline.directory(context).listFiles().orEmpty().map {it.name}.toSet()
        val file=File(context.cacheDir,"invalid-image.txt").apply {writeText("not an image")}
        try {
            val result=runCatching {ImagePipeline.scan(context,Uri.fromFile(file))}
            assertTrue(result.isFailure)
            assertEquals(original,ImagePipeline.directory(context).listFiles().orEmpty().map {it.name}.toSet())
        } finally {file.delete()}
    }
}
