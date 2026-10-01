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
import kotlin.math.*

/** Only a confirmed, perspective-corrected JPEG is passed into ImagePipeline. */
object ImageCropper {
    /** Four supported image edges first, foreground segmentation second. Null requests manual framing. */
    fun detect(bitmap: Bitmap): List<Float>? {
        val scale = minOf(1f, 320f / maxOf(bitmap.width, bitmap.height))
        val w = (bitmap.width * scale).roundToInt().coerceAtLeast(2)
        val h = (bitmap.height * scale).roundToInt().coerceAtLeast(2)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()
        detectEdges(pixels, w, h)?.let { return it }
        val border = mutableListOf<Int>()
        for (x in 0 until w) { border += pixels[x]; border += pixels[(h - 1) * w + x] }
        for (y in 1 until h - 1) { border += pixels[y * w]; border += pixels[y * w + w - 1] }
        fun median(shift: Int) = border.map { (it shr shift) and 255 }.sorted()[border.size / 2]
        val red = median(16); val green = median(8); val blue = median(0)
        data class Point(val x: Int, val y: Int)
        fun cross(a: Point, b: Point, c: Point) = (b.x-a.x).toLong()*(c.y-a.y)-(b.y-a.y).toLong()*(c.x-a.x)
        var best: List<Float>? = null; var bestArea = 0.0
        val dxs=intArrayOf(-1,1,0,0);val dys=intArrayOf(0,0,-1,1)
        for (threshold in listOf(14, 30, 55, 85)) {
            val mask = BooleanArray(pixels.size) { i ->
                val p = pixels[i]
                maxOf(kotlin.math.abs(((p shr 16) and 255)-red), kotlin.math.abs(((p shr 8) and 255)-green), kotlin.math.abs((p and 255)-blue)) > threshold
            }
            val seen = BooleanArray(pixels.size); val queue = IntArray(pixels.size)
            for (start in pixels.indices) {
                if (!mask[start] || seen[start]) continue
                var read = 0; var count = 1; queue[0] = start; seen[start] = true
                val edge = mutableListOf<Point>()
                while (read < count) {
                    val i = queue[read++]; val x = i % w; val y = i / w
                    var boundary = false
                    for (direction in 0..3) {
                        val nx=x+dxs[direction]; val ny=y+dys[direction]
                        if (nx !in 0 until w || ny !in 0 until h || !mask[ny*w+nx]) { boundary=true; continue }
                        val j=ny*w+nx
                        if (!seen[j]) {seen[j]=true;queue[count++]=j}
                    }
                    if (boundary) edge += Point(x,y)
                }
                if (count < pixels.size * .12 || edge.size < 4) continue
                val sorted=edge.distinct().sortedWith(compareBy<Point> {it.x}.thenBy {it.y})
                fun half(points:List<Point>):MutableList<Point> {
                    val result=mutableListOf<Point>()
                    for (p in points) {while(result.size>=2 && cross(result[result.size-2],result.last(),p)<=0) result.removeAt(result.lastIndex);result+=p}
                    return result
                }
                val hull=(half(sorted).dropLast(1)+half(sorted.reversed()).dropLast(1)).toMutableList()
                while(hull.size>4) {
                    val remove=hull.indices.minBy {i->kotlin.math.abs(cross(hull[(i+hull.size-1)%hull.size],hull[i],hull[(i+1)%hull.size]))}
                    hull.removeAt(remove)
                }
                if(hull.size!=4) continue
                val first=hull.indices.minBy {hull[it].x.toFloat()/w+hull[it].y.toFloat()/h}
                val ordered=(0..3).map {hull[(first+it)%4]}
                val points=ordered.flatMap {listOf(it.x.toFloat()/(w-1),it.y.toFloat()/(h-1))}
                if(!valid(points)) continue
                val area=kotlin.math.abs(ordered.indices.sumOf {i->val a=ordered[i];val b=ordered[(i+1)%4];a.x.toDouble()*b.y-b.x.toDouble()*a.y})/2
                // A filled card should explain the region; reject thin borders/text and near-full-frame backgrounds.
                if(area < pixels.size*.12 || area > pixels.size*.94 || count/area !in .70..1.20) continue
                if(ordered.any {it.x<=1 || it.y<=1 || it.x>=w-2 || it.y>=h-2}) continue
                if(area>bestArea) {bestArea=area;best=points}
            }
        }
        return best
    }
    /** Directional Sobel + bounded Hough search: does not assume the table has one uniform color. */
    private fun detectEdges(pixels: IntArray, w: Int, h: Int): List<Float>? {
        if (w < 32 || h < 32) return null
        val gray = FloatArray(pixels.size) { i ->
            val p = pixels[i]; (((p shr 16) and 255) * .299f + ((p shr 8) and 255) * .587f + (p and 255) * .114f)
        }
        // A small blur suppresses JPEG grain and wood/fabric texture without erasing the card edge.
        val blurred = gray.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            blurred[i] = (gray[i] * 4 + (gray[i-1]+gray[i+1]+gray[i-w]+gray[i+w])*2 +
                gray[i-w-1]+gray[i-w+1]+gray[i+w-1]+gray[i+w+1]) / 16
        }
        val gx = FloatArray(pixels.size); val gy = FloatArray(pixels.size)
        val magnitude = FloatArray(pixels.size)
        for (y in 2 until h - 2) for (x in 2 until w - 2) {
            val i=y*w+x
            gx[i]=blurred[i-w+1]+2*blurred[i+1]+blurred[i+w+1]-blurred[i-w-1]-2*blurred[i-1]-blurred[i+w-1]
            gy[i]=blurred[i+w-1]+2*blurred[i+w]+blurred[i+w+1]-blurred[i-w-1]-2*blurred[i-w]-blurred[i-w+1]
            magnitude[i]=hypot(gx[i],gy[i])
        }
        val bins=180; val diagonal=ceil(hypot(w.toDouble(),h.toDouble())).toInt()
        val stride=diagonal*2+1
        val cosine=DoubleArray(bins) {cos(it*PI/bins)}
        val sine=DoubleArray(bins) {sin(it*PI/bins)}
        val votes=FloatArray(bins*stride)
        for (y in 2 until h-2) for (x in 2 until w-2) {
            val i=y*w+x
            if(magnitude[i]<24f) continue
            val angle=((atan2(gy[i],gx[i])*180/PI).roundToInt()+180)%180
            for(delta in -2..2) {
                val a=(angle+delta+180)%180
                val rho=(x*cosine[a]+y*sine[a]).roundToInt()+diagonal
                votes[a*stride+rho]+=minOf(magnitude[i],100f)/100f
            }
        }
        data class Line(val a:Int,val rho:Double)
        val lines=mutableListOf<Line>()
        // Nonmaximum suppression keeps one hypothesis per physical edge, not dozens of adjacent bins.
        repeat(28) {
            var peak=-1; var strength=maxOf(18f,minOf(w,h)*.10f)
            for(i in votes.indices) if(votes[i]>strength) {peak=i;strength=votes[i]}
            if(peak<0) return@repeat
            val a=peak/stride; val rho=peak%stride-diagonal
            lines+=Line(a,rho.toDouble())
            for(da in -5..5) for(dr in -6..6) {
                val raw=a+da; val aa=(raw+180)%180
                val rr=(if(raw !in 0..179) -rho else rho)+dr+diagonal
                if(rr in 0 until stride) votes[aa*stride+rr]=0f
            }
        }
        data class P(val x:Double,val y:Double)
        fun intersection(a:Line,b:Line):P? {
            val determinant=cosine[a.a]*sine[b.a]-sine[a.a]*cosine[b.a]
            if(abs(determinant)<.35) return null
            val x=(a.rho*sine[b.a]-b.rho*sine[a.a])/determinant
            val y=(cosine[a.a]*b.rho-cosine[b.a]*a.rho)/determinant
            return if(x>=1 && y>=1 && x<w-1 && y<h-1) P(x,y) else null
        }
        fun separation(a:Line,b:Line):Int = abs(a.a-b.a).let {minOf(it,180-it)}
        val pairs=mutableListOf<Pair<Int,Int>>()
        for(a in lines.indices) for(b in a+1 until lines.size) if(separation(lines[a],lines[b])<28) pairs+=a to b
        fun support(a:P,b:P):Double {
            val length=hypot(b.x-a.x,b.y-a.y)
            if(length<minOf(w,h)*.14) return 0.0
            val nx=-(b.y-a.y)/length;val ny=(b.x-a.x)/length
            var found=0
            for(step in 0 until 48) {
                val t=(step+.5)/48;val x=a.x+(b.x-a.x)*t;val y=a.y+(b.y-a.y)*t
                var best=0.0
                for(offset in -2..2) {
                    val xx=(x+nx*offset).roundToInt().coerceIn(0,w-1)
                    val yy=(y+ny*offset).roundToInt().coerceIn(0,h-1)
                    val index=yy*w+xx
                    val aligned=abs(gx[index]*nx+gy[index]*ny)
                    if(aligned>best && aligned>=magnitude[index]*.75) best=aligned
                }
                if(best>=24) found++
            }
            return found/48.0
        }
        var best:List<Float>?=null;var bestScore=0.0
        for(i in pairs.indices) for(j in i+1 until pairs.size) {
            val (a,b)=pairs[i];val(c,d)=pairs[j]
            if(a==c || a==d || b==c || b==d || separation(lines[a],lines[c])<45) continue
            val corners=listOfNotNull(intersection(lines[a],lines[c]),intersection(lines[b],lines[c]),
                intersection(lines[b],lines[d]),intersection(lines[a],lines[d]))
            if(corners.size!=4) continue
            val cx=corners.sumOf {it.x}/4;val cy=corners.sumOf {it.y}/4
            val ordered=corners.sortedBy {atan2(it.y-cy,it.x-cx)}
            val first=ordered.indices.minBy {ordered[it].x/w+ordered[it].y/h}
            val quad=(0..3).map {ordered[(first+it)%4]}
            val points=quad.flatMap {listOf((it.x/(w-1)).toFloat(),(it.y/(h-1)).toFloat())}
            if(!valid(points)) continue
            val area=abs(quad.indices.sumOf {k->val q=quad[k];val r=quad[(k+1)%4];q.x*r.y-r.x*q.y})/2/(w*h)
            if(area !in .10.. .92) continue
            val lengths=quad.indices.map {k->hypot(quad[k].x-quad[(k+1)%4].x,quad[k].y-quad[(k+1)%4].y)}
            val ratio=(lengths[0]+lengths[2])/(lengths[1]+lengths[3])
            if(ratio !in .35..2.85) continue
            val edges=quad.indices.map {k->support(quad[k],quad[(k+1)%4])}
            if(edges.min()<.64 || edges.average()<.78) continue
            val score=area*edges.average()*edges.min()
            if(score>bestScore) {bestScore=score;best=points}
        }
        return best
    }
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
