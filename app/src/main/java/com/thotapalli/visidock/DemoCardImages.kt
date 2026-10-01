package com.thotapalli.visidock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream

/** Disposable artwork for the explicitly labelled demo; never used as a real user's image. */
internal object DemoCardImages {
    fun preview(card:Card,back:Boolean=false):ByteArray {
        val bitmap=Bitmap.createBitmap(1000,600,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        val navy=Color.rgb(12,35,76);val blue=Color.rgb(46,96,236);val ice=Color.rgb(238,245,255)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        fun text(value:String,x:Float,y:Float,size:Float,color:Int,bold:Boolean=false) {
            paint.color=color;paint.textSize=size;paint.typeface=Typeface.create("sans-serif",if(bold) Typeface.BOLD else Typeface.NORMAL)
            canvas.drawText(value,x,y,paint)
        }
        if(back) {
            canvas.drawColor(navy)
            paint.color=blue;canvas.drawCircle(925f,85f,240f,paint)
            text(card.company.uppercase(),64f,285f,54f,ice,true)
            text("Design with intention.",68f,350f,28f,ice)
            text("DEMO CARD · REVERSE",68f,540f,20f,Color.rgb(157,183,229))
        } else {
            canvas.drawColor(ice)
            paint.color=blue;canvas.drawRect(0f,0f,18f,600f,paint)
            text(card.company.uppercase(),64f,90f,25f,blue,true)
            text(card.name,64f,245f,62f,navy,true)
            text(card.role,66f,297f,30f,navy)
            paint.color=Color.rgb(186,203,230);canvas.drawRect(64f,362f,936f,364f,paint)
            text(card.email,66f,423f,27f,navy)
            text(card.address.ifBlank {"India"},66f,469f,25f,navy)
            text("DEMO CARD",66f,550f,18f,blue)
        }
        return try {ByteArrayOutputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,88,it);it.toByteArray()}} finally {bitmap.recycle()}
    }
}
