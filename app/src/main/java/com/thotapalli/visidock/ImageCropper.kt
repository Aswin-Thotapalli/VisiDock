package com.thotapalli.visidock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Only a confirmed, perspective-corrected JPEG is passed into ImagePipeline. */
object ImageCropper {
    fun directory(context:Context)=File(context.cacheDir,"crop").apply {mkdirs()}
    suspend fun stage(context:Context,uri:Uri):File {
        val target=File(directory(context),"${UUID.randomUUID()}.source")
        try {
            context.contentResolver.openInputStream(uri)?.use {input -> target.outputStream().use {output ->
                val buffer=ByteArray(32*1024);var length=0L
                while(true) {
                    currentCoroutineContext().ensureActive()
                    val count=input.read(buffer);if(count<0) break
                    length+=count;require(length<=ImagePipeline.MAX_ORIGINAL) {"Choose an image smaller than 20 MB."}
                    output.write(buffer,0,count)
                }
            }} ?: error("This photo is unavailable. Choose it again.")
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeFile(target.path,bounds)
            require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outMimeType in setOf("image/jpeg","image/png","image/webp")) {"Choose a readable JPEG, PNG or WebP."}
            return target
        } catch(e:Exception) {target.delete();throw e}
    }
    fun decode(file:File,maxEdge:Int=2400):Bitmap {
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeFile(file.path,bounds)
        require(bounds.outWidth>0 && bounds.outHeight>0) {"Could not read this photo."}
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>maxEdge) sample*=2
        val original=checkNotNull(BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply {inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888})) {"Could not decode this photo."}
        val exif=runCatching {ExifInterface(file)}.getOrNull()
        val transform=Matrix().apply {if(exif?.isFlipped==true) postScale(-1f,1f);postRotate((exif?.rotationDegrees ?: 0).toFloat())}
        return try {Bitmap.createBitmap(original,0,0,original.width,original.height,transform,true).also {if(it!==original) original.recycle()}}
        catch(e:Throwable) {original.recycle();throw e}
    }
    /** Clockwise TL, TR, BR, BL in upright-image normalized coordinates. */
    fun valid(points:List<Float>):Boolean {
        if(points.size!=8 || points.any {!it.isFinite() || it !in 0f..1f}) return false
        var area=0f
        for(i in 0..3) {
            val j=(i+1)%4;val k=(i+2)%4
            val ax=points[j*2]-points[i*2];val ay=points[j*2+1]-points[i*2+1]
            val bx=points[k*2]-points[j*2];val by=points[k*2+1]-points[j*2+1]
            if(ax*by-ay*bx<=0.0001f || hypot(ax,ay)<.02f) return false
            area+=points[i*2]*points[j*2+1]-points[j*2]*points[i*2+1]
        }
        return area>.02f
    }
    fun warp(bitmap:Bitmap,points:List<Float>):Bitmap {
        require(valid(points)) {"Place the four corners around the card without crossing its edges."}
        val source=FloatArray(8) {i->points[i]*(if(i%2==0) bitmap.width else bitmap.height)}
        fun edge(a:Int,b:Int)=hypot(source[a*2]-source[b*2],source[a*2+1]-source[b*2+1])
        val width=maxOf(edge(0,1),edge(3,2)).roundToInt().coerceIn(1,2400)
        val height=maxOf(edge(0,3),edge(1,2)).roundToInt().coerceIn(1,2400)
        require(width>=40 && height>=40) {"Select a larger card area."}
        val destination=floatArrayOf(0f,0f,width.toFloat(),0f,width.toFloat(),height.toFloat(),0f,height.toFloat())
        val transform=Matrix();check(transform.setPolyToPoly(source,0,destination,0,4)) {"Could not straighten these corners."}
        return Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also {result ->
            val canvas=Canvas(result);canvas.drawColor(android.graphics.Color.WHITE)
            canvas.drawBitmap(bitmap,transform,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
    }
    suspend fun crop(context:Context,file:File,points:List<Float>):File {
        val source=decode(file)
        var result:Bitmap?=null
        val output=File(directory(context),"${UUID.randomUUID()}.jpg")
        try {
            currentCoroutineContext().ensureActive()
            val rendered=warp(source,points);result=rendered
            output.outputStream().use {check(rendered.compress(Bitmap.CompressFormat.JPEG,94,it)) {"Could not save the cropped card."}}
            currentCoroutineContext().ensureActive()
            return output
        } catch(e:Exception) {output.delete();throw e}
        finally {result?.recycle();source.recycle()}
    }
}
