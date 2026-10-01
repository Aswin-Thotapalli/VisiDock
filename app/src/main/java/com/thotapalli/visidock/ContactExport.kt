package com.thotapalli.visidock

import java.nio.charset.StandardCharsets

/** Sharing is an explicit projection: private fields never ride along implicitly. */
data class ShareFields(
    val phones:Boolean=true, val emails:Boolean=true, val websites:Boolean=true,
    val company:Boolean=true, val address:Boolean=false, val notes:Boolean=false
)

object ContactExport {
    private fun notes(card:Card)= (listOf(card.notes)+card.datedNotes.sortedBy {it.createdAt}.map {"${java.time.Instant.ofEpochMilli(it.createdAt)}: ${it.text}"}).filter(String::isNotBlank).joinToString("\n\n")
    private fun escape(value:String)=value.replace("\\","\\\\").replace("\r\n","\n")
        .replace("\r","\n").replace("\n","\\n").replace(";","\\;").replace(",","\\,")

    // RFC 6350 folding counts UTF-8 octets, never splits a code point.
    private fun fold(line:String):String {
        val out=StringBuilder(); var octets=0
        val iterator=line.codePoints().iterator()
        while(iterator.hasNext()) {
            val chars=String(Character.toChars(iterator.nextInt()))
            val size=chars.toByteArray(StandardCharsets.UTF_8).size
            if(octets+size>75) {out.append("\r\n ");octets=1}
            out.append(chars);octets+=size
        }
        return out.toString()
    }
    fun vcard(card:Card, fields:ShareFields=ShareFields()):String {
        val lines=mutableListOf("BEGIN:VCARD","VERSION:3.0","FN:${escape(card.displayLabel)}","N:;${escape(card.displayLabel)};;;")
        if(fields.company) {
            if(card.company.isNotBlank()) lines+="ORG:${escape(card.company)}"
            if(card.role.isNotBlank()) lines+="TITLE:${escape(card.role)}"
        }
        if(fields.phones) card.contactPhones.forEachIndexed {index,phone->
            if(phone.label.isBlank()) lines+="TEL:${escape(phone.number)}"
            else {val group="item${index+1}";lines+="$group.TEL:${escape(phone.number)}";lines+="$group.X-ABLabel:${escape(phone.label)}"}
        }
        if(fields.emails) card.contactEmails.forEach {lines+="EMAIL:${escape(it)}"}
        if(fields.websites) card.contactWebsites.forEach {lines+="URL:${escape(it)}"}
        if(fields.address && card.address.isNotBlank()) lines+="ADR:;;${escape(card.address)};;;;"
        if(fields.notes && notes(card).isNotBlank()) lines+="NOTE:${escape(notes(card))}"
        lines+="END:VCARD"
        return lines.joinToString("\r\n",postfix="\r\n",transform=::fold)
    }
    private fun csvCell(value:String):String {
        // Spreadsheet exports must not evaluate contact content as formulas.
        val safe=if(value.trimStart().firstOrNull() in listOf('=','+','-','@','\t','\r')) "'"+value else value
        return "\""+safe.replace("\"","\"\"")+"\""
    }
    fun csv(cards:List<Card>,fields:ShareFields=ShareFields()):String {
        val headers=mutableListOf("Name")
        if(fields.company) headers+=listOf("Company","Job title")
        if(fields.phones) headers+="Phone numbers"
        if(fields.emails) headers+="Email addresses"
        if(fields.websites) headers+="Websites"
        if(fields.address) headers+="Address"
        if(fields.notes) headers+="Notes"
        val rows=cards.map {card->buildList {
            add(card.displayLabel)
            if(fields.company) {add(card.company);add(card.role)}
            if(fields.phones) add(card.contactPhones.joinToString("\n") {if(it.label.isBlank()) it.number else "${it.label}: ${it.number}"})
            if(fields.emails) add(card.contactEmails.joinToString("\n"))
            if(fields.websites) add(card.contactWebsites.joinToString("\n"))
            if(fields.address) add(card.address)
            if(fields.notes) add(notes(card))
        }}
        return (listOf(headers)+rows).joinToString("\r\n",postfix="\r\n") {row->row.joinToString(",",transform=::csvCell)}
    }
}
