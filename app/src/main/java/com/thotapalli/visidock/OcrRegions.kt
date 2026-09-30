package com.thotapalli.visidock

import org.json.JSONArray
import org.json.JSONObject

object OcrRegions {
    fun encode(regions:List<OcrRegion>):String=JSONArray(regions.take(300).map {r ->JSONObject(mapOf(
        "text" to r.text.take(1000),"left" to r.left,"top" to r.top,"right" to r.right,"bottom" to r.bottom,"side" to r.side))}).toString()
    fun decode(value:String?):List<OcrRegion> = runCatching {
        val a=JSONArray(value?:"[]")
        (0 until minOf(a.length(),300)).map {i ->val r=a.getJSONObject(i);OcrRegion(r.getString("text"),
            r.getDouble("left").toFloat(),r.getDouble("top").toFloat(),r.getDouble("right").toFloat(),r.getDouble("bottom").toFloat(),r.optInt("side",0))}
    }.getOrDefault(emptyList()).filter {r -> listOf(r.left,r.top,r.right,r.bottom).all {it.isFinite()} }
        .map {r ->r.copy(left=r.left.coerceIn(0f,1f),top=r.top.coerceIn(0f,1f),right=r.right.coerceIn(0f,1f),bottom=r.bottom.coerceIn(0f,1f),side=r.side.coerceIn(0,1))}
}
