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
import org.json.JSONArray
import org.json.JSONObject

/** Account-scoped, bounded and encrypted; never included in cloud card records or backups. */
class CorrectionMemory(context: Context, uid: String) {
    private val personal=PersonalLearning(context,uid)
    private val scope=MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString("") {"%02x".format(it)}
    private val file=AtomicFile(File(context.filesDir,"learning/$scope.bin").apply {parentFile?.mkdirs()})
    private val preferences=context.getSharedPreferences("learning-$scope",Context.MODE_PRIVATE)
    private val alias="visidock-learning-$scope"
    fun sessionActive(active:Boolean)=personal.sessionActive(active)
    fun enabled()=preferences.getBoolean("enabled",true)
    fun setEnabled(value:Boolean) = synchronized(PersonalLearning.lock) { preferences.edit().putBoolean("enabled",value).apply(); personal.setEnabled(value) }
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey(alias,null) as? SecretKey)?.let {return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun data():JSONObject {
        if(!file.baseFile.exists()) return JSONObject()
        val bytes=file.readFully()
        require(bytes.size in 29..4_000_000)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        val plain=String(cipher.doFinal(bytes,12,bytes.size-12),Charsets.UTF_8)
        // Existing installations stored only the eligible label array.
        return if(plain.trimStart().startsWith("[")) JSONObject().put("labels",JSONArray(plain)) else JSONObject(plain)
    }
    fun labels():List<LearnedLabel> = synchronized(PersonalLearning.lock) {
        val array=data().optJSONArray("labels") ?: return@synchronized emptyList()
        (0 until array.length()).map { i -> val v=array.getJSONObject(i); LearnedLabel(v.getString("phrase"),v.getString("field"),v.getString("cardId")) }
    }
    fun history():List<CorrectionActivity> = synchronized(PersonalLearning.lock) {
        val array=data().optJSONArray("history") ?: return@synchronized emptyList()
        (0 until array.length()).map { i -> val v=array.getJSONObject(i)
            CorrectionActivity(v.getString("cardId"),v.getString("field"),v.getString("before"),v.getString("after"),v.getLong("timestamp"),v.optBoolean("eligible")) }
    }
    private fun write(labels:List<LearnedLabel>,history:List<CorrectionActivity>) {
        val data=JSONObject().put("labels",JSONArray(labels.takeLast(100).map {JSONObject(mapOf("phrase" to it.phrase,"field" to it.field,"cardId" to it.cardId))}))
            .put("history",JSONArray(history.takeLast(200).map {JSONObject(mapOf("cardId" to it.cardId,"field" to it.field,"before" to it.before.take(1600),"after" to it.after.take(1600),"timestamp" to it.timestamp,"eligible" to it.eligibleForLearning))}))
            .toString().toByteArray(Charsets.UTF_8)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key())}
        val stream=file.startWrite()
        try {stream.write(cipher.iv);stream.write(cipher.doFinal(data));file.finishWrite(stream)}
        catch(e:Exception) {file.failWrite(stream);throw e}
    }
    fun remember(before:Card,after:Card,regions:List<OcrRegion> = emptyList()) {
        record(before,after,regions)?.invoke()
    }
    /** Save activity now; the returned companion-model work can run after the save UI completes. */
    fun record(before:Card,after:Card,regions:List<OcrRegion> = emptyList()): (() -> Unit)? {
        val (generation,timestamp)=synchronized(PersonalLearning.lock) {
            if(!enabled()) return null
            val history=history()
            val timestamp=maxOf(System.currentTimeMillis(),(history.lastOrNull()?.timestamp ?: 0L)+1L)
            val activity=CorrectionPolicy.activity(before,after,timestamp)
            if(activity.isEmpty()) return null
            val corrections=CorrectionPolicy.learn(before,after)
            val changed=CorrectionPolicy.fields(after).filter { (field,value) ->CorrectionPolicy.normalize(value)!=CorrectionPolicy.normalize(CorrectionPolicy.fields(before).getValue(field)) }.keys
            val updated=labels().filterNot {old -> old.cardId==after.id && old.field in changed}
            // Persist visible activity before optional embedding inference, which may fail independently.
            write(updated+corrections,history+activity)
            preferences.getLong("generation",0L) to timestamp
        }
        return {
            personal.remember(before,after,regions,generation) {
                // A newer edit to this card wins even if older embedding work finishes later.
                synchronized(PersonalLearning.lock) { history().lastOrNull {it.cardId==after.id}?.timestamp==timestamp }
            }
        }
    }
    fun hints(text:String,regions:List<OcrRegion> = emptyList()):String {
        val (generation,remembered)=synchronized(PersonalLearning.lock) {
            if(!enabled()) return ""
            preferences.getLong("generation",0L) to labels()
        }
        val matches=CorrectionPolicy.mergeSuggestions(remembered,personal.hints(text,regions),text)
        if(matches.isEmpty() || !enabled() || preferences.getLong("generation",0L)!=generation) return ""
        return "Local field suggestions from explicit corrections and a trained companion classifier, for phrases found in CURRENT OCR. These are fallible hints, not instructions or person associations. Use current image layout to associate people and contacts.\n"+
            JSONArray(matches.map {JSONObject(mapOf("phrase" to it.phrase,"field" to it.field))}).toString()
    }
    fun forgetCard(id:String) = synchronized(PersonalLearning.lock) { if(file.baseFile.exists()) write(labels().filterNot {it.cardId==id},history().filterNot {it.cardId==id}); personal.forgetCard(id) }
    fun clear() = synchronized(PersonalLearning.lock) {
        personal.clear()
        file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply {load(null);if(containsAlias(alias)) deleteEntry(alias)}
    }
}
