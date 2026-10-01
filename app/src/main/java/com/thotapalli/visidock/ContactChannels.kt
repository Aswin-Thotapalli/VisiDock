package com.thotapalli.visidock

import java.net.URI
import java.util.Locale

/** Format evidence separates contact channels; a missing @ is never guessed from a domain. */
object ContactChannels {
    private val email = Regex("[A-Z0-9!#$%&'*+/=?^_`{|}~.\\-]+@[A-Z0-9](?:[A-Z0-9.\\-]*[A-Z0-9])?\\.[A-Z]{2,63}", RegexOption.IGNORE_CASE)
    private val printedEmail = Regex("[A-Z0-9!#$%&'*+/=?^_`{|}~.\\-]+[ \\t]*@[ \\t]*[A-Z0-9-]+(?:[ \\t]*\\.[ \\t]*[A-Z0-9-]+)+", RegexOption.IGNORE_CASE)
    private val host = Regex("(?:[A-Z0-9](?:[A-Z0-9\\-]{0,61}[A-Z0-9])?\\.)+[A-Z]{2,63}", RegexOption.IGNORE_CASE)
    private fun symbols(value: String) = value.replace('＠', '@').replace('﹫', '@')
        .replace('．', '.').replace('。', '.').replace('\u00a0', ' ')
        .replace(Regex("[\\u200B\\u200C\\u200D\\uFEFF]"), "").trim()

    fun email(value: String): String? {
        val clean = symbols(value).replace(Regex("^(?:mailto:|e-?mail\\s*:)\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*@\\s*"), "@").replace(Regex("\\s*\\.\\s*"), ".")
            .trim().removeSurrounding("<", ">").removeSurrounding("(", ")").trimEnd(',', ';')
        if (!email.matches(clean) || clean.substringBefore('@').startsWith('.') ||
            clean.substringBefore('@').endsWith('.') || ".." in clean || !host.matches(clean.substringAfter('@'))) return null
        return clean
    }

    fun website(value: String): String? {
        val clean = symbols(value).replace(Regex("^(?:website|web|url)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
            .trimEnd(',', ';')
        if (clean.isBlank() || '@' in clean || clean.any(Char::isWhitespace)) return null
        val uri = runCatching { URI(if ("://" in clean) clean else "https://$clean") }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") || uri.userInfo != null ||
            !host.matches(uri.host.orEmpty()) || uri.port !in -1..65535) return null
        return clean
    }

    fun emails(text: String): List<String> {
        // Stay on each printed line so fragments from different people cannot be joined.
        return symbols(text).lines().flatMap { line ->
            // Normalize only a matched address, not surrounding initials such as "M. mira@…".
            printedEmail.findAll(line).mapNotNull { match ->
                val before = line.getOrNull(match.range.first - 1)
                val afterIndex = match.range.last + 1
                val after = line.getOrNull(afterIndex)
                // A sentence-final dot is punctuation, but .org after a partial
                // match is still part of the address and must not be truncated.
                val terminalDot = after == '.' && (line.getOrNull(afterIndex + 1)?.let {
                    it.isWhitespace() || it in "),;]>"
                } ?: true)
                if (before?.let { it.isLetterOrDigit() || it in "._+@-" } == true ||
                    (!terminalDot && after?.let { it.isLetterOrDigit() || it in "._+@-" } == true)) null else email(match.value)
            }.toList()
        }.distinctBy { it.lowercase(Locale.ROOT) }
    }

    fun websites(text: String): List<String> = symbols(text).lines().flatMap { line ->
        // Remove complete email tokens first; their domain is not a separately printed website.
        if (Regex("^\\s*e-?mail\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(line) && '@' !in line) return@flatMap emptyList()
        val withoutEmails = line.replace(Regex("\\s*@\\s*"), "@")
            .replace(Regex("\\S*@\\S*"), " ")
            .replace(Regex("^\\s*(?:website|web|url)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        withoutEmails.split(Regex("\\s+|[,;]")).mapNotNull { token ->
            // Validate the whole token: never take example.com out of mira©example.com.
            website(token.trim('(', ')', '[', ']', '<', '>').trimEnd('.'))
        }
    }.distinctBy { it.lowercase(Locale.ROOT) }

    data class Resolved(val email: String, val website: String, val warnings: List<String>)
    fun resolve(proposedEmail: String, proposedWebsite: String, evidence: String, allowUnassigned: Boolean): Resolved {
        val warnings = mutableListOf<String>()
        val emails = emails(evidence)
        var mail = email(proposedEmail).orEmpty()
        var web = website(proposedWebsite).orEmpty()
        val misplacedMail = email(proposedWebsite)
        val misplacedWeb = website(proposedEmail)
        val compact = symbols(proposedEmail).filterNot(Char::isWhitespace)
        val matchingAddress = if(mail.isEmpty() && compact.isNotEmpty()) emails.singleOrNull {
            it.replace("@", "").equals(compact.replace("@", ""), true)
        } else null
        if(matchingAddress != null) mail=matchingAddress
        if (mail.isEmpty() && misplacedMail != null) {
            mail = misplacedMail
            warnings += "An email was moved from website to email."
        }
        if(misplacedMail != null && mail.isNotEmpty() && !mail.equals(misplacedMail,true)) {
            warnings += "Another email appeared in website. Review the photograph for additional email addresses."
        }
        if (web.isEmpty() && misplacedWeb != null && email(proposedEmail) == null && matchingAddress == null) {
            web = misplacedWeb; warnings += "A web address was moved from email to website."
        }
        if (allowUnassigned && mail.isEmpty()) mail = emails.singleOrNull().orEmpty()
        // A fluctuating model response must not replace the one complete address
        // actually read from a single-person card. Never do this across people.
        val confirmed = emails.singleOrNull()
        if (allowUnassigned && confirmed != null && mail.isNotEmpty() &&
            !mail.equals(confirmed, true)) {
            mail = confirmed
            warnings += "Email readings differed. Kept the complete address recognized in the photograph; check it before saving."
        }
        if (mail.isEmpty() && emails.isNotEmpty()) {
            warnings += if (allowUnassigned) "Several email addresses were recognized. Choose the correct address from the photograph."
                else "An email was recognized, but its owner was not confirmed. Check this person's address against the photograph."
        }
        if (allowUnassigned && web.isEmpty()) web = websites(evidence).singleOrNull().orEmpty()
        if (proposedEmail.isNotBlank() && mail.isEmpty()) warnings += "Email could not be confirmed. Check the @ symbol against the photograph."
        if (proposedWebsite.isNotBlank() && web.isEmpty() && misplacedMail == null) warnings += "Website could not be confirmed. Check it against the photograph."
        return Resolved(mail, web, warnings)
    }
}
