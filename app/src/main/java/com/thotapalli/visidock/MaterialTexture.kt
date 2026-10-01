package com.thotapalli.visidock

import android.graphics.Bitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.random.Random
import kotlin.math.*

/** Fixed, seamless material samples: no random work or bitmap allocation on animation frames. */
internal object SurfaceTextures {
    /** Pebble height field, lit from the same upper-left source as the case bevel.
     * The repeat is periodic in both axes, so no square bitmap seams appear. */
    private fun leather():ShaderBrush {
        val side=256
        val random=Random(341)
        // Approximately five physical pixels per grain: the shell reads as
        // fine leather, not the large, hard scales of the earlier height field.
        val cells=48
        val cell=side.toFloat()/cells
        val seeds=Array(cells*cells) {i->
            floatArrayOf((i%cells+.20f+random.nextFloat()*.60f)*cell,(i/cells+.20f+random.nextFloat()*.60f)*cell)
        }
        val heights=FloatArray(side*side)
        for(y in 0 until side) for(x in 0 until side) {
            val gx=(x/cell).toInt();val gy=(y/cell).toInt()
            var nearest=Float.MAX_VALUE
            var second=Float.MAX_VALUE
            for(dy in -1..1) for(dx in -1..1) {
                val sx=gx+dx;val sy=gy+dy
                val seed=seeds[Math.floorMod(sy,cells)*cells+Math.floorMod(sx,cells)]
                val px=seed[0]+(sx-Math.floorMod(sx,cells))*cell
                val py=seed[1]+(sy-Math.floorMod(sy,cells))*cell
                val distance=hypot(x-px,y-py)
                if(distance<nearest) {second=nearest;nearest=distance} else if(distance<second) second=distance
            }
            // Narrow valleys separate rounded pebbles. Fine pore variation is low
            // amplitude so the visible surface is relief, not television noise.
            heights[y*side+x]=(1f-exp(-(second-nearest)*.80f))*.52f+random.nextFloat()*.022f
        }
        val pixels=IntArray(side*side)
        for(y in 0 until side) for(x in 0 until side) {
            val left=heights[y*side+Math.floorMod(x-1,side)]
            val right=heights[y*side+(x+1)%side]
            val above=heights[Math.floorMod(y-1,side)*side+x]
            val below=heights[((y+1)%side)*side+x]
            val light=((right-left)*.6f+(below-above)*.8f).coerceIn(-1f,1f)
            val valley=(1f-heights[y*side+x]).coerceIn(0f,1f)
            val alpha=((abs(light)*28f)+valley*3f).toInt().coerceIn(0,24)
            pixels[y*side+x]=(alpha shl 24) or if(light>0) 0x00CAE0EC else 0x00021931
        }
        return ShaderBrush(ImageShader(Bitmap.createBitmap(pixels,side,side,Bitmap.Config.ARGB_8888).asImageBitmap(),TileMode.Repeated,TileMode.Repeated))
    }
    private fun sample(seed:Int,lining:Boolean):ShaderBrush {
        val random=Random(seed)
        val size=128
        val pixels=IntArray(size*size) {index->
            val x=index%size;val y=index/size
            val woven=if(lining && ((x+y)%6==0 || (x-y+size)%6==0)) 18 else 0
            val shade=if(random.nextBoolean()) 0x00FFFFFF else 0x000A2445
            val alpha=random.nextInt(0,30)+woven
            (alpha shl 24) or shade
        }
        val bitmap=Bitmap.createBitmap(pixels,size,size,Bitmap.Config.ARGB_8888).asImageBitmap()
        return ShaderBrush(ImageShader(bitmap,TileMode.Repeated,TileMode.Repeated))
    }
    val shell by lazy {leather()}
    val lining by lazy {sample(872,true)}
    val paper by lazy {sample(119,false)}
}

/** Used on material surfaces, never on card photographs or OCR input. */
internal fun Modifier.materialGrain(lining:Boolean=false,alpha:Float=.40f)=drawWithCache {
    val texture=if(lining) SurfaceTextures.lining else SurfaceTextures.paper
    onDrawWithContent {drawContent();drawRect(texture,alpha=alpha)}
}

internal fun Modifier.materialUnderlay(alpha:Float=.24f)=drawWithCache {
    val texture=SurfaceTextures.paper
    onDrawWithContent {drawRect(texture,alpha=alpha);drawContent()}
}
