package com.thotapalli.visidock

/** Field-wise recovery from a second read; omitted unrelated fields never erase the first read. */
internal object RepairReconciliation {
    private fun normalized(value:String)=CorrectionPolicy.normalize(value)
    private val scalarFields=listOf("name","role","company","address")
    private fun scalar(card:Card,field:String)=when(field) {"name"->card.name;"role"->card.role;"company"->card.company;"address"->card.address;else->""}
    private fun issueFor(proposal:VisualProposal,index:Int,field:String)=proposal.reviewIssues.any {
        it.contactIndex==index && (it.field==field || it.field=="ownership")
    }
    private fun printed(value:String,source:FieldSource):Boolean {
        if(value.isBlank() || source.regions.isEmpty()) return false
        val text=normalized(source.regions.joinToString(" ") {it.text})
        return Regex("(?<![\\p{L}\\p{N}])"+Regex.escape(normalized(value))+"(?![\\p{L}\\p{N}])").containsMatchIn(text)
    }
    private fun sameRegion(a:OcrRegion,b:OcrRegion):Boolean {
        if(a.side!=b.side || normalized(a.text)!=normalized(b.text)) return false
        val intersection=(minOf(a.right,b.right)-maxOf(a.left,b.left)).coerceAtLeast(0f)*
            (minOf(a.bottom,b.bottom)-maxOf(a.top,b.top)).coerceAtLeast(0f)
        val minimum=minOf((a.right-a.left)*(a.bottom-a.top),(b.right-b.left)*(b.bottom-b.top))
        return minimum>0 && intersection/minimum>.7f
    }
    internal fun correspondence(first:VisualProposal,revised:VisualProposal,allowOriginalOwnership:Boolean=false):Map<Int,Int> {
        fun blocked(p:VisualProposal,index:Int)=p.reviewIssues.any {it.contactIndex==index && it.field=="ownership"}
        fun channels(card:Card):Set<String> = card.contactEmails.map {"email:${it.lowercase(java.util.Locale.ROOT)}"}.toSet()+
            card.contactPhones.map {it.number.filter(Char::isDigit)}.filter {it.length>=7}.map {"phone:$it"}.toSet()
        val oldChannels=first.contacts.map(::channels)
        val newChannels=revised.contacts.map(::channels)
        val candidates=first.contacts.indices.associateWith { oldIndex ->
            if(!allowOriginalOwnership && blocked(first,oldIndex)) emptyList() else revised.contacts.indices.filter { newIndex ->
                if(blocked(revised,newIndex)) false else {
                    val old=first.contacts[oldIndex];val next=revised.contacts[newIndex]
                    val namesEqual=old.name.isNotBlank() && normalized(old.name)==normalized(next.name)
                    if(old.name.isNotBlank() && next.name.isNotBlank() && !namesEqual) return@filter false
                    val uniqueName=namesEqual && first.contacts.count {normalized(it.name)==normalized(old.name)}==1 &&
                        revised.contacts.count {normalized(it.name)==normalized(next.name)}==1
                    val singleCompatible=first.contacts.size==1 && revised.contacts.size==1 &&
                        (old.name.isBlank() || next.name.isBlank() || namesEqual)
                    val oldName=first.sources.filter {it.contactIndex==oldIndex && it.field=="name"}.flatMap {it.regions}
                    val nextName=revised.sources.filter {it.contactIndex==newIndex && it.field=="name"}.flatMap {it.regions}
                    val identityRegion=oldName.any {a->nextName.any {b->sameRegion(a,b)}}
                    val uniqueChannel=oldChannels[oldIndex].intersect(newChannels[newIndex]).any { channel ->
                        oldChannels.count {channel in it}==1 && newChannels.count {channel in it}==1
                    }
                    uniqueName || singleCompatible || identityRegion || uniqueChannel
                }
            }
        }
        // Enforce a bijection: duplicate source/name evidence is not enough to choose an owner.
        return candidates.filterValues {it.size==1}.mapValues {it.value.single()}.filter {(_,target)->
            candidates.values.count {target in it}==1
        }
    }
    fun merge(first:VisualProposal,revised:VisualProposal):VisualProposal {
        val pairs=correspondence(first,revised)
        val accepted=mutableSetOf<Pair<Int,String>>()
        val sources=first.sources.toMutableList()
        var changes=0
        val cards=first.contacts.mapIndexed {oldIndex,original ->
            val nextIndex=pairs[oldIndex] ?: return@mapIndexed original
            val next=revised.contacts[nextIndex]
            var card=original
            fun evidence(field:String,value:String,aliases:List<String> = listOf(field)):List<FieldSource> =
                revised.sources.filter {it.contactIndex==nextIndex && aliases.any {alias->it.field==alias || it.field.startsWith("$alias.")} &&
                    !issueFor(revised,nextIndex,it.field) && !issueFor(revised,nextIndex,field) && printed(value,it)}
            fun accept(field:String,value:String,proof:List<FieldSource>) {
                changes++
                accepted+=oldIndex to field
                sources.removeAll {it.contactIndex==oldIndex && it.field==field}
                sources+=proof.map {it.copy(contactIndex=oldIndex,field=field,value=value)}
            }
            for(field in scalarFields) {
                val oldValue=scalar(card,field);val newValue=scalar(next,field)
                if(newValue.isBlank() || oldValue.isNotBlank()) continue
                val proof=evidence(field,newValue)
                if(proof.isEmpty()) continue
                card=when(field) {"name"->card.copy(name=newValue);"role"->card.copy(role=newValue);"company"->card.copy(company=newValue);else->card.copy(address=newValue)}
                accept(field,newValue,proof)
            }
            val mails=card.contactEmails.toMutableList()
            next.contactEmails.forEach {value ->
                if(mails.any {it.equals(value,true)} || ContactChannels.email(value)==null) return@forEach
                val proof=evidence("email",value,listOf("email","emails"))
                if(proof.isNotEmpty() && mails.size<12) {mails+=value;accept("emails.${mails.lastIndex}",value,proof);if(mails.size==1) accept("email",value,proof)}
            }
            val websites=card.contactWebsites.toMutableList()
            next.contactWebsites.forEach {value ->
                if(websites.any {it.equals(value,true)} || ContactChannels.website(value)==null) return@forEach
                val proof=evidence("website",value,listOf("website","websites"))
                if(proof.isNotEmpty() && websites.size<12) {websites+=value;accept("websites.${websites.lastIndex}",value,proof);if(websites.size==1) accept("website",value,proof)}
            }
            val phones=card.contactPhones.toMutableList()
            next.contactPhones.forEach {value ->
                if(phones.any {it.number.filter(Char::isDigit)==value.number.filter(Char::isDigit)}) return@forEach
                val proof=evidence("phone",value.number,listOf("phone","phones"))
                if(proof.isNotEmpty() && phones.size<12) {phones+=value;accept("phones.${phones.lastIndex}",value.number,proof);if(phones.size==1) accept("phone",value.number,proof)}
            }
            if(mails!=card.contactEmails) card=card.copy(email=mails.firstOrNull().orEmpty(),emails=mails)
            if(websites!=card.contactWebsites) card=card.copy(website=websites.firstOrNull().orEmpty(),websites=websites)
            if(phones!=card.contactPhones) card=card.copy(phone=phones.firstOrNull()?.number.orEmpty(),phones=phones)
            card
        }
        if(changes==0) return first
        val usedRegions=sources.flatMap {it.regionIds}.toSet()
        val resolvedIssues=first.reviewIssues.filter {(it.contactIndex to it.field) in accepted ||
            (it.field=="unassigned" && it.sourceIds.isNotEmpty() && usedRegions.containsAll(it.sourceIds))}
        val remaining=first.reviewIssues-resolvedIssues.toSet()
        val resolvedWarnings=resolvedIssues.map {"Person ${it.contactIndex+1}: ${it.reason}"}.toSet()
        return first.copy(contacts=cards,sources=sources.distinct(),reviewIssues=remaining,
            unassignedRegionIds=first.unassignedRegionIds.filterNot {it in usedRegions},
            warnings=(first.warnings.filterNot {it in resolvedWarnings}+"The second read recovered source-backed details. Existing contact data was retained; review any remaining issues.").distinct())
    }
}
