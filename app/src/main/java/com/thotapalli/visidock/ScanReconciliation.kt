package com.thotapalli.visidock

import org.json.JSONObject
import java.util.UUID

data class ReconciledScan(val cards: List<Card>, val baselines: List<Card>, val sources: List<FieldSource>,
    val issues: List<ExtractionReviewIssue>, val warnings: List<String>,val ownCardChoiceRequired:Boolean=false)

/** Re-reading a scan must not turn the currently edited person into the first model result. */
object ScanReconciliation {
    private val extracted = setOf("name", "role", "company", "phone", "phones", "email", "emails", "website", "websites", "address", "rawText", "backRawText", "sourceScanId")
    private fun normalized(value: String) = CorrectionPolicy.normalize(value)
    private fun overlap(a: OcrRegion, b: OcrRegion): Float {
        if (a.side != b.side) return 0f
        val intersection = (minOf(a.right,b.right)-maxOf(a.left,b.left)).coerceAtLeast(0f) *
            (minOf(a.bottom,b.bottom)-maxOf(a.top,b.top)).coerceAtLeast(0f)
        val minimum = minOf((a.right-a.left)*(a.bottom-a.top), (b.right-b.left)*(b.bottom-b.top))
        return if (minimum > 0) intersection/minimum else 0f
    }
    fun reconcile(proposal: VisualProposal, current: Card?, pending: List<Card>, baselines: Map<String, Card>,
        processed: List<Card>, oldSources: List<FieldSource>, activeIndex: Int, protected: JSONObject?, sourceScanId: String): ReconciledScan {
        val used = mutableSetOf<Int>()
        val output = mutableListOf<Card>(); val originals = mutableListOf<Card>()
        val sources = mutableListOf<FieldSource>(); val issues = mutableListOf<ExtractionReviewIssue>()
        val warnings = proposal.warnings.toMutableList()
        fun match(card: Card, oldIndex: Int?): Int? {
            val names = listOf(card.name, baselines[card.id]?.name.orEmpty()).map(::normalized).filter(String::isNotBlank).toSet()
            val byName = proposal.contacts.indices.filter { it !in used && normalized(proposal.contacts[it].name) in names }
            if (byName.size == 1) return byName.single()
            val regions = oldSources.filter { it.contactIndex == oldIndex && it.field == "name" }.flatMap { it.regions }
            val byRegion = proposal.contacts.indices.filter { index -> index !in used &&
                proposal.sources.filter { it.contactIndex == index && it.field == "name" }.flatMap { it.regions }
                    .any { next -> regions.any { overlap(it,next) > .7f } } }
            return byRegion.singleOrNull()
        }
        fun append(index: Int, previous: Card?, protection: JSONObject? = null) {
            used += index
            val contact = proposal.contacts[index]
            val id = previous?.id ?: UUID.randomUUID().toString()
            var baseline = contact.copy(id=id, sourceScanId=sourceScanId, isOwnCard=previous?.isOwnCard==true)
            val retained=mutableSetOf<String>()
            if(previous!=null && previous.id in baselines) {
                val fields=baseline.record().toMutableMap()
                val before=previous.record()
                for(field in listOf("name","role","company","address")) {
                    if((fields[field] as? String).isNullOrBlank() && !(before[field] as? String).isNullOrBlank()) {
                        fields[field]=before.getValue(field);retained+=field
                    }
                }
                if(baseline.contactPhones.isEmpty() && previous.contactPhones.isNotEmpty()) {
                    fields["phone"]=before.getValue("phone");fields["phones"]=before.getValue("phones");retained+="phones"
                }
                if(baseline.contactEmails.isEmpty() && previous.contactEmails.isNotEmpty()) {
                    fields["email"]=before.getValue("email");fields["emails"]=before.getValue("emails");retained+="email";retained+="emails"
                }
                if(baseline.contactWebsites.isEmpty() && previous.contactWebsites.isNotEmpty()) {
                    fields["website"]=before.getValue("website");fields["websites"]=before.getValue("websites");retained+="website";retained+="websites"
                }
                // Carry-forward is not a new user correction and must not train the model as one.
                baseline=cardFrom(id,fields)
            }
            val preserved = if (previous == null) baseline else cardFrom(id,
                previous.record() + baseline.record().filterKeys { it in extracted }).copy(
                    hasLocalFrontImage=previous.hasLocalFrontImage, hasLocalBackImage=previous.hasLocalBackImage)
            val edited = if (protection == null) preserved else cardFrom(id,
                preserved.record()+protection.keys().asSequence().associateWith { protection.get(it) }).copy(
                    hasLocalFrontImage=preserved.hasLocalFrontImage, hasLocalBackImage=preserved.hasLocalBackImage)
            val target = output.size
            output += edited; originals += baseline
            sources += proposal.sources.filter { it.contactIndex == index && it.field.substringBefore('.') !in retained }.map { it.copy(contactIndex=target) }
            issues += proposal.reviewIssues.filter { it.contactIndex == index }.map { it.copy(contactIndex=target) }
            if(retained.isNotEmpty()) warnings+="Person ${target+1}: The new reading left some fields unresolved. Earlier values were kept; check them against the photograph."
            retained.filterNot {it in listOf("emails","websites")}.forEach {field->
                issues+=ExtractionReviewIssue(target,field,"The new reading did not resolve this field. Your earlier value was kept; check it against the photograph.")
            }
        }
        val needsOwnerChoice=baselines.isEmpty() && current?.isOwnCard==true && proposal.contacts.size>1
        if (baselines.isEmpty()) {
            proposal.contacts.indices.forEach { append(it, if (it == 0) current else null, if (it == 0) protected else null) }
            if(needsOwnerChoice) {
                output.indices.forEach {i->output[i]=output[i].copy(isOwnCard=false);originals[i]=originals[i].copy(isOwnCard=false)}
            }
        } else {
            checkNotNull(current)
            val currentMatch = match(current, activeIndex) ?: if (proposal.contacts.size == 1 &&
                proposal.contacts.single().name.isBlank() && pending.isEmpty() && processed.isEmpty()) 0 else null
            check(currentMatch != null) { "The new reading could not safely match the person you are editing. Your details and remaining people were kept." }
            append(currentMatch, current, protected)
            pending.forEachIndexed { offset, old ->
                val index = match(old, activeIndex+offset+1)
                if (index != null) append(index, old) else {
                    val target = output.size
                    output += old; originals += baselines[old.id] ?: old
                    sources += oldSources.filter { it.contactIndex == activeIndex+offset+1 }.map { it.copy(contactIndex=target) }
                    warnings += "The new reading did not resolve ${old.displayLabel.ifBlank { "one remaining person" }}. Their previous details were kept."
                }
            }
            processed.forEach { old -> match(old, null)?.let(used::add) }
            // Do not reintroduce an already saved/skipped person because their OCR spelling changed.
            val newCapacity = (proposal.contacts.size-(1+pending.size+processed.size)).coerceAtLeast(0)
            proposal.contacts.indices.filterNot { it in used }.take(newCapacity).forEach { append(it, null) }
        }
        return ReconciledScan(output, originals, sources, issues, warnings.distinct(),needsOwnerChoice)
    }
}
