package com.thotapalli.visidock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ScanFiles(val original: File, val preview: File, val mime: String, val text: String, val regions: List<OcrRegion> = emptyList(), val evidence: OcrEvidence = OcrEvidence())

object ImagePipeline {
    const val MAX_ORIGINAL = 20L * 1024 * 1024
    fun directory(context: Context) = File(context.filesDir, "drafts").apply { mkdirs() }
    suspend fun scan(context: Context, uri: Uri): ScanFiles {
        val files = prepare(context, uri)
        return try { read(context, files) } catch (error: Throwable) {
            files.original.delete(); files.preview.delete(); throw error
        }
    }

    /** Stage both photographs before paying the OCR/model cost. The caller owns returned files. */
    suspend fun prepare(context: Context, uri: Uri): ScanFiles = withContext(Dispatchers.IO) {
        val stageStarted = SystemClock.elapsedRealtime()
        val key = UUID.randomUUID().toString()
        val original = File(directory(context), "$key.original")
        val preview = File(directory(context), "$key.jpg")
        val ownedBitmaps = mutableListOf<Bitmap>()
        var complete = false
        try {
            context.contentResolver.openInputStream(uri)?.use { source ->
                original.outputStream().use { sink ->
                    val buffer = ByteArray(32 * 1024)
                    var count = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = source.read(buffer)
                        if (n < 0) break
                        count += n
                        require(count <= MAX_ORIGINAL) { "Choose an image smaller than 20 MB." }
                        sink.write(buffer, 0, n)
                    }
                }
            } ?: error("This image is no longer available. Choose it again.")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(original.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Choose a readable JPEG, PNG or WebP image." }
            require(bounds.outMimeType in setOf("image/jpeg", "image/png", "image/webp")) { "Choose a JPEG, PNG or WebP image." }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
            val decoded = BitmapFactory.decodeFile(original.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Could not read this image.")
            ownedBitmaps += decoded
            currentCoroutineContext().ensureActive()
            val exif = runCatching { ExifInterface(original) }.getOrNull()
            val matrix = Matrix().apply {
                if (exif?.isFlipped == true) postScale(-1f, 1f)
                postRotate((exif?.rotationDegrees ?: 0).toFloat())
            }
            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (rotated !== decoded) ownedBitmaps += rotated
            if (rotated !== decoded) decoded.recycle()
            val ratio = minOf(1f, 1600f / maxOf(rotated.width, rotated.height))
            val bitmap = Bitmap.createScaledBitmap(rotated, maxOf(1, (rotated.width * ratio).toInt()), maxOf(1, (rotated.height * ratio).toInt()), true)
            if (bitmap !== rotated) ownedBitmaps += bitmap
            if (bitmap !== rotated) rotated.recycle()
            preview.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) { "Could not prepare this image." } }
            currentCoroutineContext().ensureActive()
            complete = true
            ScanFiles(original, preview, bounds.outMimeType, "")
        } finally {
            RecognitionDiagnostics.record(context, RecognitionTiming(RecognitionStage.Prepare,
                SystemClock.elapsedRealtime()-stageStarted, complete))
            ownedBitmaps.forEach { if (!it.isRecycled) it.recycle() }
            if (!complete) { original.delete(); preview.delete() }
        }
    }

    /** OCR the detailed source, not the smaller display JPEG: punctuation needs those pixels. */
    suspend fun read(context: Context, files: ScanFiles): ScanFiles = withContext(Dispatchers.IO) {
        val decodeStarted = SystemClock.elapsedRealtime()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memory = android.app.ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val batteryLevel = battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val quality = RecognitionQuality.budget(OcrDeviceBudget(memory.availMem, memory.lowMemory,
            batteryLow = batteryLevel in 1..15,
            thermalStatus = if (android.os.Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else 0))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(files.original.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This card photograph is no longer available. Retake this side." }
        var sample = 1
        // Decode one sampling level above the target, then resize precisely. A 3000px
        // photograph must not become 1500px before OCR reads its tiny @ and dot marks.
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4800) sample *= 2
        val decoded = BitmapFactory.decodeFile(files.original.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            // Decoder scaling avoids holding both a 4800px intermediate and a
            // separate resized bitmap in an app that also runs a local model.
            inDensity = maxOf(bounds.outWidth, bounds.outHeight) / sample
            inTargetDensity = minOf(inDensity, quality.baseEdge)
            inScaled = inTargetDensity < inDensity
        })
            ?: error("Could not read this card photograph.")
        var bitmap = decoded
        try {
            val exif = runCatching { ExifInterface(files.original) }.getOrNull()
            val matrix = Matrix().apply {
                if (exif?.isFlipped == true) postScale(-1f, 1f)
                postRotate((exif?.rotationDegrees ?: 0).toFloat())
            }
            bitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            RecognitionDiagnostics.record(context, RecognitionTiming(RecognitionStage.Decode,
                SystemClock.elapsedRealtime()-decodeStarted, true, bitmap.width, bitmap.height))
            currentCoroutineContext().ensureActive()
            val recognizer = recognizer(RecognitionPreferences.effectiveScript(context))
            try {
                // Native recognition must finish before its bitmap can safely be released.
                val recognized = recognize(context, recognizer, bitmap, RecognitionStage.OcrFull)
                currentCoroutineContext().ensureActive()
                fun observations(text: com.google.mlkit.vision.text.Text, w: Int, h: Int, area: OcrRegion?, pass: String) =
                    text.textBlocks.flatMap { it.lines }.mapNotNull { line -> line.boundingBox?.let { box ->
                        val left = area?.left ?: 0f; val top = area?.top ?: 0f
                        val width = (area?.right ?: 1f) - left; val height = (area?.bottom ?: 1f) - top
                        OcrObservation(OcrRegion(line.text, left+box.left.toFloat()/w*width,
                            top+box.top.toFloat()/h*height, left+box.right.toFloat()/w*width,
                            top+box.bottom.toFloat()/h*height), line.confidence.takeIf { it.isFinite() && it > 0f && it <= 1f }, pass)
                    } }
                var evidence = OcrEvidence(observations(recognized, bitmap.width, bitmap.height, null, "full"),
                    qualityWarnings = if (quality.baseEdge < 2400) listOf("Available device memory limited this read. Check small text, or retry after closing other apps.") else emptyList())
                val plan = OcrDetailPlanner.plan(evidence.lines, bitmap.width, bitmap.height).take(quality.detailPasses)
                // This is an admission budget, not a native-task timeout. Never recycle a bitmap
                // while ML Kit owns it. Good scans do not enter this loop.
                val started = SystemClock.elapsedRealtime()
                var pixels = 0L
                for (region in plan) {
                    currentCoroutineContext().ensureActive()
                    if (SystemClock.elapsedRealtime()-started > quality.admissionMillis || pixels >= quality.detailPixels) break
                    val detail = decodeDetail(files.original, bounds.outWidth, bounds.outHeight, matrix, region) ?: continue
                    try {
                        if (pixels + detail.bitmap.width.toLong()*detail.bitmap.height > quality.detailPixels ||
                            !RecognitionQuality.addsDetail(bitmap.width, bitmap.height, detail.bitmap.width, detail.bitmap.height, detail.area)) continue
                        pixels += detail.bitmap.width.toLong()*detail.bitmap.height
                        val reading = try {
                            recognize(context, recognizer, detail.bitmap, RecognitionStage.OcrDetail)
                        } catch (_: Exception) {
                            currentCoroutineContext().ensureActive()
                            continue // Optional detail OCR must not discard the complete successful first pass.
                        }
                        currentCoroutineContext().ensureActive()
                        evidence = evidence.merge(observations(reading, detail.bitmap.width, detail.bitmap.height, detail.area, "detail"))
                    } finally { detail.bitmap.recycle() }
                }
                val additional = evidence.lines.filter { it.pass == "detail" }.map { it.region.text }
                val text = (listOf(recognized.text) + additional).filter(String::isNotBlank).joinToString("\n")
                files.copy(text = text, regions = evidence.lines.map { it.region }, evidence = evidence)
            } finally { recognizer.close() }
        } finally {
            if (bitmap !== decoded) bitmap.recycle()
            decoded.recycle()
        }
    }

    private fun recognizer(script: OcrScript): TextRecognizer = when (script) {
        OcrScript.Chinese -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        OcrScript.Devanagari -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        OcrScript.Japanese -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        OcrScript.Korean -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private suspend fun recognize(context: Context, recognizer: TextRecognizer, bitmap: Bitmap,
        stage: RecognitionStage): com.google.mlkit.vision.text.Text {
        val started = SystemClock.elapsedRealtime()
        var success = false
        var regions = 0
        try {
            val result = withContext(NonCancellable) { recognizer.process(InputImage.fromBitmap(bitmap, 0)).await() }
            success = true
            regions = result.textBlocks.sumOf { it.lines.size }
            return result
        } finally {
            RecognitionDiagnostics.record(context, RecognitionTiming(stage, SystemClock.elapsedRealtime()-started,
                success, bitmap.width, bitmap.height, regions))
        }
    }

    private data class Detail(val bitmap: Bitmap, val area: OcrRegion)

    /** Decode only selected native pixels, with EXIF mapped both ways; never allocate a second full photo. */
    @Suppress("DEPRECATION")
    private fun decodeDetail(file: File, width: Int, height: Int, orientation: Matrix, area: OcrRegion): Detail? {
        val upright = RectF(0f, 0f, width.toFloat(), height.toFloat())
        orientation.mapRect(upright)
        val forward = Matrix(orientation).apply { postTranslate(-upright.left, -upright.top) }
        val inverse = Matrix()
        if (!forward.invert(inverse)) return null
        val raw = RectF(area.left*upright.width(), area.top*upright.height(), area.right*upright.width(), area.bottom*upright.height())
        inverse.mapRect(raw)
        val rect = Rect(kotlin.math.floor(raw.left).toInt().coerceIn(0,width-1), kotlin.math.floor(raw.top).toInt().coerceIn(0,height-1),
            kotlin.math.ceil(raw.right).toInt().coerceIn(1,width), kotlin.math.ceil(raw.bottom).toInt().coerceIn(1,height))
        if (rect.width() <= 0 || rect.height() <= 0) return null
        // Integer region rounding must be reflected in the returned source geometry.
        val actual = RectF(rect); forward.mapRect(actual)
        var decoder: BitmapRegionDecoder? = null
        var decoded: Bitmap? = null
        var rotated: Bitmap? = null
        try {
            decoder = BitmapRegionDecoder.newInstance(file.path, false) ?: return null
            var sample = 1
            while (rect.width().toLong()*rect.height()/(sample.toLong()*sample) > 1_500_000L ||
                maxOf(rect.width(), rect.height())/sample > 4800) sample *= 2
            decoded = decoder.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, orientation, true)
            val result = checkNotNull(rotated)
            rotated = null
            if (result === decoded) decoded = null
            return Detail(result, area.copy(left=actual.left/upright.width(), top=actual.top/upright.height(),
                right=actual.right/upright.width(), bottom=actual.bottom/upright.height()))
        } catch (_: IllegalArgumentException) {
            return null // An unsupported optional region decode must not discard the successful full read.
        } catch (_: java.io.IOException) {
            return null
        } finally { rotated?.recycle(); decoded?.recycle(); decoder?.recycle() }
    }

    fun cleanOld(context: Context, keep: Set<String>) {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        directory(context).listFiles()?.filter { it.path !in keep && it.lastModified() < cutoff }?.forEach { it.delete() }
        File(context.cacheDir, "camera").listFiles()?.filter { it.path !in keep && it.lastModified() < cutoff }?.forEach { it.delete() }
    }
}
