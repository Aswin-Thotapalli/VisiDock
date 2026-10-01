package com.thotapalli.visidock

data class LearnedLabel(val phrase: String, val field: String, val cardId: String)
data class CorrectionActivity(
    val cardId: String, val field: String, val before: String, val after: String,
    val timestamp: Long, val eligibleForLearning: Boolean
)

/** Learn field labels, never transplant a previous person's details into a different card. */
object CorrectionPolicy {
    fun normalize(value: String) = value.lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    fun appears(phrase: String, text: String): Boolean {
        val value=normalize(phrase)
        return value.length>=3 && Regex("(?<![\\p{L}\\p{N}])"+Regex.escape(value)+"(?![\\p{L}\\p{N}])").containsMatchIn(normalize(text))
    }
    fun fields(card: Card) = linkedMapOf("name" to card.name,"role" to card.role,"company" to card.company,
        "phone" to card.contactPhones.joinToString("; ") { it.number },"email" to card.contactEmails.joinToString("; "),
        "website" to card.contactWebsites.joinToString("; "),"address" to card.address)

    /** Activity includes typo fixes and removals even when they cannot safely become training labels. */
    fun activity(before: Card, after: Card, timestamp: Long = System.currentTimeMillis()): List<CorrectionActivity> {
        val old = fields(before).toMutableMap().apply { put("phone", before.contactPhones.joinToString("; ") { "${it.label}: ${it.number}" }) }
        val current = fields(after).toMutableMap().apply { put("phone", after.contactPhones.joinToString("; ") { "${it.label}: ${it.number}" }) }
        val eligible = learn(before, after).map { it.field }.toSet()
        return current.mapNotNull { (field, value) ->
            if (old.getValue(field).trim() == value.trim()) null
            else CorrectionActivity(after.id, field, old.getValue(field), value, timestamp, field in eligible)
        }
    }

    fun learn(proposed: Card, corrected: Card): List<LearnedLabel> {
        val evidence=proposed.rawText+"\n"+proposed.backRawText
        fun values(card: Card): List<Pair<String, String>> = fields(card).filterKeys {
            it !in listOf("phone", "email", "website")
        }.toList() + card.contactPhones.map { "phone" to it.number } +
            card.contactEmails.map { "email" to it } + card.contactWebsites.map { "website" to it }
        val before = values(proposed)
        val current = values(corrected)
        return current.mapNotNull { (field, value) ->
            if (before.none { it.first == field && normalize(it.second) == normalize(value) } && appears(value, evidence) &&
                current.count { normalize(it.second) == normalize(value) } == 1)
                LearnedLabel(value.trim().take(1000), field, corrected.id) else null
        }
    }

    fun relevant(labels: List<LearnedLabel>, text: String): List<LearnedLabel> = labels
        .filter { appears(it.phrase,text) }
        .groupBy {normalize(it.phrase)}
        // Conflicting corrections need fresh interpretation rather than a remembered guess.
        .values.filter { values -> values.map {it.field}.distinct().size==1 }
        .map {it.last()}.sortedByDescending {it.phrase.length}.take(8)

    /** A trained suggestion must not override a known ambiguous or explicitly confirmed label. */
    fun mergeSuggestions(labels:List<LearnedLabel>,inferred:List<Pair<String,String>>,text:String):List<LearnedLabel> {
        val exact=relevant(labels,text)
        val blocked=labels.groupBy {normalize(it.phrase)}.filterValues {values ->values.map {it.field}.distinct().size>1}.keys
        val known=exact.map {normalize(it.phrase)}.toSet()
        return exact+inferred.filter { (phrase,_) ->normalize(phrase) !in blocked && normalize(phrase) !in known && appears(phrase,text)}
            .distinctBy {normalize(it.first)}.take(8).map { (phrase,field) ->LearnedLabel(phrase,field,"") }
    }
}
