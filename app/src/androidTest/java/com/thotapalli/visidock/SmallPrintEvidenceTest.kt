package com.thotapalli.visidock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Synthetic contact only. This measures the native OCR pipeline, not model accuracy. */
class SmallPrintEvidenceTest {
    @Test fun smallFooterPunctuationAndAddressRetainSourceCoordinates() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "small-print-evidence.png")
        val bitmap = Bitmap.createBitmap(3600, 1800, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(15,35,65); typeface=Typeface.create("sans-serif",Typeface.NORMAL) }
            paint.textSize=96f; canvas.drawText("Mira Sen", 160f, 250f, paint)
            paint.textSize=54f; canvas.drawText("Product Designer", 160f, 340f, paint)
            // 24px native characters become 16px on the ordinary 2400px full pass.
            paint.textSize=24f
            canvas.drawText("mira.sen@example.com", 160f, 1440f, paint)
            canvas.drawText("12 Lake Road, Bengaluru 560001", 160f, 1500f, paint)
            source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { bitmap.recycle() }
        try {
            val scan = ImagePipeline.scan(context, Uri.fromFile(source))
            try {
                val allReadings = scan.evidence.lines.map { it.region } + scan.evidence.disagreements.map { it.alternative.region }
                assertTrue("Small printed email must be found in a source-backed read", allReadings.any { "mira.sen@example.com" in it.text })
                assertTrue("Postal line must not be omitted", allReadings.any { "560001" in it.text })
                assertTrue(allReadings.filter { "example.com" in it.text || "560001" in it.text }.all { it.top > .7f && it.bottom <= 1f })
                assertTrue(scan.evidence.lines.any { it.agreed || it.pass == "detail" } || scan.evidence.disagreements.isNotEmpty())
            } finally { scan.original.delete(); scan.preview.delete() }
        } finally { source.delete() }
    }
}
