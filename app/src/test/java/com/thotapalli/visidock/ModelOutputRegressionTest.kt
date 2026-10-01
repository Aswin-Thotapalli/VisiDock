package com.thotapalli.visidock

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Captured real-model output on fictional card images, not hand-authored successful JSON. */
class ModelOutputRegressionTest {
    private fun captured(name:String):Pair<VisualProposal,String> {
        val json=JSONObject(checkNotNull(javaClass.getResourceAsStream("/recognition/$name.json")).bufferedReader().use {it.readText()})
        val rows=json.getJSONArray("regions")
        val evidence=OcrEvidence((0 until rows.length()).map {i->
            val row=rows.getJSONObject(i);val box=row.getJSONArray("box")
            OcrObservation(OcrRegion(row.getString("text"),box.getInt(0)/1000f,box.getInt(1)/1000f,
                box.getInt(2)/1000f,box.getInt(3)/1000f))
        })
        val raw=json.getString("rawText")
        return VisualExtraction.requireUsable(VisualExtraction.parse(json.getString("response"),raw,ocrEvidence=evidence)) to raw
    }
    @Test fun actualLimitedRepairPopulatesOneCompletePerson() {
        val (proposal,raw)=captured("limited-repair-unconstrained")
        val form=ScanReconciliation.reconcile(proposal,Card(id="draft"),emptyList(),emptyMap(),emptyList(),emptyList(),0,null,"scan").cards.single()
        assertEquals("Arjun Mehta",form.name)
        assertEquals("Regional Sales Manager",form.role)
        assertEquals("NORTHLINE TECHNOLOGIES PRIVATE LIMITED",form.company)
        assertEquals("arjun@northline.example",form.email)
        assertEquals(3,form.contactPhones.size)
        assertEquals("Plot No. 27, Sapphire Business Park\nRoad No. 5, Jubilee Hills\nHyderabad, Telangana 500033\nIndia",form.address)
        assertEquals(raw,form.rawText)
    }
    @Test fun actualImageOnlyFieldsPopulateEvenWhenAbsentFromOcr() {
        val (proposal,raw)=captured("ocr-missed-unconstrained")
        val form=ScanReconciliation.reconcile(proposal,Card(id="draft"),emptyList(),emptyMap(),emptyList(),emptyList(),0,null,"scan").cards.single()
        assertFalse(raw.contains("Mira Sen"));assertFalse(raw.contains("Design Director"));assertFalse(raw.contains("Lake Road"))
        assertEquals("Mira Sen",form.name);assertEquals("Design Director",form.role)
        listOf("Building 7","Lake Road","PO Box 123","Madhapur","Hyderabad 500081").forEach {assertTrue(form.address.contains(it))}
        assertEquals(3,form.contactPhones.size);assertEquals(2,form.contactEmails.size);assertEquals(2,form.contactWebsites.size)
        assertEquals(raw,form.rawText)
    }

    @Test fun actualUnconstrainedTwoPersonResponseReachesDistinctForms() {
        val (proposal,raw)=captured("two-person-unconstrained")
        val forms=ScanReconciliation.reconcile(proposal,Card(id="draft"),emptyList(),emptyMap(),emptyList(),emptyList(),0,null,"scan").cards
        assertEquals(2,forms.size)
        assertEquals(listOf("Mira Sen","Dev Rao"),forms.map {it.name})
        assertEquals(listOf("Design Director","Project Lead"),forms.map {it.role})
        assertEquals(listOf("mira@example.com","dev@example.com"),forms.map {it.email})
        assertEquals(listOf("+91 98765 43210","+91 91234 56780"),forms.map {it.phone})
        forms.forEach {
            assertEquals("NORTHLINE STUDIO",it.company)
            assertEquals("12 Lake Road, Bengaluru 560001",it.address)
            assertEquals("www.example.com",it.website)
            assertEquals(raw,it.rawText)
        }
        assertFalse(proposal.reviewIssues.any {it.field=="ownership"})
    }
}
