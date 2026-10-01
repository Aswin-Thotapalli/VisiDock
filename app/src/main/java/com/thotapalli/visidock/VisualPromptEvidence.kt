package com.thotapalli.visidock

import org.json.JSONObject

/** Prompt-only compaction. Original OCR and region evidence remain untouched for local grounding. */
object VisualPromptEvidence {
    data class Result(val text:String,val omittedByBudget:Boolean,val deduplicatedLines:Int)
    fun build(frontText:String,backText:String,evidence:OcrEvidence,maxChars:Int=4600):Result {
        require(maxChars in 1200..12000)
        val sources=evidence.modelContextResult(maxChars/2,includeConflicts=false)
        val ids=evidence.sourceRegions()
        val disagreements=evidence.disagreements.joinToString("\n") {conflict->
            val id=ids.entries.firstOrNull {it.value==conflict.primary.region}?.key
                ?: if(conflict.primary.region.side==1) "BACK" else "FRONT"
            "CONFLICT $id primary=${JSONObject.quote(conflict.primary.region.text)} alternative=${JSONObject.quote(conflict.alternative.region.text)}"
        }
        var omitted=false
        fun bounded(value:String,limit:Int):String {
            if(value.length<=limit) return value
            omitted=true
            return VisualExtraction.boundedOcr(value,limit)
        }
        val conflicts=if(disagreements.isEmpty()) "" else
            "Alternative OCR readings (not additional printed text):\n"+bounded(disagreements,maxChars/4-60)+"\n"
        // Never normalize punctuation, whitespace or case to claim coverage. Duplicate occurrences
        // need separate represented rows; a front-side line cannot cover identical back-side text.
        var removed=0
        fun remaining(raw:String,side:Int):String {
            val covered=sources.fullyRepresented.filter {it.side.coerceIn(0,1)==side}
                .flatMap {it.text.lines()}.groupingBy {it}.eachCount().toMutableMap()
            val rawLines=raw.lines()
            val availableRaw=rawLines.groupingBy {it}.eachCount().toMutableMap()
            val additional=evidence.lines.filter {it.region.side.coerceIn(0,1)==side}.flatMap {it.region.text.lines()}.filter {line->
                val count=availableRaw[line] ?: 0
                if(count>0) {availableRaw[line]=count-1;false} else true
            }
            return (rawLines+additional).filterIndexed {index,line->
                val count=covered[line] ?: 0
                if(line.isNotEmpty() && count>0) {covered[line]=count-1;if(index<rawLines.size) removed++;false} else true
            }.joinToString("\n")
        }
        val front=remaining(frontText,0);val back=remaining(backText,1)
        val header="OCR evidence may contain errors. Source IDs and alternative readings refer to these photographs.\n"
        val sections=mutableListOf<Pair<String,String>>()
        if(front.isNotBlank()) sections+="FRONT OCR not fully represented above:\n" to front
        if(back.isNotBlank()) sections+="BACK OCR not fully represented above:\n" to back
        val budget=maxChars-header.length-sources.text.length-conflicts.length-sections.sumOf {it.first.length+1}
        val raw=buildString {
            var remainingBudget=budget
            sections.forEachIndexed {index,(label,value)->
                val later=sections.drop(index+1).sumOf {it.second.length}
                val allowance=if(index==sections.lastIndex) remainingBudget else
                    (remainingBudget.toLong()*value.length/(value.length+later)).toInt().coerceIn(40,remainingBudget-40)
                val excerpt=bounded(value,allowance)
                append(label).append(excerpt).append('\n');remainingBudget-=excerpt.length
            }
        }
        val text=header+sources.text+conflicts+raw
        check(text.length<=maxChars)
        return Result(text,omitted,removed)
    }
}
