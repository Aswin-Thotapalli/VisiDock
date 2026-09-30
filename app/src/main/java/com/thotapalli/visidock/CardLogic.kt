package com.thotapalli.visidock

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Card(
    val id: String = "", val name: String = "", val role: String = "", val company: String = "",
    val phone: String = "", val email: String = "", val address: String = "", val website: String = "",
    val notes: String = "", val rawText: String = "", val imagePath: String = "",
    val originalPath: String = "", val favorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val backImagePath: String = "", val backOriginalPath: String = "", val backRawText: String = "", val sourceScanId: String = ""
) {
    val displayLabel: String get() = name.ifBlank { company }
    fun fields() = listOf(name, role, company, phone, email, address, website, notes, rawText, backRawText)
}

object CardLogic {
    private val emailPattern = Regex("[A-Z0-9._%+\\-]+@[A-Z0-9.\\-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
    private val phonePattern = Regex("\\+?\\d[\\d() .-]{7,}\\d")
    private val webPattern = Regex("(?:https?://|www\\.)[^\\s,]+|\\b[a-z0-9-]+\\.(?:[a-z]{2,63})(?:\\.[a-z]{2,63})*\\b", RegexOption.IGNORE_CASE)
    private val rolePattern = Regex("\\b(director|manager|founder|ceo|cto|head|officer|consultant|engineer|president|partner|lead|designer|architect)\\b", RegexOption.IGNORE_CASE)
    private val companyPattern = Regex("\\b(labs?|solutions|technologies|technology|industries|limited|ltd|llp|pvt|studio|systems|group|inc|company)\\b", RegexOption.IGNORE_CASE)
    fun extract(text: String): Card {
        val lines = text.lines().map(String::trim).filter(String::isNotBlank)
        val email = emailPattern.find(text)?.value.orEmpty()
        val withoutEmail = text.replace(emailPattern, "")
        val role = lines.firstOrNull { rolePattern.containsMatchIn(it) }.orEmpty()
        val company = lines.firstOrNull { companyPattern.containsMatchIn(it) && it != role }.orEmpty()
        val candidates = lines.filter { it.length in 3..80 && it.any(Char::isLetter) && !it.contains('@') && it.none(Char::isDigit) && !webPattern.containsMatchIn(it) && it != role && it != company }
        return Card(name = candidates.firstOrNull().orEmpty(), role = role,
            company = company,
            phone = phonePattern.findAll(text).map { it.value.trim() }.firstOrNull { it.count(Char::isDigit) in 10..15 }.orEmpty(),
            email = email, website = webPattern.find(withoutEmail)?.value.orEmpty(), address = lines.filter { Regex("(?i)\\b(road|street|avenue|lane|floor|building|nagar|colony|sector|cross|main|district|pin|pincode)\\b|\\b[1-9][0-9]{5}\\b").containsMatchIn(it) && !emailPattern.containsMatchIn(it) && !webPattern.containsMatchIn(it) }.joinToString("\n"), rawText = text.take(12000))
    }
    fun validate(card: Card): String? = when {
        card.name.isBlank() && card.company.isBlank() -> "Add a person or company name so you can find this card again."
        card.name.length > 200 -> "Keep the name under 200 characters."
        listOf(card.role, card.company, card.email, card.website).any { it.length > 300 } -> "Keep role, company, email and website under 300 characters."
        card.phone.length > 100 -> "Keep the phone number under 100 characters."
        card.address.length > 1000 -> "Keep the address under 1,000 characters."
        card.notes.length > 4000 -> "Keep notes under 4,000 characters."
        card.rawText.length > 12000 || card.backRawText.length > 12000 -> "Keep recognized text under 12,000 characters."
        card.email.isNotBlank() && !emailPattern.matches(card.email.trim()) -> "Check the email address, or leave it empty."
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
        val digits = card.phone.filter(Char::isDigit).takeLast(10)
        return cards.filter { it.id != card.id && (
            card.email.isNotBlank() && card.email.trim().equals(it.email.trim(), true) ||
            digits.length == 10 && digits == it.phone.filter(Char::isDigit).takeLast(10)) }
    }
    fun date(millis: Long): String = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
}
