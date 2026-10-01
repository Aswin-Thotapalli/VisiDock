package com.thotapalli.visidock

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VisualJsonTest {
    private val missingPhoneCloser="""{
  "contacts": [
    {
      "kind": "person",
      "name": "Mira Sen",
      "role": "Design Director",
      "company": "NORTHLINE STUDIO",
      "phones": [
        {
          "number": "+91 98765 43210",
          "label": "",
          "sources": [
          "F4"
        ]
      ],
      "emails": [
        "mira@example.com"
      ],
      "websites": [
        "www.example.com"
      ],
      "address": "12 Lake Road, Bengaluru 560001",
      "sources": {
        "name": [
          "F1"
        ],
        "role": [
          "F3"
        ],
        "company": [
          "F1"
        ],
        "address": [
          "F7"
        ]
      }
    }
  ]
}"""
    @Test fun physicalModelMissingPhoneObjectCloserRecoversWithoutChangingValues() {
        val result=VisualJson.read(missingPhoneCloser)
        assertEquals(1,result.insertedClosers)
        val contacts=JSONObject(result.text).getJSONArray("contacts")
        assertEquals(1,contacts.length())
        val person=contacts.getJSONObject(0)
        assertEquals("Mira Sen",person.getString("name"))
        assertEquals("+91 98765 43210",person.getJSONArray("phones").getJSONObject(0).getString("number"))
        assertEquals("12 Lake Road, Bengaluru 560001",person.getString("address"))
        val complete=VisualExtraction.completedJson(missingPhoneCloser)
        assertEquals(missingPhoneCloser,complete)
        val proposal=VisualExtraction.parse(complete!!,"Mira Sen\nDesign Director\nNORTHLINE STUDIO\n+91 98765 43210\nmira@example.com\nwww.example.com\n12 Lake Road, Bengaluru 560001")
        assertTrue(proposal.warnings.any {it.contains("omitted closing punctuation")})
        assertEquals(1,proposal.contacts.size)
    }
    @Test fun twoIndependentMissingClosersKeepBothPeopleAndAllPhoneValues() {
        val input="""{"contacts":[{"name":"Asha","phones":[{"number":"1234567890"]},{"name":"Mira","phones":[{"number":"9876543210"]}]}"""
        val result=VisualJson.read(input)
        assertEquals(2,result.insertedClosers)
        val contacts=JSONObject(result.text).getJSONArray("contacts")
        assertEquals(2,contacts.length())
        assertEquals("Asha",contacts.getJSONObject(0).getString("name"))
        assertEquals("Mira",contacts.getJSONObject(1).getString("name"))
        assertEquals("9876543210",contacts.getJSONObject(1).getJSONArray("phones").getJSONObject(0).getString("number"))
    }
    @Test fun stringsAndEscapesRemainByteForByteUnchanged() {
        val input="""{"contacts":[{"name":"A \"quoted\" \\ value ] } [ { \u0041","phones":[{"number":"1234567890"]}]}"""
        val result=VisualJson.read(input)
        assertEquals(1,result.insertedClosers)
        assertEquals(input.replace("\"1234567890\"]","\"1234567890\"}]"),result.text)
    }
    @Test fun completeValidJsonIsUntouched() {
        val input="""{"contacts":[{"name":"Asha","phones":[]}],"numbers":[0,-1.25,2e+3],"ok":true,"empty":null}"""
        val result=VisualJson.read(input)
        assertEquals(0,result.insertedClosers);assertEquals(input,result.text)
    }
    @Test fun neverCompletesTruncatedOutputOrAcceptsSecondObjectOrProse() {
        val valid="""{"contacts":[{"name":"Asha"}]}"""
        for(length in 0 until valid.length) assertNull("Prefix $length",VisualExtraction.completedJson(valid.take(length)))
        listOf(valid+" {}",valid+" trailing text","Here is JSON: "+valid,"```json\n$valid\n``` extra",valid+"}","```json\n$valid").forEach {assertNull(it,VisualExtraction.completedJson(it))}
    }
    @Test fun streamingGuardWaitsForEntireFenceAndStillRejectsFalseTerminalBraces() {
        val json="""{"contacts":[{"name":"Asha"}]}"""
        val fenced="```json\n$json\n```"
        for(length in 0 until fenced.length) assertNull("Fence prefix $length",VisualExtraction.completedJson(fenced.take(length)))
        assertEquals(json,VisualExtraction.completedJson(" \t$fenced\r\n"))
        assertEquals(json,VisualExtraction.completedJson("\n$json \t"))
        listOf(
            """{"contacts":[{"name":"brace }""",
            """{"contacts":[{"name":"Asha"}""",
            json+" text }",
            "$fenced extra }",
            "```json\n$json }\n```"
        ).forEach {assertNull(it,VisualExtraction.completedJson(it))}
    }
    @Test fun missingValuesCommasQuotesDuplicateKeysAndExtraRepairsAreRejected() {
        val invalid=listOf(
            """{"contacts":[{"name":}]}""",
            """{"contacts":[{"name":"Asha" "role":"Engineer"}]}""",
            """{"contacts":[{"name":"Asha}]}""",
            """{"contacts":[{"name":"Asha",}]}""",
            """{"contacts":[{"name":"Asha"},]}""",
            """{"contacts":[],"contacts":[{"name":"Mira"}]}""",
            """{"contacts":[],"\u0063ontacts":[{"name":"Mira"}]}""",
            """{"contacts":[{"name":"Asha","bad":NaN}]}""",
            """{"contacts":[{"name":"Asha","bad":01}]}""",
            """{"contacts":[{"name":"Asha","bad":"\x41"}]}""",
            """{"contacts":[{"name":"A","phones":[{"number":"1234567890"]},{"name":"B","phones":[{"number":"1234567890"]},{"name":"C","phones":[{"number":"1234567890"]}]}""",
            """{"contacts":[{"name":"Asha"]""",
            """{"contacts":[{"name":"Asha"}]}/* hidden */"""
        )
        invalid.forEach {assertNull(it,VisualExtraction.completedJson(it))}
    }
}
