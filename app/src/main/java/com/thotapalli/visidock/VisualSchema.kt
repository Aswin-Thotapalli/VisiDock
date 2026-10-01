package com.thotapalli.visidock

import org.json.JSONArray
import org.json.JSONObject

/** Offline schema/evaluation contract. Production uses validated free-form JSON generation
 * after constrained decoding regressed field completeness. This controls syntax/shape, not truth or field ownership.
 * Grounding and channel validation still run after decoding. No account/profile keys exist here.
 * Source IDs remain optional so image-only/legacy OCR paths can use the same contract.
 */
internal object VisualSchema {
    private const val sourcePattern = "^[FB]([1-9]|[1-9][0-9]|[12][0-9]{2}|300)$"

    // The SDK accepts this directly: ResponseFormat.json(VisualSchema.json).
    val json: String by lazy {
        val sourceId = text(4).put("pattern", sourcePattern).put("minLength", 2)
        fun sources() = array(sourceId, 20)
        val fieldSources = JSONObject().apply {
            listOf("name", "role", "company", "phone", "email", "website", "address").forEach { put(it, sources()) }
            listOf("emails", "websites").forEach { channel ->
                repeat(12) { index -> put("$channel.$index", sources()) }
            }
        }
        val phone = closed(JSONObject()
            .put("number", text(80).put("minLength", 1))
            .put("label", text(40))
            .put("sources", sources()), "number")
        val contact = closed(JSONObject()
            .put("kind", text(7).put("enum", JSONArray(listOf("person", "company"))))
            .put("name", text(200))
            .put("role", text(300))
            .put("company", text(300))
            .put("phones", array(phone, 12))
            .put("emails", array(text(300).put("minLength", 1), 12))
            .put("websites", array(text(300).put("minLength", 1), 12))
            .put("address", text(1000))
            .put("sources", closed(fieldSources)))
        val ignored = JSONObject().put("type", "object").put("maxProperties", 300)
            .put("patternProperties", JSONObject().put(sourcePattern, text(100).put("minLength", 1)))
            .put("additionalProperties", false)
        closed(JSONObject()
            .put("contacts", array(contact, 12).put("minItems", 1))
            .put("warnings", array(text(300).put("minLength", 1), 12))
            .put("ignoredSources", ignored), "contacts").toString()
    }

    private fun text(limit: Int) = JSONObject().put("type", "string").put("maxLength", limit)
    private fun array(item: JSONObject, limit: Int) = JSONObject().put("type", "array")
        .put("items", item).put("maxItems", limit)
    private fun closed(properties: JSONObject, vararg required: String) = JSONObject()
        .put("type", "object").put("properties", properties).put("additionalProperties", false)
        .apply { if (required.isNotEmpty()) put("required", JSONArray(required.toList())) }
}
