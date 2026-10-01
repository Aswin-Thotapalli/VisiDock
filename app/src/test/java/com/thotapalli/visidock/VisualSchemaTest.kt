package com.thotapalli.visidock

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VisualSchemaTest {
    private val root get() = JSONObject(VisualSchema.json)
    private fun properties(schema: JSONObject) = schema.getJSONObject("properties")
    private val contact get() = properties(root).getJSONObject("contacts").getJSONObject("items")

    @Test fun rootAndContactsRejectUnexpectedProfileFieldsAndBoundPeople() {
        assertFalse(root.getBoolean("additionalProperties"))
        assertEquals(setOf("contacts", "warnings", "ignoredSources"), properties(root).keys().asSequence().toSet())
        assertEquals("contacts", root.getJSONArray("required").getString(0))
        val people = properties(root).getJSONObject("contacts")
        assertEquals(1, people.getInt("minItems"))
        assertEquals(12, people.getInt("maxItems"))
        assertFalse(contact.getBoolean("additionalProperties"))
        assertEquals(setOf("kind", "name", "role", "company", "phones", "emails", "websites", "address", "sources"), properties(contact).keys().asSequence().toSet())
        // Company-only and source-assigned contacts must not be forced to invent a person name.
        assertFalse(contact.has("required"))
    }

    @Test fun repeatedChannelsAndTheirSourcesAreSeparateAndBounded() {
        val fields = properties(contact)
        val phones = fields.getJSONObject("phones")
        assertEquals(12, phones.getInt("maxItems"))
        val phone = phones.getJSONObject("items")
        assertFalse(phone.getBoolean("additionalProperties"))
        assertEquals("number", phone.getJSONArray("required").getString(0))
        assertEquals(80, properties(phone).getJSONObject("number").getInt("maxLength"))
        assertEquals(40, properties(phone).getJSONObject("label").getInt("maxLength"))
        val assignments = fields.getJSONObject("sources")
        assertFalse(assignments.getBoolean("additionalProperties"))
        listOf("emails", "websites").forEach { key ->
            val channel = fields.getJSONObject(key)
            assertEquals("array", channel.getString("type"))
            assertEquals(12, channel.getInt("maxItems"))
            assertEquals("string", channel.getJSONObject("items").getString("type"))
            repeat(12) { index -> assertTrue(properties(assignments).has("$key.$index")) }
            assertFalse(properties(assignments).has("$key.12"))
        }
        assertEquals(1000, fields.getJSONObject("address").getInt("maxLength"))
    }

    @Test fun ignoredSourcesAcceptOnlyBoundedFrontBackRegionKeys() {
        val ignored = properties(root).getJSONObject("ignoredSources")
        assertEquals(300, ignored.getInt("maxProperties"))
        assertFalse("llguidance does not implement propertyNames", ignored.has("propertyNames"))
        assertFalse(ignored.getBoolean("additionalProperties"))
        val patterns = ignored.getJSONObject("patternProperties")
        assertEquals(1, patterns.length())
        val keyPattern = patterns.keys().next()
        val pattern = Regex(keyPattern)
        listOf("F1", "B9", "F99", "B100", "F299", "B300").forEach { assertTrue(it, pattern.matches(it)) }
        listOf("name", "profile", "F0", "B01", "F301", "B999", "F1\n").forEach { assertFalse(it, pattern.matches(it)) }
        assertEquals("string", patterns.getJSONObject(keyPattern).getString("type"))
        assertEquals(100, patterns.getJSONObject(keyPattern).getInt("maxLength"))
    }

    @Test fun sourceArraysAndWarningsAreBoundedWithoutRequiringOcr() {
        val sources = properties(properties(contact).getJSONObject("sources"))
        sources.keys().asSequence().toSet().forEach { key ->
            val value = sources.getJSONObject(key)
            assertEquals("array", value.getString("type"))
            assertEquals(20, value.getInt("maxItems"))
            assertEquals("string", value.getJSONObject("items").getString("type"))
            assertEquals(4, value.getJSONObject("items").getInt("maxLength"))
        }
        val warnings = properties(root).getJSONObject("warnings")
        assertEquals(12, warnings.getInt("maxItems"))
        assertEquals(300, warnings.getJSONObject("items").getInt("maxLength"))
        assertTrue("Schema should stay compact enough for a mobile grammar compiler", VisualSchema.json.length < 8000)
    }
}
