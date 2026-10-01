package com.thotapalli.visidock

import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object RecognitionPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("recognition-preferences", Context.MODE_PRIVATE)
    fun script(context: Context): OcrScript = runCatching { OcrScript.valueOf(prefs(context).getString("script", "Auto")!!) }.getOrDefault(OcrScript.Auto)
    fun setScript(context: Context, value: OcrScript) { prefs(context).edit().putString("script", value.name).apply() }
    fun effectiveScript(context: Context): OcrScript = script(context).let { selected ->
        if (selected != OcrScript.Auto) selected else OcrScript.forLanguage(context.resources.configuration.locales[0].language)
    }
    fun diagnosticsEnabled(context: Context) = prefs(context).getBoolean("diagnostics", false)
    fun setDiagnosticsEnabled(context: Context, enabled: Boolean) = synchronized(RecognitionDiagnostics.lock) {
        prefs(context).edit().putBoolean("diagnostics", enabled).apply()
        if (!enabled) RecognitionDiagnostics.clear(context)
    }
}

enum class RecognitionStage { Prepare, Decode, OcrFull, OcrDetail, VisualModel, AssignmentReview }
data class RecognitionTiming(val stage: RecognitionStage, val elapsedMillis: Long, val success: Boolean,
    val width: Int = 0, val height: Int = 0, val regionCount: Int = 0) {
    fun json() = JSONObject().put("stage", stage.name).put("elapsedMs", elapsedMillis.coerceAtLeast(0))
        .put("success", success).put("width", width.coerceAtLeast(0)).put("height", height.coerceAtLeast(0))
        .put("regions", regionCount.coerceAtLeast(0))
}

/** Explicitly opt-in, local only. Schema cannot accept contact text, image paths, exceptions or identifiers. */
object RecognitionDiagnostics {
    internal val lock = Any()
    private fun file(context: Context) = File(context.noBackupFilesDir, "recognition-timings.json")
    fun clear(context: Context) = synchronized(lock) { file(context).delete(); Unit }
    fun record(context: Context, timing: RecognitionTiming) = synchronized(lock) {
        if (!RecognitionPreferences.diagnosticsEnabled(context)) return@synchronized
        runCatching {
            val source = file(context)
            val previous = if (source.isFile && source.length() <= 128_000) JSONArray(source.readText()) else JSONArray()
            val values = JSONArray()
            for (i in maxOf(0, previous.length()-119) until previous.length()) values.put(previous.getJSONObject(i))
            values.put(timing.json())
            val atomic = android.util.AtomicFile(source)
            val output = atomic.startWrite()
            try { output.write(values.toString().toByteArray()); atomic.finishWrite(output) }
            catch (e: Exception) { atomic.failWrite(output); throw e }
        }
        Unit
    }
    fun export(context: Context): String = synchronized(lock) {
        val source = file(context)
        val readings = if (RecognitionPreferences.diagnosticsEnabled(context) && source.isFile && source.length() <= 128_000)
            runCatching { JSONArray(source.readText()) }.getOrDefault(JSONArray()) else JSONArray()
        JSONObject().put("format", 1).put("appVersion", BuildConfig.VERSION_NAME).put("api", Build.VERSION.SDK_INT)
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("timings", readings).toString(2)
    }
}
