package com.thotapalli.visidock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ScanFiles(val original: File, val preview: File, val mime: String, val text: String, val regions: List<OcrRegion> = emptyList())

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
            ownedBitmaps.forEach { if (!it.isRecycled) it.recycle() }
            if (!complete) { original.delete(); preview.delete() }
        }
    }

    /** OCR the detailed source, not the smaller display JPEG: punctuation needs those pixels. */
    suspend fun read(context: Context, files: ScanFiles): ScanFiles = withContext(Dispatchers.IO) {
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
            inTargetDensity = minOf(inDensity, 2400)
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
            currentCoroutineContext().ensureActive()
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val recognized = try {
                // Native recognition must finish before its bitmap can safely be released.
                withContext(NonCancellable) { recognizer.process(InputImage.fromBitmap(bitmap, 0)).await() }
            } finally { recognizer.close() }
            currentCoroutineContext().ensureActive()
            val regions = recognized.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                line.boundingBox?.let { box -> OcrRegion(line.text, box.left.toFloat()/bitmap.width,
                    box.top.toFloat()/bitmap.height, box.right.toFloat()/bitmap.width, box.bottom.toFloat()/bitmap.height) }
            }
            files.copy(text = recognized.text, regions = regions)
        } finally {
            if (bitmap !== decoded) bitmap.recycle()
            decoded.recycle()
        }
    }

    fun cleanOld(context: Context, keep: Set<String>) {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        directory(context).listFiles()?.filter { it.path !in keep && it.lastModified() < cutoff }?.forEach { it.delete() }
        File(context.cacheDir, "camera").listFiles()?.filter { it.path !in keep && it.lastModified() < cutoff }?.forEach { it.delete() }
    }
}
