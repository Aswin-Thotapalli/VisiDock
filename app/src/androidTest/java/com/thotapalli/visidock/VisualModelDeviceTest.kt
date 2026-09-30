package com.thotapalli.visidock

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Opt in with -e visualModelTest true. Uses real OCR and actual installed Gemma inference. */
class VisualModelDeviceTest {
    @Test fun readsSingleAndMultiplePeopleOnDevice(): Unit = runBlocking {
        assumeTrue("Opt-in real visual inference requires visualModelTest=true",
            InstrumentationRegistry.getArguments().getString("visualModelTest")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val model=VisualModel(context)
        assertTrue("Preinstall the verified model in filesDir/visual-model/gemma-4-e2b.litertlm",model.installed())
        val device="${Build.MANUFACTURER} ${Build.MODEL}; API ${Build.VERSION.SDK_INT}; ${Build.SUPPORTED_ABIS.joinToString()}"
        val directory=File(context.cacheDir,"visual-device-test").apply {mkdirs()}
        val fixtures=JSONArray(instrumentation.context.assets.open("vision/cases.json").bufferedReader().use {it.readText()})
        fixtures.put(JSONObject("""{"id":"three_phones_postal","images":["three_phones_postal.png"],"people":[{"name":"Mira Sen","phone":"040 2345 6789","email":"mira@example.com","company":"Northline Studio"}]}"""))
        val supported=setOf("single","two_columns","two_sides","three_phones_postal")
        val selected=InstrumentationRegistry.getArguments().getString("visualModelFixture")
        require(selected==null || selected in supported) {"Unknown visualModelFixture"}
        for(caseIndex in 0 until fixtures.length()) {
            val case=fixtures.getJSONObject(caseIndex)
            val fixture=case.getString("id")
            if(fixture !in supported || (selected!=null && selected!=fixture)) continue
            val imageNames=case.getJSONArray("images")
            val images=mutableListOf<File>()
            val scans=mutableListOf<ScanFiles>()
            val start=SystemClock.elapsedRealtime()
            var passed=false
            fun report(phase:String) {
                val elapsed=SystemClock.elapsedRealtime()-start
                // Only fixture IDs and hardware/timing metadata: no model response or account data.
                val message="fixture=$fixture phase=$phase elapsedMs=$elapsed device=$device"
                Log.i("VisiDockVisionTest",message)
                instrumentation.sendStatus(2,Bundle().apply {
                    putString("stream","VisiDock visual device evaluation: $message\n")
                    putString("fixture",fixture);putString("phase",phase)
                    putLong("elapsedMs",elapsed);putString("device",device)
                })
            }
            try {
                report("started")
                for(index in 0 until imageNames.length()) {
                    val name=imageNames.getString(index)
                    val image=File(directory,name).also {images+=it}
                    if(fixture=="three_phones_postal") drawPostalFixture(image)
                    else instrumentation.context.assets.open("vision/$name").use {input ->image.outputStream().use {input.copyTo(it)}}
                    scans+=ImagePipeline.scan(context,Uri.fromFile(image))
                }
                val files=scans.first()
                val back=scans.getOrNull(1)
                val evidence=files.text+"\n"+back?.text.orEmpty()
                report("ocr_complete")
                val result=withTimeout(20*60*1000L) {model.extract(files.preview,back?.preview,files.text,back?.text.orEmpty())}
                val expected=case.getJSONArray("people")
                assertEquals("Fixture $fixture person count",expected.length(),result.contacts.size)
                for(personIndex in 0 until expected.length()) {
                    val person=expected.getJSONObject(personIndex)
                    val name=person.getString("name")
                    val actual=result.contacts.singleOrNull {normalize(it.name)==normalize(name)}
                    assertNotNull("Fixture $fixture omitted or duplicated an expected person",actual)
                    actual!!
                    val phone=person.getString("phone").filter(Char::isDigit)
                    if(fixture=="three_phones_postal") assertTrue("Fixture $fixture primary office number retained",actual.contactPhones.any {it.number.filter(Char::isDigit)==phone})
                    else assertEquals("Fixture $fixture phone ownership",phone,actual.phone.filter(Char::isDigit))
                    assertEquals("Fixture $fixture email ownership",normalize(person.getString("email")),normalize(actual.email))
                    if(person.has("company")) assertEquals(normalize(person.getString("company")),normalize(actual.company))
                    if(person.has("address")) assertEquals(normalize(person.getString("address")),normalize(actual.address))
                    assertTrue("Fixture $fixture name must be grounded in OCR",CorrectionPolicy.appears(actual.name,evidence))
                    assertTrue("Fixture $fixture email must be grounded in OCR",CorrectionPolicy.appears(actual.email,evidence))
                    assertTrue("Fixture $fixture phone digits must be grounded in OCR",evidence.filter {it.isDigit()}.contains(phone))
                    assertEquals("Front OCR must remain unchanged",files.text,actual.rawText)
                    assertEquals("Back OCR must remain unchanged",back?.text.orEmpty(),actual.backRawText)
                }
                if(fixture=="three_phones_postal") {
                    val actual=result.contacts.single()
                    val expectedNumbers=mapOf("04023456789" to "office","04023456790" to "direct","919876543210" to "mobile")
                    assertEquals("Three full numbers must be separate, without omissions or duplicates",expectedNumbers.keys,actual.contactPhones.map {it.number.filter(Char::isDigit)}.toSet())
                    assertEquals("Exactly three separate phone entries",3,actual.contactPhones.size)
                    actual.contactPhones.forEach {phone ->
                        assertEquals("Keep the printed phone label",expectedNumbers[phone.number.filter(Char::isDigit)],normalize(phone.label).trimEnd(':'))
                        assertTrue("Phone digits must occur in OCR",evidence.filter(Char::isDigit).contains(phone.number.filter(Char::isDigit)))
                    }
                    listOf("building 7","lake road","po box 123","madhapur","hyderabad","500081").forEach {part ->
                        assertTrue("Complete postal address must retain $part",normalize(actual.address).contains(part))
                        assertTrue("Address fragment must be grounded in OCR",normalize(evidence).contains(part))
                    }
                    assertEquals("design director",normalize(actual.role))
                } else if(fixture!="two_columns") {
                    val actual=result.contacts.single()
                    assertEquals("northline studio",normalize(actual.company))
                    assertEquals("design director",normalize(actual.role))
                    assertEquals("12 lake road, bengaluru 560001",normalize(actual.address))
                    assertEquals("example.com",normalize(actual.website).removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/'))
                } else {
                    assertEquals("Each person must retain a separate phone",2,result.contacts.map {it.phone.filter(Char::isDigit)}.distinct().size)
                    assertEquals("Each person must retain a separate email",2,result.contacts.map {normalize(it.email)}.distinct().size)
                }
                passed=true
            } finally {
                report(if(passed) "passed" else "failed")
                scans.forEach {it.original.delete();it.preview.delete()}
                images.forEach {it.delete()}
            }
        }
        directory.delete()
        Unit
    }

    /** Fictional local test image: no account data, network or image-generation dependency. */
    private fun drawPostalFixture(file:File) {
        val bitmap=android.graphics.Bitmap.createBitmap(1600,1000,android.graphics.Bitmap.Config.ARGB_8888)
        try {
            val canvas=android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color=android.graphics.Color.rgb(17,44,86)
                typeface=android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL)
            }
            fun text(value:String,x:Float,y:Float,size:Float,bold:Boolean=false) {
                paint.textSize=size
                paint.typeface=android.graphics.Typeface.create("sans-serif",if(bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                canvas.drawText(value,x,y,paint)
            }
            text("NORTHLINE STUDIO",80f,115f,44f,true)
            text("Mira Sen",80f,220f,64f,true)
            text("Design Director",80f,282f,36f)
            text("Office: 040 2345 6789",80f,405f,40f)
            text("Direct: 040 2345 6790",80f,472f,40f)
            text("Mobile: +91 98765 43210",80f,539f,40f)
            text("mira@example.com",80f,615f,36f)
            text("example.com",80f,675f,36f)
            text("Building 7",960f,620f,36f)
            text("Lake Road",960f,681f,36f)
            text("PO Box 123",960f,742f,36f)
            text("Madhapur",960f,803f,36f)
            text("Hyderabad 500081",960f,864f,36f)
            file.outputStream().use {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
        } finally {bitmap.recycle()}
    }

    private fun normalize(text:String)=text.lowercase(Locale.ROOT).replace(Regex("\\s+")," ").trim()
}
