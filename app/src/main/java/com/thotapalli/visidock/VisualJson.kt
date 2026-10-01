package com.thotapalli.visidock

import org.json.JSONObject

/** Syntax recovery only: never synthesize values, infer people, truncate output or complete EOF. */
object VisualJson {
    data class Result(val original:String,val text:String,val insertedClosers:Int)
    fun read(response:String):Result {
        require(response.length<=32_000) {"The visual result was too long."}
        var input=response.trim()
        if(input.startsWith("```")) {
            val opener=if(input.startsWith("```json")) "```json" else "```"
            require(input.length>=opener.length+3 && input.endsWith("```")) {"The JSON fence is incomplete."}
            input=input.substring(opener.length,input.length-3).trim()
        }
        val parser=Parser(input,2)
        val text=parser.document()
        Parser(text,0).document()
        JSONObject(text).getJSONArray("contacts")
        return Result(input,text,parser.repairs)
    }
    private class Parser(private val source:String,private val repairLimit:Int) {
        private var at=0
        private val stack=mutableListOf<Char>()
        private val insertions=mutableListOf<Pair<Int,Char>>()
        var repairs=0;private set
        fun document():String {
            space();require(peek()=='{') {"Expected one JSON object."};value();space()
            require(at==source.length) {"Unexpected text after the JSON object."}
            return buildString {
                var copied=0
                insertions.forEach {(position,closer)->append(source,copied,position);append(closer);copied=position}
                append(source,copied,source.length)
            }
        }
        private fun peek():Char?=source.getOrNull(at)
        private fun space() {while(peek() in listOf(' ','\t','\r','\n')) at++}
        private fun consume(c:Char) {space();require(peek()==c) {"Invalid JSON punctuation."};at++}
        private fun value() {
            space()
            when(peek()) {
                '{'->objectValue();'['->arrayValue();'"'->stringValue()
                't'->literal("true");'f'->literal("false");'n'->literal("null")
                '-',in '0'..'9'->number()
                else->error("Invalid or missing JSON value.")
            }
        }
        private fun open(c:Char) {require(stack.size<64) {"JSON nesting is too deep."};at++;stack+=c}
        private fun finish(expected:Char) {
            space()
            if(peek()==expected) at++
            else {
                val ancestor=when(peek()) {'}'->'{';']'->'[';else->null}
                require(ancestor!=null && stack.dropLast(1).contains(ancestor) && repairs<repairLimit) {"The JSON container is incomplete."}
                // Insert only before an existing ancestor delimiter, never at EOF.
                insertions+=at to expected;repairs++
            }
            stack.removeAt(stack.lastIndex)
        }
        private fun objectValue() {
            open('{');space()
            if(peek()=='}') {finish('}');return}
            val keys=mutableSetOf<String>()
            while(true) {
                space();require(peek()=='"') {"Object keys must be quoted."}
                val start=at;stringValue()
                val key=JSONObject("{"+source.substring(start,at)+":null}").keys().next()
                require(keys.add(key)) {"Duplicate JSON keys are ambiguous."}
                consume(':');value();space()
                if(peek()==',') {at++;continue}
                finish('}');return
            }
        }
        private fun arrayValue() {
            open('[');space()
            if(peek()==']') {finish(']');return}
            while(true) {
                value();space()
                if(peek()==',') {at++;continue}
                finish(']');return
            }
        }
        private fun stringValue() {
            consume('"')
            while(at<source.length) {
                val c=source[at++]
                if(c=='"') return
                require(c.code>=32) {"Unescaped control character in JSON string."}
                if(c=='\\') {
                    require(at<source.length) {"Incomplete JSON escape."}
                    when(source[at++]) {
                        '"','\\','/','b','f','n','r','t'->Unit
                        'u'->{require(at+4<=source.length && source.substring(at,at+4).all {it in '0'..'9'||it in 'a'..'f'||it in 'A'..'F'}) {"Invalid Unicode escape."};at+=4}
                        else->error("Invalid JSON escape.")
                    }
                }
            }
            error("Unterminated JSON string.")
        }
        private fun literal(word:String) {require(source.startsWith(word,at)) {"Invalid JSON literal."};at+=word.length}
        private fun number() {
            val match=Regex("-?(?:0|[1-9][0-9]*)(?:[.][0-9]+)?(?:[eE][+-]?[0-9]+)?").find(source,at)
            require(match!=null && match.range.first==at) {"Invalid JSON number."};at=match.range.last+1
        }
    }
}
