package com.thotapalli.visidock

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CardRecordTest {
    @Test fun legacyThreeNumbersMigrateWithoutLosingAny() {
        val record = Card(id="legacy", name="Alex", phone="+91 98765 43210; +91 91234 56780\n040 2345 6789").record()
        assertEquals("+91 98765 43210", record["phone"])
        val restored = cardFrom("legacy", record)
        assertEquals(listOf("+91 98765 43210", "+91 91234 56780", "040 2345 6789"), restored.contactPhones.map { it.number })
        val oldDocument = record.toMutableMap().apply { remove("phones"); put("phone", "+91 98765 43210; +91 91234 56780\n040 2345 6789") }
        assertEquals(restored.contactPhones.map { it.number }, cardFrom("legacy", oldDocument).contactPhones.map { it.number })
    }

    @Test fun firestoreRoundtripPreservesLabelsAndPrimaryCompatibilityField() {
        val phones=listOf(PhoneNumber(number="+91 98765 43210", label="Mobile"), PhoneNumber(number="040 2345 6789",label="Office"),PhoneNumber(number="1800 123 456",label="Support"))
        val source=Card(id="three",name="Alex",phones=phones)
        val record=source.record()
        assertEquals(phones.first().number,record["phone"])
        assertEquals(phones,cardFrom(source.id,record).contactPhones)
    }

    @Test fun savedDraftJsonPreservesAllPhoneMapsAndLabels() {
        val phones=listOf(PhoneNumber(number="+91 98765 43210",label="Mobile"),PhoneNumber(number="040 2345 6789",label="Office"),PhoneNumber(number="1800 123 456",label="Support"))
        val json=JSONObject(JSONObject(Card(id="draft",name="Alex",phones=phones).record()).toString())
        val data=json.keys().asSequence().associateWith { json.get(it) }
        assertEquals(phones,cardFrom("draft",data).contactPhones)
    }
}
