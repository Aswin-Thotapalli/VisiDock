package com.thotapalli.visidock

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.work.*
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/** Encrypted examples and weights. Operations serialize across worker, correction, reset and delete. */
class PersonalLearning(private val context:Context,private val uid:String) {
    private val scope=MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString(""){"%02x".format(it)}
    private val alias="visidock-classifier-$scope"
    private val work="personal-training-$scope"
    private val preferences=context.getSharedPreferences("learning-$scope",Context.MODE_PRIVATE)
    private val file=AtomicFile(File(context.noBackupFilesDir,"personal-learning/$scope.bin").apply {parentFile?.mkdirs()})
    companion object { internal val lock=Any() }
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey(alias,null) as? SecretKey)?.let {return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun read():JSONObject {
        if(!file.baseFile.exists()) return JSONObject()
        val b=file.readFully();require(b.size in 29..8_000_000)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,b.copyOfRange(0,12)))}
        return JSONObject(String(cipher.doFinal(b,12,b.size-12),Charsets.UTF_8))
    }
    private fun write(data:JSONObject) {
        data.put("revision",data.optLong("revision",0)+1)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key())}
        val stream=file.startWrite()
        try {stream.write(cipher.iv);stream.write(cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8)));file.finishWrite(stream)} catch(e:Exception) {file.failWrite(stream);throw e}
    }
    private fun examples(data:JSONObject):List<PersonalExample> {
        val list=data.optJSONArray("examples")?:return emptyList()
        return (0 until list.length()).map {i ->val e=list.getJSONObject(i);val f=e.getJSONArray("features");require(f.length()==PersonalClassifier.DIM)
            PersonalExample(e.getString("cardId"),e.getString("group"),e.getString("field"),e.getString("text"),FloatArray(f.length()){f.getDouble(it).toFloat()})}
    }
    private fun encode(examples:List<PersonalExample>)=JSONArray(examples.map {e ->JSONObject(mapOf("cardId" to e.cardId,"group" to e.group,"field" to e.field,"text" to e.text,"features" to JSONArray(e.features.toList())))})
    private fun weights(data:JSONObject):Array<FloatArray> {
        val w=data.optJSONArray("weights")?:return PersonalClassifier.empty()
        require(w.length()==PersonalClassifier.fields.size)
        return Array(w.length()){i ->val row=w.getJSONArray(i);require(row.length()==PersonalClassifier.DIM);FloatArray(row.length()){row.getDouble(it).toFloat()}}
    }
    private fun generation()=preferences.getLong("generation",0L)
    fun remember(before:Card,after:Card,regions:List<OcrRegion>,expectedGeneration:Long?=null,isCurrent:()->Boolean = {true}) {
        val initialGeneration=synchronized(lock) {
            if(!preferences.getBoolean("enabled",true) || (expectedGeneration!=null && expectedGeneration!=generation()) || !isCurrent()) return
            generation()
        }
        val corrections=CorrectionPolicy.learn(before,after)
        // Re-importing an identical card must not inflate independent validation evidence.
        val evidence=CorrectionPolicy.normalize(before.rawText+"\n"+before.backRawText)
        val group=if(evidence.isNotBlank()) MessageDigest.getInstance("SHA-256").digest(evidence.toByteArray())
            .joinToString(""){"%02x".format(it)} else after.sourceScanId.ifBlank {after.id}
        val changed=CorrectionPolicy.fields(after).filter { (field,value) -> CorrectionPolicy.normalize(value)!=CorrectionPolicy.normalize(CorrectionPolicy.fields(before).getValue(field)) }.keys
        if(changed.isEmpty()) return
        // Native inference runs outside the storage monitor, so reset/disable never waits for it.
        val additions=if(corrections.isEmpty()) emptyList() else PersonalEmbeddings(context).use {encoder ->corrections.map {label ->
            val region=regions.filter {CorrectionPolicy.normalize(it.text)==CorrectionPolicy.normalize(label.phrase)}.singleOrNull()
            PersonalExample(after.id,group,label.field,label.phrase,
                PersonalClassifier.features(label.phrase,region,encoder.encode(label.phrase)))
        }}
        synchronized(lock) {
            if(!preferences.getBoolean("enabled",true)||generation()!=initialGeneration || !isCurrent()) return
            val data=read();val existing=examples(data)
            val removed=existing.any {it.cardId==after.id && it.field in changed}
            val merged=(existing.filterNot {it.cardId==after.id && it.field in changed}+additions).takeLast(280)
            if(removed) {data.remove("weights");data.remove("trainedAt")}
            data.put("examples",encode(merged));write(data)
            schedule()
        }
    }
    private fun schedule() {
        val constraints=Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).setRequiresDeviceIdle(true).build()
        WorkManager.getInstance(context).enqueueUniqueWork(work,ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PersonalTrainingWorker>().setConstraints(constraints).setInputData(workDataOf("uid" to uid)).build())
    }
    private data class Snapshot(val data:JSONObject,val examples:List<PersonalExample>,val weights:Array<FloatArray>,val revision:Long,val generation:Long)
    private fun snapshot():Snapshot?=synchronized(lock) {
        if(!preferences.getBoolean("enabled",true)||!file.baseFile.exists()) return@synchronized null
        val data=read();Snapshot(data,examples(data),weights(data),data.optLong("revision",0),generation())
    }
    fun trainIfEligible(shouldContinue:()->Boolean = {true}) {
        val snapshot=snapshot()?:return
        val (training,validation)=PersonalClassifier.split(snapshot.examples)
        if(validation.isEmpty() || training.map {it.field}.distinct().size<2) return
        val candidate=PersonalClassifier.train(training,shouldContinue=shouldContinue)
        if(!PersonalClassifier.acceptable(snapshot.weights,candidate,validation)) return
        synchronized(lock) {
            if(!shouldContinue() || !preferences.getBoolean("enabled",true) || generation()!=snapshot.generation || !file.baseFile.exists()) return
            val current=read()
            // A concurrent correction, deletion or reset invalidates this training snapshot.
            if(current.optLong("revision",0)!=snapshot.revision) return
            current.put("weights",JSONArray(candidate.map {JSONArray(it.toList())}));current.put("trainedAt",System.currentTimeMillis());write(current)
        }
    }
    /** Return hints about current OCR only, never historical contact values. */
    fun hints(text:String,regions:List<OcrRegion>):List<Pair<String,String>> {
        val snapshot=snapshot()?:return emptyList()
        if(!snapshot.data.has("weights")) return emptyList()
        val supported=snapshot.examples.groupBy {it.field}.filterValues {it.map {e ->e.group}.distinct().size>=2}.keys
        val candidates=(if(regions.isNotEmpty()) regions.map {it.text} else text.lines()).filter {it.isNotBlank() && it.length<=160 && CorrectionPolicy.appears(it,text)}.distinct().take(24)
        val result=PersonalEmbeddings(context).use {encoder ->candidates.mapNotNull {line ->
            val region=regions.filter {it.text==line}.singleOrNull()
            val prediction=PersonalClassifier.predict(snapshot.weights,PersonalClassifier.features(line,region,encoder.encode(line)))
            if(prediction.confidence>=0.85f && prediction.field in supported) line to prediction.field else null
        }.take(8)}
        return synchronized(lock) {if(preferences.getBoolean("enabled",true) && generation()==snapshot.generation && file.baseFile.exists() && read().optLong("revision",0)==snapshot.revision) result else emptyList()}
    }
    fun sessionActive(active:Boolean) {
        if(!active) synchronized(lock) {preferences.edit().putLong("generation",generation()+1).apply();WorkManager.getInstance(context).cancelUniqueWork(work)}
        else synchronized(lock) {if(preferences.getBoolean("enabled",true) && file.baseFile.exists()) schedule()}
    }
    fun setEnabled(enabled:Boolean)=synchronized(lock) {
        if(!enabled) {preferences.edit().putLong("generation",generation()+1).apply();WorkManager.getInstance(context).cancelUniqueWork(work)}
        else if(file.baseFile.exists()) schedule()
    }
    fun forgetCard(id:String)=synchronized(lock) {
        preferences.edit().putLong("generation",generation()+1).apply()
        if(!file.baseFile.exists()) return@synchronized
        val data=read();val retained=examples(data).filterNot {it.cardId==id}
        // Retrain from retained evidence; retaining old weights would retain influence of deleted cards.
        data.remove("weights");data.remove("trainedAt");data.put("examples",encode(retained));write(data)
        if(preferences.getBoolean("enabled",true)) schedule()
    }
    fun clear()=synchronized(lock) {
        preferences.edit().putLong("generation",generation()+1).apply()
        WorkManager.getInstance(context).cancelUniqueWork(work);file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply {load(null);if(containsAlias(alias)) deleteEntry(alias)}
    }
}

class PersonalTrainingWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {
        val uid=inputData.getString("uid")?:return Result.failure()
        // No cloud access. Account mismatch cancels this run instead of training another session's data.
        if(runCatching {com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid}.getOrNull()!=uid) return Result.success()
        return try {PersonalLearning(applicationContext,uid).trainIfEligible { !isStopped && runCatching {com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid}.getOrNull()==uid };Result.success()} catch(e:java.util.concurrent.CancellationException) {throw e} catch(_:Exception) {Result.failure()}
    }
}
