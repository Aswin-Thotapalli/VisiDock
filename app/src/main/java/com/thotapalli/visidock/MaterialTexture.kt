package com.thotapalli.visidock

import android.graphics.Bitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.random.Random

/** Fixed, seamless material samples: no random work or bitmap allocation on animation frames. */
internal object SurfaceTextures {
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
    val shell by lazy {sample(341,false)}
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
