package com.thotapalli.visidock

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject

internal data class LocalPhotos(val front:String="",val back:String="")
internal data class PendingCard(val id:String,val cardId:String,val front:String="",val back:String="",val purge:Boolean=false)
internal data class StoredVersion(val version:CardVersion,val photos:LocalPhotos)
internal data class VaultSnapshot(
    val cards:MutableMap<String,Card> = linkedMapOf(),
    val photos:MutableMap<String,LocalPhotos> = linkedMapOf(),
    val pending:MutableMap<String,PendingCard> = linkedMapOf(),
    val history:MutableMap<String,MutableList<StoredVersion>> = linkedMapOf(),
    val conflicts:MutableMap<String,CardConflict> = linkedMapOf()
)

/** All durable contact data and photo bytes are authenticated-encrypted, private, and excluded from OS backup. */
internal class LocalCardVault(context:Context,uid:String) {
    private val account=MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString("") {"%02x".format(it)}
    private val directory=File(context.noBackupFilesDir,"card-vault/$account").apply {mkdirs()}
    private val file=AtomicFile(File(directory,"vault.enc"))
    private val alias="visidock-vault-$account"
    val mutex=locks.getOrPut(account) {Mutex()}
    val syncMutex=syncLocks.getOrPut(account) {Mutex()}
    val changes=signals.getOrPut(account) {MutableStateFlow(0L)}
    private fun key():SecretKey = synchronized(keysLock) {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey(alias,null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    private fun encrypt(bytes:ByteArray):ByteArray=Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE,key());updateAAD(account.toByteArray());iv+doFinal(bytes)
    }
    private fun decrypt(bytes:ByteArray):ByteArray=Cipher.getInstance("AES/GCM/NoPadding").run {
        require(bytes.size>=28) {"The local vault is damaged."}
        init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        updateAAD(account.toByteArray());doFinal(bytes,12,bytes.size-12)
    }
    private fun writeAtomic(destination:AtomicFile,bytes:ByteArray) {
        val output=destination.startWrite()
        try {output.write(encrypt(bytes));destination.finishWrite(output)} catch(error:Throwable) {destination.failWrite(output);throw error}
    }
    fun read():VaultSnapshot {
        committed[account]?.let {return copySnapshot(it)}
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists()) return VaultSnapshot()
        // Never replace unreadable/corrupt data with an empty collection.
        val json=JSONObject(String(decrypt(file.readFully()),Charsets.UTF_8))
        val snapshot=VaultSnapshot()
        json.optJSONArray("cards").each {item->val card=decodeCard(item);snapshot.cards[card.id]=card}
        json.optJSONArray("photos").each {item->snapshot.photos[item.getString("id")]=LocalPhotos(item.optString("front"),item.optString("back"))}
        json.optJSONArray("pending").each {item->val id=item.getString("cardId");snapshot.pending[id]=PendingCard(item.getString("id"),id,item.optString("front"),item.optString("back"),item.optBoolean("purge"))}
        json.optJSONArray("history").each {item->val card=decodeCard(item.getJSONObject("card"));snapshot.history.getOrPut(card.id) {mutableListOf()}.add(StoredVersion(CardVersion(item.getString("id"),card,item.getLong("savedAt")),LocalPhotos(item.optString("front"),item.optString("back"))))}
        json.optJSONArray("conflicts").each {item->val local=decodeCard(item.getJSONObject("local"));snapshot.conflicts[local.id]=CardConflict(local.id,local,decodeCard(item.getJSONObject("remote")))}
        committed[account]=snapshot
        return copySnapshot(snapshot)
    }
    fun write(snapshot:VaultSnapshot) {
        val json=JSONObject().put("schema",1)
            .put("cards",JSONArray(snapshot.cards.values.map(::encodeCard)))
            .put("photos",JSONArray(snapshot.photos.map {(id,p)->JSONObject().put("id",id).put("front",p.front).put("back",p.back)}))
            .put("pending",JSONArray(snapshot.pending.values.map {JSONObject().put("id",it.id).put("cardId",it.cardId).put("front",it.front).put("back",it.back).put("purge",it.purge)}))
            .put("history",JSONArray(snapshot.history.values.flatten().map {JSONObject().put("id",it.version.id).put("card",encodeCard(it.version.card)).put("savedAt",it.version.savedAt).put("front",it.photos.front).put("back",it.photos.back)}))
            .put("conflicts",JSONArray(snapshot.conflicts.values.map {JSONObject().put("local",encodeCard(it.local)).put("remote",encodeCard(it.remote))}))
        writeAtomic(file,json.toString().toByteArray());committed[account]=copySnapshot(snapshot);changes.value+=1
    }
    fun savePhotos(files:ScanFiles):String {
        val id=UUID.randomUUID().toString()
        writeAtomic(AtomicFile(File(directory,"$id-preview.enc")),files.preview.readBytes())
        writeAtomic(AtomicFile(File(directory,"$id-original.enc")),files.original.readBytes())
        writeAtomic(AtomicFile(File(directory,"$id-mime.enc")),files.mime.toByteArray())
        return id
    }
    fun cachePhoto(bytes:ByteArray):String {
        val id=UUID.randomUUID().toString()
        writeAtomic(AtomicFile(File(directory,"$id-preview.enc")),bytes)
        return id
    }
    fun photo(id:String,original:Boolean=false):ByteArray? {
        if(!id.matches(Regex("[a-f0-9-]{36}"))) return null
        val target=File(directory,"$id-${if(original) "original" else "preview"}.enc")
        return if(target.exists()) decrypt(AtomicFile(target).readFully()) else null
    }
    fun materialize(id:String,context:Context):ScanFiles? {
        if(id.isBlank()) return null
        val original=photo(id,true) ?: error("The saved original photo is unavailable. The queued change has been preserved.")
        val preview=photo(id) ?: error("The saved preview is unavailable. The queued change has been preserved.")
        val folder=File(context.cacheDir,"vault-upload/$account").apply {mkdirs()}
        val token=UUID.randomUUID().toString()
        val mimeFile=File(directory,"$id-mime.enc")
        val mime=if(mimeFile.exists()) String(decrypt(mimeFile.readBytes())) else "image/jpeg"
        return ScanFiles(File(folder,"$token-original").apply {writeBytes(original)},File(folder,"$token-preview.jpg").apply {writeBytes(preview)},mime,"")
    }
    fun clear() {
        directory.listFiles()?.filter {it.isFile}?.forEach {it.delete()}
        KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(alias)}
        committed.remove(account)
        changes.value+=1
    }
    fun collectUnusedPhotos(snapshot:VaultSnapshot) {
        val retained=buildSet {
            snapshot.photos.values.forEach {add(it.front);add(it.back)}
            snapshot.history.values.flatten().forEach {add(it.photos.front);add(it.photos.back)}
            snapshot.pending.values.forEach {add(it.front);add(it.back)}
        }
        directory.listFiles()?.forEach {entry->
            val id=entry.name.take(36)
            if(entry.isFile && id.matches(Regex("[a-f0-9-]{36}")) && id !in retained) entry.delete()
        }
    }
    fun cleanUploadTemps(context:Context) {
        File(context.cacheDir,"vault-upload/$account").listFiles()?.filter {it.isFile}?.forEach {it.delete()}
    }
    companion object {
        private val locks=ConcurrentHashMap<String,Mutex>()
        private val syncLocks=ConcurrentHashMap<String,Mutex>()
        private val signals=ConcurrentHashMap<String,MutableStateFlow<Long>>()
        private val keysLock=Any()
        private val committed=ConcurrentHashMap<String,VaultSnapshot>()
        private fun copySnapshot(s:VaultSnapshot)=VaultSnapshot(s.cards.toMutableMap(),s.photos.toMutableMap(),s.pending.toMutableMap(),s.history.mapValues {it.value.toMutableList()}.toMutableMap(),s.conflicts.toMutableMap())
        private fun encodeCard(card:Card)=JSONObject(card.record()).put("id",card.id).put("hasLocalFrontImage",card.hasLocalFrontImage).put("hasLocalBackImage",card.hasLocalBackImage)
        private fun decodeCard(json:JSONObject)=cardFrom(json.getString("id"),json.keys().asSequence().associateWith {json.opt(it)})
        private fun JSONArray?.each(block:(JSONObject)->Unit) {if(this!=null) for(i in 0 until length()) block(getJSONObject(i))}
    }
}
