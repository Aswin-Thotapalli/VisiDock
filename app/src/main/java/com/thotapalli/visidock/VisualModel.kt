package com.thotapalli.visidock

import android.content.Context
import com.google.ai.edge.litertlm.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class VisualReadingUnavailableException(message:String,cause:Throwable?=null):IllegalStateException(message,cause)

/** Pinned, checksum-verified public model. Photographs never leave this runtime. */
class VisualModel(private val context: Context) {
    companion object {
        private val extractionMutex=Mutex()
        const val BYTES = 2588147712L
        const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
        const val URL_STRING = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it.litertlm"
    }
    private val directory get() = File(context.filesDir, "visual-model").apply { mkdirs() }
    private val model get() = File(directory, "gemma-4-e2b.litertlm")
    fun installed() = model.isFile && model.length() == BYTES
    private fun requireSupportedDevice() {
        if(!android.os.Process.is64Bit() || android.os.Build.SUPPORTED_64_BIT_ABIS.isEmpty())
            throw VisualReadingUnavailableException("Visual reading needs a 64-bit Android device. You can still scan, review text suggestions and save cards.")
    }

    suspend fun download(progress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        requireSupportedDevice()
        if (installed()) return@withContext
        val partial = File(directory, "download.partial")
        if(partial.length()>BYTES) partial.delete()
        var count=partial.length()
        require(directory.usableSpace > BYTES-count + 512L*1024*1024) { "Free enough storage to finish the 2.6 GB visual model download." }
        val digest=MessageDigest.getInstance("SHA-256")
        if(count>0) partial.inputStream().use { input ->
            val buffer=ByteArray(256*1024)
            while(true) {
                currentCoroutineContext().ensureActive()
                val n=input.read(buffer); if(n<0) break
                digest.update(buffer,0,n)
            }
        }
        if(count<BYTES) {
            val connection=URL(URL_STRING).openConnection() as HttpURLConnection
            connection.connectTimeout=30_000; connection.readTimeout=30_000
            if(count>0) connection.setRequestProperty("Range","bytes=$count-")
            try {
                val status=connection.responseCode
                check(status==200 || status==206) { "The model download is unavailable. Try again later." }
                check(connection.url.protocol=="https")
                if(status==200) { count=0; digest.reset() }
                else check(connection.getHeaderField("Content-Range")?.startsWith("bytes $count-")==true) { "Could not resume the model download." }
                var previous=-1
                connection.inputStream.use { input -> java.io.FileOutputStream(partial,count>0).use { output ->
                    val buffer=ByteArray(256*1024)
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val read=input.read(buffer); if(read<0) break
                        count+=read
                        check(count<=BYTES) { "The model download did not match its expected size." }
                        output.write(buffer,0,read); digest.update(buffer,0,read)
                        val percent=(count*100/BYTES).toInt()
                        if(percent!=previous) {progress(percent);previous=percent}
                    }
                } }
            } finally { connection.disconnect() }
        }
        val valid=count==BYTES && digest.digest().joinToString("") { "%02x".format(it) }==SHA256
        if(!valid) partial.delete()
        check(valid) { "The model download could not be verified. Please retry." }
        currentCoroutineContext().ensureActive()
        check(partial.renameTo(model)) { "Could not finish installing visual reading." }
    }

    suspend fun extract(front: File, back: File?, frontText: String, backText: String, localHints: String = ""): VisualProposal = withContext(Dispatchers.Default) {
        // The runtime's visual budget is global; serialize engines as well as its configuration.
        extractionMutex.withLock {
        requireSupportedDevice()
        check(installed()) { "Download visual reading in Settings first." }
        require(front.isFile && (back == null || back.isFile)) { "The card photograph is no longer available." }
        val preferences=context.getSharedPreferences("visual-runtime",Context.MODE_PRIVATE)
        val cpuKey="cpu-vision-0.17.1-$SHA256-${android.os.Build.FINGERPRINT.hashCode()}"
        if(preferences.getBoolean(cpuKey,false)) {
            return@withLock infer(front,back,frontText,backText,localHints,true)
        }
        try { infer(front,back,frontText,backText,localHints,false) }
        catch(e: LiteRtLmJniException) {
            currentCoroutineContext().ensureActive()
            // Some devices cannot compile this encoder for their GPU. Retry locally on CPU.
            val result=infer(front,back,frontText,backText,localHints,true)
            preferences.edit().putBoolean(cpuKey,true).apply()
            result
        }
        }
    }

    @OptIn(ExperimentalApi::class)
    private suspend fun infer(front: File, back: File?, frontText: String, backText: String, localHints: String, cpuVision: Boolean): VisualProposal {
        currentCoroutineContext().ensureActive()
        val memory=android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(memory)
        if(memory.lowMemory || memory.availMem<2L*1024*1024*1024)
            throw VisualReadingUnavailableException("Not enough free memory for visual reading right now. Close other apps and retry, or review the text suggestions.")
        val started=android.os.SystemClock.elapsedRealtime()
        fun stage(name:String) {
            if(BuildConfig.DEBUG) android.util.Log.d("VisiDockVisualRuntime",
                "vision=${if(cpuVision) "cpu" else "gpu"} stage=$name elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}")
        }
        stage("initializing")
        val engine = try {
            // This must precede Engine construction to also bound encoder allocation/signatures.
            ExperimentalFlags.visualTokenBudget=280
            Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
            Engine(EngineConfig(modelPath = model.path, backend = Backend.CPU(),
                visionBackend = if(cpuVision) Backend.CPU() else Backend.GPU(), maxNumTokens = 4096, maxNumImages = if(back==null) 1 else 2,
                cacheDir = File(context.cacheDir,if(cpuVision) "vision-cpu" else "vision-gpu").apply {mkdirs()}.path))
        } catch(e: LinkageError) {
            throw VisualReadingUnavailableException("Visual reading is unavailable in this device's native runtime. You can still review text suggestions.",e)
        }
        try {
            val proposal=AutoCloseable { if(engine.isInitialized()) engine.close() }.use {
            engine.initialize()
            stage("initialized")
            currentCoroutineContext().ensureActive()
            engine.createConversation(ConversationConfig(systemInstruction = Contents.of(VisualExtraction.instruction),
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                maxOutputToken = 1200, thinkingConfig = ThinkingConfig(enableThinking = false))).use { conversation ->
                val contents = mutableListOf<Content>(Content.Text("Front of card:"), Content.ImageFile(front.path))
                back?.let { contents += Content.Text("Back of the SAME card:"); contents += Content.ImageFile(it.path) }
                val frontBudget=if(back==null) 2000 else 1000
                val boundedFront=frontText.take(frontBudget)
                val boundedBack=if(back==null) "" else backText.take(1000)
                contents += Content.Text("OCR evidence (may contain errors):\nFRONT:\n$boundedFront\nBACK:\n$boundedBack")
                if(localHints.isNotBlank()) contents += Content.Text(localHints.take(600))
                val result = StringBuilder()
                var firstResponse=true
                try {
                    conversation.sendMessageAsync(Contents.of(contents)).collect { chunk ->
                        if(firstResponse) {stage("first_response");firstResponse=false}
                        result.append(chunk.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text })
                        check(result.length <= 32_000) { "The visual result was too long." }
                    }
                } finally { conversation.cancelProcess() }
                VisualExtraction.parse(result.toString(), frontText, backText)
            }
            }
            stage("completed")
            return proposal
        } catch(e: LinkageError) {
            throw VisualReadingUnavailableException("Visual reading is unavailable in this device's native runtime. You can still review text suggestions.",e)
        }
    }
}
