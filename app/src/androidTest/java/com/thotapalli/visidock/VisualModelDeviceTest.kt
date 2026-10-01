package com.thotapalli.visidock

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ensureActive
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
        val gpuLanguage=InstrumentationRegistry.getArguments().getString("gpuLanguage")=="true"
        var evaluatingFixture=""
        val speculativeDecoding=InstrumentationRegistry.getArguments().getString("speculativeDecoding")=="true"
        val model=VisualModel(context,gpuLanguage=gpuLanguage,speculativeDecoding=speculativeDecoding,evaluationObserver={phase,proposal->
            // Test-only fictional corpus. The release variant never invokes this observer.
            val report=JSONObject().put("fixture",evaluatingFixture).put("phase",phase)
                .put("contacts",JSONArray(proposal.contacts.map {card->JSONObject()
                    .put("name",card.name).put("role",card.role).put("company",card.company)
                    .put("address",card.address).put("phones",JSONArray(card.contactPhones.map {JSONObject().put("number",it.number).put("label",it.label)}))
                    .put("emails",JSONArray(card.contactEmails)).put("websites",JSONArray(card.contactWebsites))}))
                .put("issues",JSONArray(proposal.reviewIssues.map {JSONObject().put("person",it.contactIndex).put("field",it.field).put("reason",it.reason).put("ids",JSONArray(it.sourceIds))}))
                .put("sources",JSONArray(proposal.sources.map {JSONObject().put("person",it.contactIndex).put("field",it.field).put("ids",JSONArray(it.regionIds)).put("text",JSONArray(it.regions.map {r->r.text}))}))
                .put("unassignedIds",JSONArray(proposal.unassignedRegionIds))
            File(context.getExternalFilesDir(null),"ui-review/recognition-$evaluatingFixture-$phase.json")
                .apply {parentFile?.mkdirs();writeText(report.toString(2))}
        })
        if(!model.installed() && InstrumentationRegistry.getArguments().getString("stagedVisualModel")=="true") {
            installStagedModel(context)
        }
        if(!model.installed() && InstrumentationRegistry.getArguments().getString("downloadVisualModel")=="true") {
            // Explicit remote-device opt-in; downloads only the public pinned model.
            // Production card data and Firebase account credentials are never fixtures.
            withTimeout(10*60*1000L) {model.download {percent->
                if(percent%20==0) instrumentation.sendStatus(2,Bundle().apply {
                    putString("stream","Preparing verified public test model: $percent%\n")
                })
            }}
        }
        assertTrue("Preinstall the verified model in filesDir/visual-model/gemma-4-e2b.litertlm",model.installed())
        val device="${Build.MANUFACTURER} ${Build.MODEL}; API ${Build.VERSION.SDK_INT}; ${Build.SUPPORTED_ABIS.joinToString()}"
        val directory=File(context.cacheDir,"visual-device-test").apply {mkdirs()}
        val fixtures=JSONArray(instrumentation.context.assets.open("vision/cases.json").bufferedReader().use {it.readText()})
        fixtures.put(JSONObject("""{"id":"three_phones_postal","images":["three_phones_postal.png"],"people":[{"name":"Mira Sen","phone":"040 2345 6789","email":"mira@example.com","company":"Northline Studio"}]}"""))
        fixtures.put(JSONObject("""{"id":"multiple_channels","images":["multiple_channels.png"],"people":[{"name":"Mira Sen","phone":"040 2345 6789","email":"mira@example.com","company":"Northline Studio"}]}"""))
        val supported=setOf("single","two_columns","two_sides","three_phones_postal","multiple_channels")
        val selected=InstrumentationRegistry.getArguments().getString("visualModelFixture")
        require(selected==null || selected in supported) {"Unknown visualModelFixture"}
        val failures=mutableListOf<Throwable>()
        for(caseIndex in 0 until fixtures.length()) {
            val case=fixtures.getJSONObject(caseIndex)
            val fixture=case.getString("id")
            if(fixture !in supported || (selected!=null && selected!=fixture)) continue
            evaluatingFixture=fixture
            val imageNames=case.getJSONArray("images")
            val images=mutableListOf<File>()
            val scans=mutableListOf<ScanFiles>()
            val start=SystemClock.elapsedRealtime()
            var passed=false
            fun report(phase:String) {
                val elapsed=SystemClock.elapsedRealtime()-start
                // Only fixture IDs and hardware/timing metadata: no model response or account data.
                val message="fixture=$fixture phase=$phase elapsedMs=$elapsed recognitionSuite=2 language=${if(gpuLanguage) "gpu" else "cpu"} device=$device"
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
                    if(fixture=="three_phones_postal" || fixture=="multiple_channels") drawPostalFixture(image, fixture=="multiple_channels")
                    else instrumentation.context.assets.open("vision/$name").use {input ->image.outputStream().use {input.copyTo(it)}}
                    scans+=ImagePipeline.scan(context,Uri.fromFile(image))
                }
                val files=scans.first()
                val back=scans.getOrNull(1)
                val evidence=files.text+"\n"+back?.text.orEmpty()
                File(context.getExternalFilesDir(null),"ui-review/recognition-$fixture-evidence.json").apply {
                    parentFile?.mkdirs();writeText(OcrEvidence.combine(files.evidence,back?.evidence ?: OcrEvidence()).encode())
                }
                report("ocr_complete")
                val result=withTimeout(3*60*1000L) {model.extract(files.preview,back?.preview,files.text,back?.text.orEmpty(),
                    ocrEvidence=OcrEvidence.combine(files.evidence,back?.evidence ?: OcrEvidence()))}
                val expected=case.getJSONArray("people")
                assertEquals("Fixture $fixture person count",expected.length(),result.contacts.size)
                for(personIndex in 0 until expected.length()) {
                    val person=expected.getJSONObject(personIndex)
                    val name=person.getString("name")
                    val actual=result.contacts.singleOrNull {normalize(it.name)==normalize(name)}
                    assertNotNull("Fixture $fixture omitted or duplicated an expected person",actual)
                    actual!!
                    val phone=person.getString("phone").filter(Char::isDigit)
                    if(fixture=="three_phones_postal" || fixture=="multiple_channels") assertTrue("Fixture $fixture primary office number retained",actual.contactPhones.any {it.number.filter(Char::isDigit)==phone})
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
                if(fixture=="three_phones_postal" || fixture=="multiple_channels") {
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
                    if (fixture=="multiple_channels") {
                        assertEquals(setOf("mira@example.com","studio@example.com"),actual.contactEmails.map(::normalize).toSet())
                        assertEquals(setOf("example.com","studio.example"),actual.contactWebsites.map { normalize(it).removePrefix("https://").removePrefix("www.").trimEnd('/') }.toSet())
                    }
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
            } catch (failure: AssertionError) {
                failures+=AssertionError("Fixture $fixture: ${failure.message}",failure)
            } catch (failure: Exception) {
                // One malformed response must not hide the other corpus results.
                // A fixture-local timeout is recoverable; external cancellation is not.
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                failures+=AssertionError("Fixture $fixture: ${failure.message}",failure)
            } finally {
                report(if(passed) "passed" else "failed")
                scans.forEach {it.original.delete();it.preview.delete()}
                images.forEach {it.delete()}
            }
            if(failures.isNotEmpty() && InstrumentationRegistry.getArguments().getString("stopOnFirstFailure")=="true") break
        }
        directory.delete()
        model.release()
        if(failures.isNotEmpty()) throw AssertionError(failures.joinToString("\n", "${failures.size} recognition fixture(s) failed:\n") {it.message.orEmpty()}).apply {
            failures.forEach(::addSuppressed)
        }
        Unit
    }

    /** Fictional local test image: no account data, network or image-generation dependency. */
    private fun drawPostalFixture(file:File, multipleChannels:Boolean=false) {
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
            if (multipleChannels) {
                text("studio@example.com",80f,675f,36f)
                text("example.com",80f,735f,36f)
                text("studio.example",80f,795f,36f)
            } else text("example.com",80f,675f,36f)
            text("Building 7",960f,620f,36f)
            text("Lake Road",960f,681f,36f)
            text("PO Box 123",960f,742f,36f)
            text("Madhapur",960f,803f,36f)
            text("Hyderabad 500081",960f,864f,36f)
            file.outputStream().use {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
        } finally {bitmap.recycle()}
    }

    /** Test Lab pushes this public model before instrumentation; never trust staging alone. */
    private suspend fun installStagedModel(context:android.content.Context)=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val input=File(requireNotNull(context.getExternalFilesDir(null)),"model-input/gemma.litertlm")
        check(input.isFile && input.length()==VisualModel.BYTES) {"Staged test model missing or wrong size"}
        val directory=File(context.filesDir,"visual-model").apply {check(mkdirs() || isDirectory)}
        check(directory.usableSpace>VisualModel.BYTES+512L*1024*1024) {"Insufficient staged model copy space"}
        val partial=File(directory,"test-staging.partial")
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        var count=0L
        try {
            input.inputStream().use {source->java.io.FileOutputStream(partial).use {output->
                val buffer=ByteArray(256*1024)
                while(true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val read=source.read(buffer);if(read<0) break
                    count+=read;check(count<=VisualModel.BYTES) {"Staged model exceeds pinned size"}
                    digest.update(buffer,0,read);output.write(buffer,0,read)
                }
                output.fd.sync()
            }}
            check(count==VisualModel.BYTES && digest.digest().joinToString("") {"%02x".format(it)}==VisualModel.SHA256) {
                "Staged model checksum mismatch"
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            android.system.Os.rename(partial.absolutePath,File(directory,"gemma-4-e2b.litertlm").absolutePath)
            input.delete()
        } finally {partial.delete()}
    }

    private fun normalize(text:String)=text.lowercase(Locale.ROOT).replace(Regex("\\s+")," ").trim()
}
