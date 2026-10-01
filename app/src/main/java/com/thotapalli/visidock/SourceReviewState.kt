package com.thotapalli.visidock

import org.json.JSONArray
import org.json.JSONObject

/** Draft-only metadata; never included in Card.record or cloud synchronization. */
object SourceReviewState {
    fun sources(values: List<FieldSource>): String = JSONArray(values.map { source -> JSONObject()
        .put("person", source.contactIndex).put("field", source.field).put("value", source.value)
        .put("ids", JSONArray(source.regionIds)).put("regions", JSONArray(OcrRegions.encode(source.regions)))
        .put("assigned", source.modelAssigned) }).toString()
    fun readSources(value: String?): List<FieldSource> = runCatching {
        if (value.isNullOrBlank() || value.length > 500_000) return emptyList()
        val array = JSONArray(value)
        (0 until minOf(array.length(), 300)).map { i -> val item = array.getJSONObject(i)
            val ids = item.getJSONArray("ids")
            FieldSource(item.getInt("person").coerceIn(0, 11), item.getString("field").take(40), item.getString("value").take(1000),
                (0 until minOf(ids.length(), 20)).map { ids.getString(it).take(10) },
                OcrRegions.decode(item.getJSONArray("regions").toString()), item.optBoolean("assigned"))
        }
    }.getOrDefault(emptyList())
    fun issues(values: List<ExtractionReviewIssue>): String = JSONArray(values.map { issue -> JSONObject()
        .put("person", issue.contactIndex).put("field", issue.field).put("reason", issue.reason)
        .put("ids", JSONArray(issue.sourceIds)) }).toString()
    fun readIssues(value: String?): List<ExtractionReviewIssue> = runCatching {
        if (value.isNullOrBlank() || value.length > 100_000) return emptyList()
        val array = JSONArray(value)
        (0 until minOf(array.length(), 100)).map { i -> val item = array.getJSONObject(i)
            val ids = item.getJSONArray("ids")
            ExtractionReviewIssue(item.getInt("person").coerceIn(0, 11), item.getString("field").take(40), item.getString("reason").take(500),
                (0 until minOf(ids.length(), 20)).map { ids.getString(it).take(10) })
        }
    }.getOrDefault(emptyList())
}
