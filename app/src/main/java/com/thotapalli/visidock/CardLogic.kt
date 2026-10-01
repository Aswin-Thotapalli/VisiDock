package com.thotapalli.visidock

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class PhoneNumber(val number: String, val label: String = "")
data class DatedNote(val id:String=java.util.UUID.randomUUID().toString(),val text:String="",val createdAt:Long=System.currentTimeMillis())

/** Explicit separators migrate older records without inventing abbreviated numbers. */
fun splitPhoneNumbers(value: String): List<PhoneNumber> = value
    .split(Regex("[;\\n\\r]+|,\\s*(?=\\+?\\d)"))
    .flatMap { chunk ->
        val parts = chunk.split('/')
        // A printed abbreviated suffix needs review, never its own dial action.
        if (parts.size > 1 && parts.all { it.count(Char::isDigit) >= 7 }) parts else listOf(chunk)
    }.map { PhoneNumber(it.trim()) }.filter { it.number.isNotBlank() }

data class Card(
    val id: String = "", val name: String = "", val role: String = "", val company: String = "",
    val phone: String = "", val email: String = "", val address: String = "", val website: String = "",
    val notes: String = "", val rawText: String = "", val imagePath: String = "",
    val originalPath: String = "", val favorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val backImagePath: String = "", val backOriginalPath: String = "", val backRawText: String = "", val sourceScanId: String = "",
    val phones: List<PhoneNumber> = emptyList(),
    val emails:List<String> = emptyList(),val websites:List<String> = emptyList(),
    val tags:List<String> = emptyList(),val collections:List<String> = emptyList(),
    val meetingDate:String="",val event:String="",val location:String="",
    val datedNotes:List<DatedNote> = emptyList(),val isOwnCard:Boolean=false,
    val updatedAt:Long=0,val revision:Long=0,val deletedAt:Long=0,
    val hasLocalFrontImage:Boolean=false,val hasLocalBackImage:Boolean=false,val transliteratedName:String=""
) {
    val hasFrontImage:Boolean get()=hasLocalFrontImage||imagePath.isNotBlank()
    val hasBackImage:Boolean get()=hasLocalBackImage||backImagePath.isNotBlank()
    val displayLabel: String get() = name.ifBlank { company }
    val contactPhones: List<PhoneNumber> get() = (if (phones.isNotEmpty()) phones else splitPhoneNumbers(phone))
        .map { it.copy(number = it.number.trim(), label = it.label.trim()) }
        .filter { it.number.isNotBlank() }.distinctBy { it.number.filter(Char::isDigit).ifBlank { it.number } }
    val contactEmails:List<String> get()=(listOf(email)+emails).map(String::trim).filter(String::isNotBlank).distinctBy {it.lowercase(Locale.ROOT)}
    val contactWebsites:List<String> get()=(listOf(website)+websites).map(String::trim).filter(String::isNotBlank).distinctBy {it.lowercase(Locale.ROOT)}
    fun fields() = listOf(name, role, company, contactPhones.joinToString(" ") { "${it.label} ${it.number}" }, contactEmails.joinToString(" "), address, contactWebsites.joinToString(" "), notes, rawText, backRawText,tags.joinToString(" "),collections.joinToString(" "),meetingDate,event,location,datedNotes.joinToString(" ") {it.text},transliteratedName)
}

object CardLogic {
    private val emailPattern = Regex("[A-Z0-9._%+\\-]+@[A-Z0-9.\\-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
    private val phonePattern = Regex("\\+?\\d[\\d() .-]{5,}\\d")
    private val webPattern = Regex("(?:https?://|www\\.)[^\\s,]+|\\b[a-z0-9-]+\\.(?:[a-z]{2,63})(?:\\.[a-z]{2,63})*\\b", RegexOption.IGNORE_CASE)
    private val rolePattern = Regex("\\b(director|manager|founder|ceo|cto|head|officer|consultant|engineer|president|partner|lead|designer|architect)\\b", RegexOption.IGNORE_CASE)
    private val companyPattern = Regex("\\b(labs?|solutions|technologies|technology|industries|limited|ltd|llp|pvt|studio|systems|group|inc|company)\\b", RegexOption.IGNORE_CASE)
    fun extract(text: String): Card {
        val lines = text.lines().map(String::trim).filter(String::isNotBlank)
        val email = ContactChannels.emails(text).firstOrNull().orEmpty()
        val role = lines.firstOrNull { rolePattern.containsMatchIn(it) }.orEmpty()
        val company = lines.firstOrNull { companyPattern.containsMatchIn(it) && it != role }.orEmpty()
        val candidates = lines.filter { it.length in 3..80 && it.any(Char::isLetter) && !it.contains('@') && it.none(Char::isDigit) && !webPattern.containsMatchIn(it) && it != role && it != company }
        val numbers = phonePattern.findAll(text).map { it.value.trim() }.filter { it.count(Char::isDigit) in 7..15 }
            .distinctBy { it.filter(Char::isDigit) }.take(12).map { PhoneNumber(it) }.toList()
        return Card(name = candidates.firstOrNull().orEmpty(), role = role,
            company = company,
            phone = numbers.firstOrNull()?.number.orEmpty(), phones = numbers,
            email = email, emails=ContactChannels.emails(text).take(12), websites=ContactChannels.websites(text).take(12), website = ContactChannels.websites(text).firstOrNull().orEmpty(), address = lines.filter { Regex("(?i)\\b(road|street|avenue|lane|floor|building|nagar|colony|sector|cross|main|district|pin|pincode)\\b|\\b[1-9][0-9]{5}\\b").containsMatchIn(it) && !emailPattern.containsMatchIn(it) && !webPattern.containsMatchIn(it) }.joinToString("\n"), rawText = text.take(12000))
    }
    fun validate(card: Card): String? = when {
        card.name.isBlank() && card.company.isBlank() -> "Add a person or company name so you can find this card again."
        card.name.length > 200 -> "Keep the name under 200 characters."
        card.transliteratedName.length>200 -> "Keep the transliterated name under 200 characters."
        listOf(card.role, card.company, card.email, card.website).any { it.length > 300 } -> "Keep role, company, email and website under 300 characters."
        card.phone.length > 100 -> "Keep the phone number under 100 characters."
        card.phones.size > 12 -> "Use at most 12 phone numbers."
        card.phones.any { it.number.length > 80 || it.label.length > 40 } -> "Keep each number under 80 characters and its label under 40."
        card.phones.any { splitPhoneNumbers(it.number).size > 1 } -> "Put each phone number in a separate field."
        card.address.length > 1000 -> "Keep the address under 1,000 characters."
        card.notes.length > 4000 -> "Keep notes under 4,000 characters."
        card.rawText.length > 12000 || card.backRawText.length > 12000 -> "Keep recognized text under 12,000 characters."
        card.email.isNotBlank() && ContactChannels.email(card.email) != card.email.trim() -> "Check the email address, or leave it empty."
        card.website.isNotBlank() && ContactChannels.website(card.website) != card.website.trim() -> "Check the website. Put email addresses in the email field."
        card.contactEmails.size>12 || card.contactEmails.any {it.length>300 || ContactChannels.email(it)!=it} -> "Use at most 12 valid email addresses."
        card.contactWebsites.size>12 || card.contactWebsites.any {it.length>300 || ContactChannels.website(it)!=it} -> "Use at most 12 valid websites."
        card.tags.size>24 || card.collections.size>24 || (card.tags+card.collections).any {it.isBlank() || it.length>80} -> "Use at most 24 tags and collections, each under 80 characters."
        card.event.length>300 || card.location.length>500 -> "Keep event under 300 characters and location under 500."
        card.meetingDate.isNotBlank() && runCatching {java.time.LocalDate.parse(card.meetingDate)}.isFailure -> "Use YYYY-MM-DD for the meeting date."
        card.datedNotes.size>24 || card.datedNotes.map {it.id}.distinct().size!=card.datedNotes.size || card.datedNotes.any {it.id.length !in 1..100 || '\n' in it.id || '\u001f' in it.id || '\u001f' in it.text || it.text.length !in 1..4000 || it.createdAt<=0} -> "Use at most 24 distinct dated notes, each under 4,000 characters, without unsupported control characters."
        else -> null
    }
    private val stop = setOf("find", "show", "me", "all", "the", "a", "an", "who", "was", "that", "from", "at", "in", "i", "met", "person", "card", "cards", "contact", "contacts", "whose", "scanned", "with", "and", "for", "is", "saved", "someone", "please")
    private val groups = listOf(setOf("government", "govt"), setOf("official", "officials", "officer", "officers"), setOf("blockchain", "web3"), setOf("tech", "technology", "technologies"), setOf("bengaluru", "bangalore", "blr"), setOf("manufacturing", "manufacturer", "manufacturers"))
    private fun normalize(value: String) = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    private fun words(value: String) = Regex("[\\p{L}\\p{N}]+").findAll(normalize(value)).map { it.value }.toList()
    private fun canonical(token: String) = groups.firstOrNull { token in it }?.first() ?: token
    /** Local, explainable matching; not semantic AI. Every meaningful query term must match. */
    fun search(cards: List<Card>, query: String, now: Long = System.currentTimeMillis()): List<Card> {
        val q = normalize(query)
        val date = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val period = when { "last month" in q -> date.minusMonths(1); "this month" in q -> date; else -> null }
        val tokens = words(q.replace("last month", "").replace("this month", "")).filter { it !in stop }.map(::canonical).distinct()
        return cards.mapNotNull { card ->
            val saved = Instant.ofEpochMilli(card.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
            if (period != null && (saved.year != period.year || saved.month != period.month)) return@mapNotNull null
            val fields = card.fields().map { words(it).map(::canonical) }
            val scores = tokens.map { token ->
                fields.mapIndexed { index, terms ->
                    if (terms.any { it == token || (token.length >= 3 && it.startsWith(token)) }) when(index) { 0 -> 6; 1, 2 -> 4; else -> 1 } else 0
                }.maxOrNull() ?: 0
            }
            if (scores.any { it == 0 }) null else card to scores.sum()
        }.sortedWith(compareByDescending<Pair<Card, Int>> { it.second }.thenByDescending { it.first.createdAt }).map { it.first }
    }
    fun duplicates(card: Card, cards: List<Card>): List<Card> {
        val numbers = card.contactPhones.map { it.number.filter(Char::isDigit) }.filter { it.length >= 7 }
        return cards.filter { it.id != card.id && (
            card.contactEmails.any {email->it.contactEmails.any {other->email.equals(other,true)}} ||
            it.contactPhones.any { phone -> val digits = phone.number.filter(Char::isDigit)
                numbers.any { number -> number == digits || (number.length >= 10 && digits.length >= 10 && number.takeLast(10) == digits.takeLast(10)) }
            }) }
    }
    fun date(millis: Long): String = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
}
