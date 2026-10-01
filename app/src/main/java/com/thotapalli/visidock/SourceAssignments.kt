package com.thotapalli.visidock

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** A source reference establishes where to review a proposal, not that AI classified it correctly. */
data class FieldSource(val contactIndex: Int, val field: String, val value: String,
    val regionIds: List<String>, val regions: List<OcrRegion>, val modelAssigned: Boolean)
data class ExtractionReviewIssue(val contactIndex: Int, val field: String, val reason: String,
    val sourceIds: List<String> = emptyList())

internal data class AssignmentResult(val json: JSONObject, val sources: List<FieldSource>,
    val issues: List<ExtractionReviewIssue>, val unassigned: List<String>)

/** Recover omitted literals from explicit AI assignments. Never infer a person's name from line order. */
internal object SourceAssignments {
    private val fields = listOf("name", "role", "company", "email", "website", "address")
    private val indexedChannel = Regex("(emails|websites)\\.([0-9]|1[01])")
    private fun value(person: JSONObject, field: String): Any? {
        val match = indexedChannel.matchEntire(field) ?: return person.opt(field)
        return person.optJSONArray(match.groupValues[1])?.opt(match.groupValues[2].toInt())
    }
    private fun put(person: JSONObject, field: String, value: String) {
        val match = indexedChannel.matchEntire(field)
        if (match == null) person.put(field, value) else {
            val key = match.groupValues[1]
            val values = person.optJSONArray(key) ?: JSONArray().also { person.put(key, it) }
            val index = match.groupValues[2].toInt()
            while (values.length() <= index) values.put("")
            values.put(index, value)
        }
    }
    private fun canonical(value: String) = value.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    private fun assignedText(field: String, value: String): String {
        val labels = when (field) {
            "name" -> "name|person|contact"; "role" -> "designation|job title|title|role"
            "company" -> "company|organisation|organization"; "email" -> "e-mail|email"
            "website" -> "website|web"; "address" -> "address"; else -> return value.trim()
        }
        return value.trim().replaceFirst(Regex("^(?:$labels)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
    }

    /** Complete channel evidence can reveal an omission without guessing names or person count. */
    private fun omittedChannelRegions(contacts:JSONArray,registry:Map<String,OcrRegion>):List<String> {
        val strings=mutableListOf<String>()
        val numbers=mutableSetOf<String>()
        for(i in 0 until contacts.length()) {
            val person=contacts.getJSONObject(i)
            listOf("email","website").forEach {key->(person.opt(key) as? String)?.let(strings::add)}
            listOf("emails","websites").forEach {key->person.optJSONArray(key)?.let {values->
                for(j in 0 until values.length()) (values.opt(j) as? String)?.let(strings::add)
            }}
            (person.opt("phone") as? String)?.let {raw->splitPhoneNumbers(raw).forEach {numbers+=it.number.filter(Char::isDigit)}}
            person.optJSONArray("phones")?.let {values->for(j in 0 until values.length()) {
                (values.optJSONObject(j)?.opt("number") as? String)?.let {raw->splitPhoneNumbers(raw).forEach {numbers+=it.number.filter(Char::isDigit)}}
            }}
        }
        val emails=strings.mapNotNull(ContactChannels::email).map {it.lowercase(Locale.ROOT)}.toSet()
        val websites=strings.mapNotNull(ContactChannels::website).map(::webKey).toSet()
        return registry.filterValues {region->
            ContactChannels.emails(region.text).any {it.lowercase(Locale.ROOT) !in emails} ||
                ContactChannels.websites(region.text).any {webKey(it) !in websites} ||
                printedPhones(region.text).any {it !in numbers}
        }.keys.toList()
    }
    private fun webKey(value:String)=value.lowercase(Locale.ROOT).removePrefix("https://").removePrefix("http://").removeSuffix("/")
    private fun printedPhones(text:String):List<String> = text.lines().flatMap {line->
        // Address numbers, dates and arbitrary digit fragments are not strong phone evidence.
        val labelled=Regex("(?i)\\b(?:tel(?:ephone)?|phone|mobile|mob|cell|fax|landline|office|direct)\\b").containsMatchIn(line)
        val onlyNumber=line.all {it.isDigit() || it in "+() .-" || it.isWhitespace()}
        Regex("\\+?\\d[\\d() .-]{5,}\\d").findAll(line).map {it.value.trim()}
            .filter {it.count(Char::isDigit) in 7..15 && (labelled || onlyNumber || it.startsWith("+")) &&
                !Regex("(?:\\d{4}[-./]\\d{1,2}[-./]\\d{1,2}|\\d{1,2}[-./]\\d{1,2}[-./]\\d{2,4})").matches(it)}
            .map {it.filter(Char::isDigit)}.toList()
    }

    /** Explicit image interpretations may share one OCR line only when their literal spans are distinct. */
    private fun distinctSharedNameLiterals(group:List<FieldSource>,original:JSONArray,
        current:JSONArray,registry:Map<String,OcrRegion>):Boolean {
        val ids=group.first().regionIds.toSet()
        val sourceText=canonical(registry.filterKeys {it in ids}.values.joinToString(" ") {it.text})
        val spans=mutableListOf<IntRange>()
        for(source in group) {
            // An ID-only reconstruction is not evidence that a combined line contains another person.
            val literal=(original.getJSONObject(source.contactIndex).opt("name") as? String)?.trim().orEmpty()
            val name=current.getJSONObject(source.contactIndex).optString("name").trim()
            if(literal.isBlank() || name.isBlank() || canonical(literal)!=canonical(name)) return false
            val pattern=Regex("(?=(?<![\\p{L}\\p{M}\\p{N}])("+Regex.escape(canonical(literal))+")(?![\\p{L}\\p{M}\\p{N}]))")
            val matches=pattern.findAll(sourceText).take(2).toList()
            if(matches.size!=1) return false
            val span=checkNotNull(matches.single().groups[1]).range
            if(spans.any {it.first<=span.last && span.first<=it.last}) return false
            spans+=span
        }
        return true
    }

    fun reconcile(input: JSONObject, evidence: OcrEvidence): AssignmentResult {
        val json = JSONObject(input.toString())
        val contacts = json.getJSONArray("contacts")
        val registry = evidence.sourceRegions()
        if (registry.isEmpty()) return AssignmentResult(json, emptyList(), emptyList(), emptyList())
        val sources = mutableListOf<FieldSource>()
        val issues = mutableListOf<ExtractionReviewIssue>()
        val used = mutableSetOf<String>()
        for (i in 0 until contacts.length()) {
            val person = contacts.getJSONObject(i)
            val assignments = person.optJSONObject("sources")
            val channelFields = listOf("emails", "websites").flatMap { key ->
                (0 until minOf(person.optJSONArray(key)?.length() ?: 0, 12)).map { "$key.$it" }
            } + assignments?.keys()?.asSequence()?.filter { indexedChannel.matches(it) }?.toList().orEmpty()
            for (field in (fields + channelFields).distinct()) {
                val raw = value(person, field)
                if (raw != null && raw != JSONObject.NULL && raw !is String) continue // Strict parser reports malformed fields.
                var value = if (raw is String) raw.trim() else ""
                val declared = assignments?.optJSONArray(field)?.let { a ->
                    (0 until minOf(a.length(), 20)).map { a.optString(it) }.distinct()
                }.orEmpty()
                val ids = declared.filter { it in registry }
                if (ids.size != declared.size) issues += ExtractionReviewIssue(i, field,
                    "The model referenced text that was not in this scan.", ids)
                if (ids.isNotEmpty() && value.isBlank() && ids.size == declared.size) {
                    val disputed = ids.any { id -> evidence.disagreements.any { it.primary.region == registry[id] } }
                    // Identity fields spanning many unrelated regions are not safe omission recovery.
                    if (disputed) {
                        issues += ExtractionReviewIssue(i, field, "Assigned text has conflicting OCR readings; inspect the photograph before choosing a value.", ids)
                    } else if (field == "address" || ids.size <= 2) {
                        val joiner = if (field == "address") "\n" else " "
                        val recovered = assignedText(field, ids.joinToString(joiner) { registry.getValue(it).text.trim() })
                        val limit = when (field) { "name" -> 200; "address" -> 1000; else -> 300 }
                        if (recovered.length <= limit) {
                            value = recovered
                            put(person, field, value)
                        } else issues += ExtractionReviewIssue(i, field, "The assigned region contains too much text for this field.", ids)
                    } else issues += ExtractionReviewIssue(i, field, "Too many regions were assigned to one identity field.", ids)
                }
                val matched = if (ids.isNotEmpty()) ids else if (value.isNotEmpty()) registry.filterValues {
                    canonical(it.text) == canonical(value)
                }.keys.toList().takeIf { it.size == 1 }.orEmpty() else emptyList()
                used += matched
                if (matched.isNotEmpty()) sources += FieldSource(i, field, value, matched,
                    matched.map(registry::getValue), ids.isNotEmpty())
                if (ids.isNotEmpty() && value.isNotBlank()) {
                    val sourceText = canonical(ids.joinToString(" ") { registry.getValue(it).text })
                    val matches = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(canonical(value)) + "(?![\\p{L}\\p{N}])")
                        .containsMatchIn(sourceText)
                    if (!matches)
                        issues += ExtractionReviewIssue(i, field, "The proposed value differs from its assigned OCR region. Check the photograph.", ids)
                }
            }
            val ownSources = sources.filter { it.contactIndex == i }
            val name = ownSources.firstOrNull { it.field == "name" }
            val conflicts = ownSources.filter { it.field in listOf("role", "company") &&
                name != null && it.regionIds.intersect(name.regionIds.toSet()).isNotEmpty() &&
                canonical(it.value) == canonical(name.value) }
            if (conflicts.isNotEmpty()) {
                // A designation cannot simultaneously serve as an identical personal name.
                person.put("name", "")
                issues += ExtractionReviewIssue(i, "name", "The same text was assigned as both a name and a ${conflicts.first().field}.", name!!.regionIds)
            }
            person.optJSONArray("phones")?.let { phones ->
                for (p in 0 until minOf(phones.length(), 12)) {
                    val entry = phones.optJSONObject(p) ?: continue
                    val value = entry.optString("number")
                    val ids = entry.optJSONArray("sources")?.let { a -> (0 until minOf(a.length(), 4))
                        .map { a.optString(it) }.filter { it in registry }.distinct() }.orEmpty()
                    val matches = if (ids.isNotEmpty()) ids else registry.filterValues { r ->
                        val digits = value.filter(Char::isDigit)
                        digits.length >= 7 && digits in r.text.filter(Char::isDigit)
                    }.keys.toList().takeIf { it.size == 1 }.orEmpty()
                    used += matches
                    if (matches.isNotEmpty()) sources += FieldSource(i, "phones.$p", value, matches,
                        matches.map(registry::getValue), ids.isNotEmpty())
                }
            }
        }
        // Shared company/office channels are valid. A shared name line needs explicit, unique, disjoint literals.
        sources.filter { it.field == "name" && it.modelAssigned }.groupBy { it.regionIds.toSet() }
            .values.filter { group -> group.map { it.contactIndex }.distinct().size > 1 }.forEach { group ->
                if(distinctSharedNameLiterals(group,input.getJSONArray("contacts"),contacts,registry)) return@forEach
                group.forEach { source ->
                    issues += ExtractionReviewIssue(source.contactIndex, "name", "This name region was assigned to more than one person.", source.regionIds)
                    contacts.getJSONObject(source.contactIndex).put("name", "")
                }
            }
        val omittedChannels=omittedChannelRegions(contacts,registry)
        if(omittedChannels.isNotEmpty()) issues+=ExtractionReviewIssue(0,"ownership",
            "Printed contact channels were omitted. Inspect their layout to determine ownership and whether another person was missed. Keep every person separate; do not attach unassigned channels to the first person.",omittedChannels.take(20))
        val unassigned = registry.keys.filterNot { it in used }
        val explicitlyIgnored = json.optJSONObject("ignoredSources")
        val unexplained = unassigned.filter { explicitlyIgnored?.optString(it).isNullOrBlank() }
        if (registry.isNotEmpty()) for (i in 0 until contacts.length()) {
            val person = contacts.getJSONObject(i)
            if (person.optString("name").isBlank() && person.optString("kind") != "company" &&
                unassigned.any { registry.getValue(it).text.count(Char::isLetter) >= 3 })
                issues += ExtractionReviewIssue(i, "name", "Recognized text remains but no person name was assigned.", unassigned.take(12))
        }
        if (unexplained.size >= 2 && unexplained.sumOf { registry.getValue(it).text.count(Char::isLetterOrDigit) } >= 45)
            issues += ExtractionReviewIssue(0, "unassigned", "Several recognized regions were not assigned to any contact field.", unexplained.take(12))
        return AssignmentResult(json, sources, issues.distinct(), unassigned)
    }

    fun repairPrompt(proposal: VisualProposal): String? {
        if (proposal.reviewIssues.isEmpty()) return null
        val issues = JSONArray(proposal.reviewIssues.take(8).map { issue -> JSONObject()
            .put("person", issue.contactIndex + 1).put("field", issue.field)
            .put("reason", issue.reason).put("sources", JSONArray(issue.sourceIds)) })
        return "Review these assignment problems against the SAME photographs and source IDs: $issues\n" +
            "Return the COMPLETE corrected contacts JSON with sources. Preserve all correctly read details and every person. " +
            "If there is no printed person, mark kind=company. Use the image and layout to distinguish names from roles. " +
            "Do not invent values, ownership or punctuation. Leave genuinely ambiguous fields empty and explain in warnings."
    }

    /** A corrective pass may not erase a person or a previously read contact channel. */
    fun preferRepair(first: VisualProposal, revised: VisualProposal): VisualProposal {
        fun preserved(index: Int, original: Card): Boolean {
            val target = revised.contacts.singleOrNull { original.name.isNotBlank() &&
                canonical(it.name) == canonical(original.name) } ?: revised.contacts.getOrNull(index) ?: return false
            val phones = target.contactPhones.map { it.number.filter(Char::isDigit) }.toSet()
            val unchangedFields = listOf("name" to (original.name to target.name), "role" to (original.role to target.role),
                "company" to (original.company to target.company), "address" to (original.address to target.address))
                .filter { (field, _) -> first.reviewIssues.none { it.contactIndex == index && it.field == field } }
            return unchangedFields.all { (_, values) -> values.first.isBlank() || values.second.isNotBlank() } &&
                original.contactPhones.all { it.number.filter(Char::isDigit) in phones } &&
                original.contactEmails.all { email -> target.contactEmails.any { it.equals(email, true) } } &&
                original.contactWebsites.all { website -> target.contactWebsites.any { it.equals(website, true) } }
        }
        val acceptable = revised.contacts.size >= first.contacts.size &&
            revised.reviewIssues.size < first.reviewIssues.size && first.contacts.withIndex().all { preserved(it.index, it.value) }
        return if (acceptable) revised else first.copy(warnings = (first.warnings +
            "Some source assignments still need review; the second read did not safely resolve them.").distinct())
    }
}
