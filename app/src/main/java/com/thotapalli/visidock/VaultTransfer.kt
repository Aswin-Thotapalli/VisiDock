package com.thotapalli.visidock

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VaultTransfer(private val context:Context,private val repository:CardRepository) {
    suspend fun export(uri:Uri,cards:List<Card>,format:String,fields:ShareFields)=withContext(Dispatchers.IO) {
        require(format in setOf("csv","vcf","zip"))
        if(format=="zip") {
            val output=context.contentResolver.openOutputStream(uri,"wt") ?: error("The destination could not be opened.")
            java.util.zip.ZipOutputStream(output).use {zip->
                zip.putNextEntry(java.util.zip.ZipEntry("contacts.csv"));zip.write(ContactExport.csv(cards,fields).toByteArray());zip.closeEntry()
                cards.forEachIndexed {index,card->listOf(false,true).forEach {back->
                    if(if(back) card.hasBackImage else card.hasFrontImage) {
                        val bytes=repository.photo(card,back) ?: error("A photo is unavailable. Download it before exporting.")
                        zip.putNextEntry(java.util.zip.ZipEntry("card-${index+1}-${if(back) "back" else "front"}.jpg"));zip.write(bytes);zip.closeEntry()
                    }
                }}
            }
            return@withContext
        }
        context.contentResolver.openOutputStream(uri,"wt")?.bufferedWriter(Charsets.UTF_8)?.use {writer->
            if(format=="csv") writer.write(ContactExport.csv(cards,fields))
            else cards.forEach {writer.write(ContactExport.vcard(it,fields))}
        } ?: error("The destination could not be opened.")
    }
    suspend fun backup(uri:Uri,cards:List<Card>,password:CharArray)=withContext(Dispatchers.IO) {
        val stage=File(context.cacheDir,"backup-${UUID.randomUUID()}").apply {mkdirs()}
        try {
            val entries=mutableListOf<Pair<String,File>>()
            cards.forEach {card->
                require(Regex("[a-zA-Z0-9_-]{1,100}").matches(card.id)) {"A card has an invalid identifier."}
                listOf(false,true).forEach {back->
                    if(if(back) card.hasBackImage else card.hasFrontImage) {
                        val bytes=repository.photo(card,back) ?: error("Download all card photos before making a backup.")
                        val name="photos/${card.id}/${if(back) "back" else "front"}.jpg"
                        val file=File(stage,name).apply {parentFile?.mkdirs();writeBytes(bytes)};entries+=name to file
                        val original=repository.original(card,back) ?: bytes
                        val originalName="photos/${card.id}/${if(back) "back-original" else "original"}.jpg"
                        entries+=originalName to File(stage,originalName).apply {writeBytes(original)}
                    }
                }
            }
            val manifest=File(stage,"manifest.json").apply {writeText(JSONObject(mapOf("version" to 1,"cards" to JSONArray(cards.map {JSONObject(it.record()+ mapOf("id" to it.id,"hasLocalFrontImage" to it.hasLocalFrontImage,"hasLocalBackImage" to it.hasLocalBackImage))}))).toString())}
            entries.add(0,"manifest.json" to manifest)
            val output=context.contentResolver.openOutputStream(uri,"wt") ?: error("The destination could not be opened.")
            output.use {PortableBackup.write(it,password,entries.asSequence())}
        } finally {password.fill('\u0000');stage.deleteRecursively()}
    }
    /** Validate the entire archive before any records change; restore as new IDs to preserve existing cards. */
    suspend fun restore(uri:Uri,password:CharArray):Int=withContext(Dispatchers.IO) {
        val stage=try {context.contentResolver.openInputStream(uri)?.use {PortableBackup.read(it,password,context.cacheDir)} ?: error("Backup could not be opened.")} finally {password.fill('\u0000')}
        try {
            val manifest=JSONObject(File(stage,"manifest.json").readText())
            require(manifest.getInt("version")==1) {"Unsupported backup version."}
            val records=manifest.getJSONArray("cards");require(records.length()<=5000)
            val cards=(0 until records.length()).map {i->
                val json=records.getJSONObject(i);val id=json.getString("id")
                require(Regex("[a-zA-Z0-9_-]{1,100}").matches(id))
                cardFrom(id,json.keys().asSequence().associateWith {json.get(it)}).also {CardLogic.validate(it)?.let {issue->error(issue)}}
            }
            require(cards.map {it.id}.distinct().size==cards.size) {"Duplicate backup identifiers."}
            fun files(card:Card,back:Boolean):ScanFiles? {
                val preview=File(stage,"photos/${card.id}/${if(back) "back" else "front"}.jpg")
                if(!preview.isFile) {require(!(if(back) card.hasBackImage else card.hasFrontImage)) {"A backed-up photo is missing."};return null}
                val original=File(stage,"photos/${card.id}/${if(back) "back-original" else "original"}.jpg").takeIf {it.isFile} ?: preview
                fun inspect(file:File,maxPixels:Long=200_000_000L):String {
                    require(file.length() in 1..ImagePipeline.MAX_ORIGINAL) {"Invalid photo size in backup."}
                    val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
                    android.graphics.BitmapFactory.decodeFile(file.path,bounds)
                    require(bounds.outMimeType in setOf("image/jpeg","image/png","image/webp") && bounds.outWidth in 1..20000 && bounds.outHeight in 1..20000 && bounds.outWidth.toLong()*bounds.outHeight<=maxPixels) {"Invalid photo dimensions or format in backup."}
                    return bounds.outMimeType
                }
                inspect(preview,4_000_000L)
                return ScanFiles(original,preview,inspect(original),if(back) card.backRawText else card.rawText)
            }
            val prepared=cards.map {Triple(it,files(it,false),files(it,true))}
            var completed=0
            try {prepared.forEach {(card,front,back)->
                repository.save(card.copy(id=UUID.randomUUID().toString(),imagePath="",originalPath="",backImagePath="",backOriginalPath="",revision=0,updatedAt=0,deletedAt=0),front,back);completed++
            }} catch(e:Exception) {throw IllegalStateException("Restored $completed of ${cards.size} cards. Existing cards were preserved. Check the collection before retrying.",e)}
            completed
        } finally {stage.deleteRecursively()}
    }
}
