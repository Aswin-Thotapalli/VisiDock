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
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class VisualReadingUnavailableException(message:String,cause:Throwable?=null):IllegalStateException(message,cause)

/** Pinned, checksum-verified public model. Photographs never leave this runtime. */
class VisualModel(private val context: Context, private val gpuLanguage:Boolean=false,
    private val speculativeDecoding:Boolean=false,
    private val evaluationObserver:((String,VisualProposal)->Unit)?=null) {
    private val runtimeScope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private var warmEngine:Engine?=null
    private var warmCpuVision:Boolean?=null
    private var warmImageCount:Int=0
    private var idleRelease:Job?=null
    private fun releaseEngine() {
        warmEngine?.let {runCatching {it.close()}}
        warmEngine=null;warmCpuVision=null;warmImageCount=0
    }
    fun release() { runtimeScope.launch {extractionMutex.withLock {idleRelease?.cancel();releaseEngine()}} }
    private fun scheduleIdleRelease() {
        idleRelease?.cancel()
        idleRelease=runtimeScope.launch { delay(60_000);extractionMutex.withLock {releaseEngine()} }
    }
    private suspend fun <T> withVisionFallback(block:suspend (Boolean)->T):T {
        val preferences=context.getSharedPreferences("visual-runtime",Context.MODE_PRIVATE)
        val cpuKey="cpu-vision-0.17.1-$SHA256-${android.os.Build.FINGERPRINT.hashCode()}"
        if(preferences.getBoolean(cpuKey,false)) return block(true)
        return try {block(false)} catch(e:LiteRtLmJniException) {
            currentCoroutineContext().ensureActive()
            if(!VisualRuntimePolicy.canRetryVisionOnCpu(e.message.orEmpty())) throw e
            block(true).also {preferences.edit().putBoolean(cpuKey,true).apply()}
        }
    }
    /** Initialize weights only. No photo encoding, conversation or inference runs before OCR. */
    suspend fun prewarm(imageCount:Int)=withContext(Dispatchers.Default) {
        require(imageCount in 1..2)
        val started=android.os.SystemClock.elapsedRealtime()
        var succeeded=false
        try {
            extractionMutex.withLock {
                currentCoroutineContext().ensureActive()
                requireSupportedDevice()
                check(installed()) {"Download visual reading in Settings first."}
                idleRelease?.cancel()
                withVisionFallback {cpuVision->prepareEngine(cpuVision,imageCount)}
                scheduleIdleRelease()
                succeeded=true
            }
        } finally {
            RecognitionDiagnostics.record(context,RecognitionTiming(RecognitionStage.VisualPrewarm,
                android.os.SystemClock.elapsedRealtime()-started,succeeded))
        }
    }
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

    suspend fun extract(front: File, back: File?, frontText: String, backText: String, localHints: String = "", ocrEvidence: OcrEvidence = OcrEvidence()): VisualProposal = withContext(Dispatchers.Default) {
        val timingStarted = android.os.SystemClock.elapsedRealtime()
        var succeeded = false
        try {
        // The runtime's visual budget is global; serialize engines as well as its configuration.
        val result = extractionMutex.withLock {
        requireSupportedDevice()
        check(installed()) { "Download visual reading in Settings first." }
        require(front.isFile && (back == null || back.isFile)) { "The card photograph is no longer available." }
        idleRelease?.cancel()
        withVisionFallback {cpuVision->infer(front,back,frontText,backText,localHints,cpuVision,ocrEvidence)}
        }
        succeeded = true
        result
        } finally {
            RecognitionDiagnostics.record(context, RecognitionTiming(RecognitionStage.VisualModel,
                android.os.SystemClock.elapsedRealtime()-timingStarted, succeeded))
        }
    }

    private fun requireMemory(warm: Boolean,imageCount:Int) {
            val manager=context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memory=android.app.ActivityManager.MemoryInfo().also {manager.getMemoryInfo(it)}
            if(!VisualMemoryPolicy.allows(memory.totalMem,memory.availMem,memory.lowMemory,
                    manager.isLowRamDevice,BYTES,warm,imageCount,memory.threshold)) {
                val message=if(manager.isLowRamDevice || memory.totalMem<VisualMemoryPolicy.MIN_TOTAL)
                    "This device does not have enough memory for the visual model. You can still scan, review text suggestions and save cards."
                else "Not enough free memory for visual reading right now. Close other apps and retry, or review the text suggestions."
                throw VisualReadingUnavailableException(message)
            }
        }
    @OptIn(ExperimentalApi::class)
    private suspend fun prepareEngine(cpuVision:Boolean,imageCount:Int):Engine {
        currentCoroutineContext().ensureActive()
        if(warmEngine!=null && (warmCpuVision!=cpuVision || warmImageCount!=imageCount)) releaseEngine()
        try { requireMemory(warmEngine!=null,imageCount) }
        catch(e:VisualReadingUnavailableException) {releaseEngine();throw e}
        warmEngine?.let {return it}
        val engine = try {
            // This must precede Engine construction to also bound encoder allocation/signatures.
            ExperimentalFlags.visualTokenBudget=280
            ExperimentalFlags.enableSpeculativeDecoding=gpuLanguage && speculativeDecoding
            Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
            Engine(EngineConfig(modelPath = model.path, backend = if(gpuLanguage) Backend.GPU() else Backend.CPU(),
                visionBackend = if(cpuVision) Backend.CPU() else Backend.GPU(), maxNumTokens = 4096, maxNumImages = imageCount,
                cacheDir = File(context.cacheDir,"language-${if(gpuLanguage) "gpu" else "cpu"}-vision-${if(cpuVision) "cpu" else "gpu"}").apply {mkdirs()}.path))
        } catch(e: LinkageError) {
            throw VisualReadingUnavailableException("Visual reading is unavailable in this device's native runtime. You can still review text suggestions.",e)
        }
        try {
            engine.initialize()
            currentCoroutineContext().ensureActive()
            requireMemory(true,imageCount)
            warmEngine=engine;warmCpuVision=cpuVision;warmImageCount=imageCount
            return engine
        } catch(e:LinkageError) {
            runCatching {engine.close()}
            throw VisualReadingUnavailableException("Visual reading is unavailable in this device's native runtime. You can still review text suggestions.",e)
        } catch(e:Exception) {
            runCatching {engine.close()}
            throw e
        }
    }
    @OptIn(ExperimentalApi::class)
    private suspend fun infer(front: File, back: File?, frontText: String, backText: String, localHints: String, cpuVision: Boolean, ocrEvidence: OcrEvidence): VisualProposal {
        val imageCount=if(back==null) 1 else 2
        val started=android.os.SystemClock.elapsedRealtime()
        fun stage(name:String) {
            if(BuildConfig.DEBUG) android.util.Log.d("VisiDockVisualRuntime",
                "language=${if(gpuLanguage) "gpu" else "cpu"} vision=${if(cpuVision) "cpu" else "gpu"} stage=$name elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}")
        }
        val reused=warmEngine!=null && warmCpuVision==cpuVision && warmImageCount==imageCount
        stage("initializing")
        val engine=prepareEngine(cpuVision,imageCount)
        try {
            val proposal=run {
            stage(if(reused) "reused" else "initialized")
            // Initialization can consume the reserve that was available at admission.
            // Check again before native image encoding/prefill, where the observed kill occurred.
            requireMemory(true,imageCount)
            currentCoroutineContext().ensureActive()
            val promptEvidence=VisualPromptEvidence.build(frontText,backText,ocrEvidence)
            val contents = mutableListOf<Content>(Content.Text("Front of card:"), Content.ImageFile(front.path))
            back?.let { contents += Content.Text("Back of the SAME card:"); contents += Content.ImageFile(it.path) }
            contents += Content.Text(promptEvidence.text)
            // Never slice a learned suggestion through a JSON string/object. Oversized hints
            // are optional; the current photograph and complete readable OCR take priority.
            if(localHints.isNotBlank() && localHints.length<=600) contents += Content.Text(localHints)
            // Grammar-constrained generation regressed actual field content in controlled
            // image+OCR probes. Let the model produce JSON normally; the bounded parser still
            // validates syntax, types, channels and ownership before values reach the form.
            suspend fun read(message: Contents): VisualProposal {
                return engine.createConversation(ConversationConfig(systemInstruction = Contents.of(VisualExtraction.instruction),
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                    maxOutputToken = 1400, thinkingConfig = ThinkingConfig(enableThinking = false))).use { conversation ->
                    val result = StringBuilder()
                    var completeJson:String?=null
                    var firstResponse=true
                    try {
                        conversation.sendMessageAsync(message).takeWhile { chunk ->
                            currentCoroutineContext().ensureActive()
                            if(firstResponse) {stage("first_response");firstResponse=false}
                            result.append(chunk.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text })
                            check(result.length <= 32_000) { "The visual result was too long." }
                            completeJson=VisualExtraction.completedJson(result.toString())
                            completeJson==null
                        }.collect {}
                    } finally {
                        stage("cancel_requested")
                        runCatching { conversation.cancelProcess() }
                        stage("cancel_returned")
                    }
                    currentCoroutineContext().ensureActive()
                    VisualExtraction.parse(completeJson ?: result.toString(), frontText, backText, ocrEvidence)
                }
            }
                val first = read(Contents.of(contents))
                if(BuildConfig.DEMO) evaluationObserver?.invoke("initial",first)
                stage("json_complete")
                val repairPrompt = SourceAssignments.repairPrompt(first)
                if (repairPrompt == null) first else {
                    // One fresh-context repair reuses the images and OCR, without accumulating a failed
                    // response in the 4096-token conversation window. Weights remain warm.
                    // Never let a repair failure discard the usable first proposal or mask cancellation.
                    stage("assignment_review")
                    val repairStarted = android.os.SystemClock.elapsedRealtime()
                    var repaired = false
                    try { SourceAssignments.preferRepair(first, read(Contents.of(contents + Content.Text(repairPrompt)))).also { repaired = true } }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) {
                        currentCoroutineContext().ensureActive()
                        first.copy(warnings = (first.warnings + "Automatic assignment review could not finish. Check the highlighted fields.").distinct())
                    } finally {
                        RecognitionDiagnostics.record(context, RecognitionTiming(RecognitionStage.AssignmentReview,
                            android.os.SystemClock.elapsedRealtime()-repairStarted, repaired))
                    }
                }
            }
            stage("conversation_closed")
            VisualExtraction.requireUsable(proposal)
            stage("completed")
            if(BuildConfig.DEMO) evaluationObserver?.invoke("completed",proposal)
            // Keep weights warm for a short capture batch; conversations always close above.
            // Idle expiry frees memory without retaining a user's inference context.
            scheduleIdleRelease()
            return proposal
        } catch(e: LinkageError) {
            if(warmEngine===engine) releaseEngine() else runCatching {engine.close()}
            throw VisualReadingUnavailableException("Visual reading is unavailable in this device's native runtime. You can still review text suggestions.",e)
        } catch(e: Exception) {
            if(warmEngine===engine) releaseEngine() else runCatching {engine.close()}
            throw e
        }
    }
}
