package com.thotapalli.visidock

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Ordered, process-durable drafts. A SavedStateHandle remains the UI cache, not the recovery boundary. */
class DraftStore(context:Context) {
    private val context=context.applicationContext
    private val directory=File(context.noBackupFilesDir,"card-drafts").apply {mkdirs()}
    val errors:StateFlow<String?> = failure
    /** Call on each draft mutation. Values are copied before dispatch; only known draft keys can reach disk. */
    fun queueSnapshot(uid:String,values:Map<String,Any?>) {
        val copy=filter(values).toMap()
        check(commands.trySend {
            if(copy["draftOwner"]!=uid || contentKeys.none {copy[it]!=null}) {
                AtomicFile(file(hash(uid))).delete()
            } else write(hash(uid),copy)
            failure.value=null
        }.isSuccess) {"Draft storage is unavailable."}
    }
    suspend fun load(uid:String):Map<String,Any?> = ordered {
        val values=read(hash(uid))
        if(values["draftOwner"]==uid) values else emptyMap()
    }
    suspend fun clear(uid:String)=ordered {AtomicFile(file(hash(uid))).delete();failure.value=null}
    suspend fun quarantineUnreadable(uid:String)=ordered {
        val original=file(hash(uid))
        val candidates=listOf(original,File(original.path+".bak"),File(original.path+".new")).filter(File::isFile)
        val stamp=java.util.UUID.randomUUID().toString()
        candidates.forEach {source->source.copyTo(File(directory,"${source.name}.$stamp.quarantine"),overwrite=false)}
        candidates.forEach {source->check(source.delete()) {"Could not preserve the damaged draft safely."}}
    }
    suspend fun flush()=ordered {check(failure.value==null) {failure.value.orEmpty()}}
    /** Merge into ImagePipeline.cleanOld's keep set before cleaning, including other signed-in accounts' drafts. */
    suspend fun protectedPaths():Set<String> = ordered {
        val paths=mutableSetOf<String>()
        if(directory.listFiles().orEmpty().any {it.name.endsWith(".quarantine")}) ImagePipeline.directory(context).listFiles().orEmpty().filter(File::isFile).forEach {paths.add(it.canonicalPath)}
        directory.listFiles()?.filter {it.name.matches(Regex("[a-f0-9]{64}[.]enc"))}?.forEach {entry->
            try {read(entry.name.removeSuffix(".enc")).filterKeys {it in pathKeys}.values.filterIsInstance<String>().forEach(paths::add)}
            catch(_:Exception) {
                // Recovery evidence must survive even when an encrypted snapshot is temporarily unreadable.
                ImagePipeline.directory(context).listFiles()?.filter {it.isFile}?.forEach {paths.add(it.canonicalPath)}
            }
        }
        paths
    }
    private suspend fun <T> ordered(block:()->T):T {
        val result=CompletableDeferred<T>()
        check(commands.trySend {try {result.complete(block())} catch(error:Exception) {result.completeExceptionally(error)}}.isSuccess)
        return result.await()
    }
    private fun filter(values:Map<String,Any?>):Map<String,Any?> = buildMap {
        values.forEach {(name,value)->
            if(name !in allowedKeys || value==null) return@forEach
            when(value) {
                is String -> if(name !in pathKeys || safePath(value,name,values["draftOwner"] as? String)) put(name,value)
                is Boolean,is Long,is Int -> if(name !in pathKeys) put(name,value)
                is List<*> -> if(name=="extractionWarnings" && value.all {it is String}) put(name,ArrayList(value.filterIsInstance<String>()))
            }
        }
    }
    private fun safePath(path:String,name:String,owner:String?):Boolean=runCatching {
        val roots=buildSet {
            add(ImagePipeline.directory(context).canonicalFile)
            if(name=="importBackPath" && owner!=null) add(File(context.filesDir,"import-queue/${hash(owner)}").canonicalFile)
            if(name=="cropSource") add(ImageCropper.directory(context).canonicalFile)
            if(name=="camera") add(File(context.cacheDir,"camera").canonicalFile)
        }
        val file=File(path).canonicalFile
        file.parentFile in roots && file.name.isNotBlank()
    }.getOrDefault(false)
    private fun file(account:String)=File(directory,"$account.enc")
    private fun key(account:String,create:Boolean):SecretKey {
        val alias="visidock-draft-$account"
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey(alias,null) as? SecretKey)?.let {return it}
        check(create) {"The draft encryption key is unavailable."}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generateKey()
        }
    }
    private fun write(account:String,values:Map<String,Any?>) {
        val durable=values.toMutableMap()
        // Cache-backed camera/crop inputs need a filesDir copy to survive Android cache eviction.
        for(name in listOf("camera","cropSource")) {
            val path=values[name] as? String ?: continue
            val source=File(path)
            if(!source.isFile || source.length()==0L || source.parentFile?.canonicalFile==ImagePipeline.directory(context).canonicalFile) continue
            require(source.length()<=ImagePipeline.MAX_ORIGINAL) {"The draft photograph exceeds the import limit."}
            val target=File(ImagePipeline.directory(context),"recovered-${hash(account+source.canonicalPath+source.lastModified()+source.length())}")
            if(!target.exists()) {
                val atomic=AtomicFile(target);val output=atomic.startWrite()
                try {source.inputStream().use {it.copyTo(output)};atomic.finishWrite(output)} catch(error:Exception) {atomic.failWrite(output);throw error}
            }
            durable[name]=target.canonicalPath
        }
        val bytes=JSONObject(durable).toString().toByteArray()
        require(bytes.size<=2*1024*1024) {"The draft is too large to recover safely."}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key(account,true));updateAAD(account.toByteArray())}
        val encrypted=cipher.iv+cipher.doFinal(bytes)
        val atomic=AtomicFile(file(account));val output=atomic.startWrite()
        try {output.write(encrypted);atomic.finishWrite(output)} catch(error:Exception) {atomic.failWrite(output);throw error}
    }
    private fun read(account:String):Map<String,Any?> {
        val file=file(account)
        if(!file.exists() && !File(file.path+".bak").exists()) return emptyMap()
        val bytes=AtomicFile(file).readFully()
        require(bytes.size in 28..(2*1024*1024+32)) {"The saved draft is damaged."}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE,key(account,false),GCMParameterSpec(128,bytes.copyOfRange(0,12)));updateAAD(account.toByteArray())
        }
        val json=JSONObject(String(cipher.doFinal(bytes,12,bytes.size-12),Charsets.UTF_8))
        return filter(json.keys().asSequence().associateWith {name->
            val value=json.opt(name)
            if(value is JSONArray) ArrayList((0 until value.length()).mapNotNull {value.opt(it) as? String}) else value
        }).filter { (name,value)->name !in pathKeys || (value is String && File(value).isFile) }
    }
    companion object {
        // One queue also orders recreation of the ViewModel against its previous pending writes.
        private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        private val commands=Channel<()->Unit>(Channel.UNLIMITED)
        private val failure=MutableStateFlow<String?>(null)
        init {
            scope.launch {
                for(command in commands) try {command()} catch(_:Exception) {
                    failure.value="The draft could not be saved on this device. Keep this screen open and try again."
                }
            }
        }
        private fun hash(uid:String)=MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString("") {"%02x".format(it)}
        private val pathKeys=setOf("original","preview","camera","backOriginal","backPreview","cropSource","importBackPath")
        private val contentKeys=pathKeys+setOf("draft","pendingPeople","activeImportId")
        private val allowedKeys=pathKeys+setOf("draftOwner","draft","selected","pendingPeople","scanProposals","protectedEdits",
            "frontRegions","backRegions","frontEvidence","backEvidence","mime","backMime","sourceScanId",
            "captureReview","cropBack","editingImages","imageEditBack","imageRevision","pendingImageRevision",
            "extractionWarnings","activeImportId","fieldSourcesJson","fieldIssuesJson","sourceContactIndex","processedPeopleJson","ownCardChoiceRequired")
    }
}
