package com.thotapalli.visidock

import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** A consistent visual model, not a rigid-body simulator. Light comes from upper left. */
internal enum class PhysicalMaterial(val normalizedMass:Float,val compression:Float,val damping:Float,val stiffness:Float) {
    Leather(1.25f,.035f,.88f,360f), Paper(.72f,.012f,.82f,430f), Metal(1.8f,.004f,.94f,720f)
}

internal data class SurfacePose(val height:Float,val shadowOffset:Float,val shadowSpread:Float,val scale:Float)

internal object PhysicalMotion {
    fun settle(material:PhysicalMaterial=PhysicalMaterial.Leather)=
        // Compose springs assume unit mass: k/m preserves the chosen natural
        // frequency sqrt(k/m), while the damping ratio controls overshoot.
        spring<Float>(dampingRatio=material.damping,stiffness=material.stiffness/material.normalizedMass)

    fun surface(depth:Float,pressed:Float,material:PhysicalMaterial):SurfacePose {
        val amount=pressed.coerceIn(0f,1f)
        val height=depth.coerceAtLeast(0f)*(1f-.82f*amount)
        return SurfacePose(height,height*.65f,.8f+height*.72f,1f-material.compression*amount)
    }

    /** Parallel leaves slide across one another; they never rotate like a carousel. */
    fun cardDepth(distance:Float):Float=abs(distance).coerceIn(0f,1f)
    fun cardScale(distance:Float):Float=1f-.055f*cardDepth(distance)
}

/**
 * Draw around a surface's content, preserving its semantics and input behavior.
 * Press fraction shortens the cast shadow and closes the bevel. Apply to a material
 * container, not a photograph: the card image itself should remain unaltered.
 */
internal fun Modifier.physicalSurface(
    depthDp:Float=4f,
    pressedFraction:Float=0f,
    shape:Shape=RoundedCornerShape(16.dp),
    material:PhysicalMaterial=PhysicalMaterial.Leather,
    castShadow:Boolean=true,
    drawBevel:Boolean=true,
)=physicalSurface(depthDp,{pressedFraction},shape,material,castShadow,drawBevel)

/** Read animated pressure in draw, avoiding recomposition/outline rebuild each frame. */
internal fun Modifier.physicalSurface(
    depthDp:Float=4f,
    pressedFraction:()->Float,
    shape:Shape=RoundedCornerShape(16.dp),
    material:PhysicalMaterial=PhysicalMaterial.Leather,
    castShadow:Boolean=true,
    drawBevel:Boolean=true,
)=drawWithCache {
    val outline=shape.createOutline(size,layoutDirection,this)
    val navy=Color(0xFF031730)
    val light=Brush.linearGradient(listOf(Color(0xFFC0DCEC).copy(alpha=.30f),Color.Transparent,navy.copy(alpha=.35f)),Offset.Zero,Offset(size.width,size.height))
    val silhouette=when(outline) {
        is Outline.Rectangle -> Path().apply {addRect(outline.rect)}
        is Outline.Rounded -> Path().apply {addRoundRect(outline.roundRect)}
        is Outline.Generic -> outline.path
    }.asAndroidPath()
    val shadowPaint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {color=navy.toArgb()}
    onDrawWithContent {
        val pose=PhysicalMotion.surface(depthDp,pressedFraction(),material)
        val elevation=pose.height.dp.toPx()
        if(castShadow && pose.height>0f) {
            if(android.os.Build.VERSION.SDK_INT>=28) drawIntoCanvas {canvas->
                val native=canvas.nativeCanvas
                val saved=native.save()
                // Exclude the object itself: only its soft exterior shadow is drawn.
                // This also avoids tinting transparent outlined controls or photos.
                native.clipOutPath(silhouette)
                shadowPaint.setShadowLayer(pose.shadowSpread.dp.toPx(),elevation*.22f,
                    pose.shadowOffset.dp.toPx(),navy.copy(alpha=.22f).toArgb())
                native.drawPath(silhouette,shadowPaint)
                shadowPaint.setShadowLayer(1.2f.dp.toPx(),0f,.7f.dp.toPx(),navy.copy(alpha=.16f).toArgb())
                native.drawPath(silhouette,shadowPaint)
                native.restoreToCount(saved)
            } else {
                // Hardware non-text shadow layers start at API28. A dense low-alpha
                // fallback avoids the visible five-ring approximation on API26/27.
                for(layer in 24 downTo 1) {
                    val spread=pose.shadowSpread.dp.toPx()*layer/24f
                    translate(left=elevation*.22f,top=pose.shadowOffset.dp.toPx()) {
                        drawOutline(outline,navy.copy(alpha=.006f),style=Stroke(spread*2))
                    }
                }
            }
        }
        drawContent()
        if(drawBevel) drawOutline(outline,light,style=Stroke((.65f+pose.height*.025f).dp.toPx()))
    }
}

internal fun caseInscription(ownerName:String):String {
    val name=ownerName.trim().replace(Regex("\\s+")," ")
    if(name.isEmpty()) return "VisiDock"
    return if(name.endsWith("s",ignoreCase=true)) "$name’ VisiDock" else "$name’s VisiDock"
}
