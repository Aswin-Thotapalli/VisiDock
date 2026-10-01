package com.thotapalli.visidock

data class CardVersion(val id:String,val card:Card,val savedAt:Long)
data class CardConflict(val cardId:String,val local:Card,val remote:Card)
data class LocalVaultState(
    val pendingCount:Int=0,
    val deletedCards:List<Card> = emptyList(),
    val conflicts:List<CardConflict> = emptyList(),
    val syncing:Boolean=false,
    val lastSyncError:String?=null
)
class CardConflictException(val remote:Card):Exception("This card changed on another device. Choose which version to keep.")

/** A proposal only. Callers must obtain an explicit merge action; similarity never deletes a card. */
object CardMerge {
    fun propose(target:Card,source:Card):Card {
        require(target.id!=source.id) {"Choose two different cards."}
        fun prefer(a:String,b:String)=a.ifBlank {b}
        val phones=(target.contactPhones+source.contactPhones).distinctBy {it.number.filter(Char::isDigit)}
        val emails=(target.contactEmails+source.contactEmails).distinctBy {it.lowercase()}
        val websites=(target.contactWebsites+source.contactWebsites).distinctBy {it.lowercase()}
        require(phones.size<=12 && emails.size<=12 && websites.size<=12) {"Review contact channels before merging these cards."}
        return target.copy(name=prefer(target.name,source.name),transliteratedName=prefer(target.transliteratedName,source.transliteratedName),role=prefer(target.role,source.role),
            company=prefer(target.company,source.company),address=prefer(target.address,source.address),
            phone=phones.firstOrNull()?.number.orEmpty(),phones=phones,email=emails.firstOrNull().orEmpty(),emails=emails,
            website=websites.firstOrNull().orEmpty(),websites=websites,
            notes=listOf(target.notes,source.notes).filter(String::isNotBlank).distinct().joinToString("\n\n"),
            tags=(target.tags+source.tags).distinct(),collections=(target.collections+source.collections).distinct(),
            datedNotes=(target.datedNotes+source.datedNotes).distinctBy {it.id},
            meetingDate=prefer(target.meetingDate,source.meetingDate),event=prefer(target.event,source.event),
            location=prefer(target.location,source.location),favorite=target.favorite||source.favorite,
            isOwnCard=target.isOwnCard||source.isOwnCard)
    }
}
