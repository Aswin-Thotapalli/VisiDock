package com.thotapalli.visidock

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update as atomicUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID

data class VaultState(
    val session: Session? = null, val cards: List<Card> = emptyList(), val loading: Boolean = true,
    val busy: String? = null, val error: String? = null, val message: String? = null,
    val draft: Card? = null, val selectedId: String? = null, val draftPreview: String? = null,
    val draftBackPreview: String? = null, val configured: Boolean = true,
    val remainingPeople: Int = 0, val extractionWarnings: List<String> = emptyList(), val visualModelReady: Boolean = false,
    val learningEnabled: Boolean = true, val learnedLabels: List<LearnedLabel> = emptyList(),
    val savedEvent: Long = 0, val savedName: String = "",
    val cropPath:String?=null,val cropBack:Boolean=false,
    val captureReview:Boolean=false, val scanSide:Int?=null, val scanPreview:String?=null,
    val scanRegions:List<OcrRegion> = emptyList(), val scanAnalysisReady:Boolean=false,
    val correctionHistory:List<CorrectionActivity> = emptyList()
)

class VaultViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val repository = runCatching { if (BuildConfig.DEMO) DemoRepository() else CloudRepository() }.getOrNull()
    private val mutable = MutableStateFlow(VaultState(session = repository?.session(), configured = repository != null))
    val state = mutable.asStateFlow()
    private var listener: Job? = null
    private var cleanup: Job? = null
    private var historyLoad:Job?=null
    private val searchEngine = SemanticSearch(application)
    private val visualModel = VisualModel(application)
    private var operationJob: Job? = null
    private var scanPresentation: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    fun finishScanPresentation() {scanPresentation?.complete(Unit)}
    private var pendingPeople = emptyList<Card>()
    suspend fun search(cards: List<Card>, query: String) = searchEngine.search(cards, query)
    private fun update(block: (VaultState) -> VaultState) { mutable.atomicUpdate(block) }
    init {
        if (saved.get<String>("draftOwner") != repository?.session()?.uid) {
            clearFiles(); saved["draft"] = null; saved["selected"] = null
        }
        saved.get<String>("draft")?.let { json ->
            runCatching {
                val value = JSONObject(json)
                val fields = value.keys().asSequence().associateWith { value.get(it) }
                update { it.copy(draft=cardFrom(value.getString("id"), fields), draftPreview=saved["preview"], draftBackPreview=saved["backPreview"]) }
            }
        }
        saved.get<String>("pendingPeople")?.let { raw ->
            runCatching { val array=JSONArray(raw); pendingPeople=(0 until array.length()).map { i ->
                val value=array.getJSONObject(i); cardFrom(value.getString("id"),value.keys().asSequence().associateWith {value.get(it)})
            } }
        }
        update { it.copy(draftPreview=saved["preview"],draftBackPreview=saved["backPreview"],captureReview=saved.get<Boolean>("captureReview") ?: false,cropPath=saved["cropSource"],cropBack=saved.get<Boolean>("cropBack") ?: false,selectedId=saved["selected"], remainingPeople=pendingPeople.size, visualModelReady=visualModel.installed(), extractionWarnings=saved.get<ArrayList<String>>("extractionWarnings").orEmpty()) }
        viewModelScope.launch(Dispatchers.IO) { ImagePipeline.cleanOld(application, setOfNotNull(saved["original"], saved["preview"], saved["camera"], saved["backOriginal"], saved["backPreview"])) }
        viewModelScope.launch(Dispatchers.IO) {
            val keep=saved.get<String>("cropSource")
            val cutoff=System.currentTimeMillis()-24*60*60*1000
            ImageCropper.directory(application).listFiles().orEmpty().filter {it.path!=keep && it.lastModified()<cutoff}.forEach {it.delete()}
        }
        observe()
    }
    private fun observe() {
        listener?.cancel()
        cleanup?.cancel()
        historyLoad?.cancel()
        if (repository?.session() == null) { update { it.copy(loading=false, cards=emptyList()) }; return }
        learning().sessionActive(true)
        update { it.copy(loading=true, session=repository.session(),learningEnabled=learning().enabled()) }
        val historyOwner=checkNotNull(repository.session()).uid
        val memory=learning()
        historyLoad=viewModelScope.launch(Dispatchers.IO) {
            runCatching {memory.history() to memory.labels()}.onSuccess {(history,labels)->
                currentCoroutineContext().ensureActive()
                update {if(it.session?.uid==historyOwner) it.copy(correctionHistory=history,learnedLabels=labels) else it}
            }
        }
        listener = viewModelScope.launch {
            try { repository.observe().collect { cards -> update { it.copy(cards=cards, loading=false) } } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { update { it.copy(loading=false, error="Could not load your collection. Check your connection and retry.") } }
        }
        cleanup = viewModelScope.launch {
            try { repository.retryCleanup() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { update { it.copy(error="Some image cleanup is pending. Use Retry sync in Settings when online.") } }
        }
    }
    private fun operation(label: String, timeoutMillis: Long = 60_000, block: suspend () -> Unit) {
        if (state.value.busy != null) return
        update { it.copy(busy=label, error=null) }
        operationJob = viewModelScope.launch {
            try { kotlinx.coroutines.withTimeout(timeoutMillis) { block() } }
            catch (e: kotlinx.coroutines.TimeoutCancellationException) { update { it.copy(error="This took too long. Your draft is still available. Retry the operation, or review the card manually. Pending cloud cleanup can be retried in Settings.") } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { update { it.copy(error=e.localizedMessage ?: "Something went wrong. Please try again.") } }
            finally { scanPresentation?.cancel();scanPresentation=null;update { it.copy(busy=null,scanSide=null,scanPreview=null) } }
        }
    }
    fun downloadVisualModel() = operation("Downloading visual reading…", 3_600_000) {
        visualModel.download { percent -> update { it.copy(busy="Downloading visual reading: $percent% of 2.6 GB") } }
        update { it.copy(visualModelReady=true, message="Visual reading is ready. Card analysis stays on this device.") }
    }
    private fun scanRegions()=OcrRegions.decode(saved["frontRegions"])+OcrRegions.decode(saved["backRegions"])
    private fun scanEvidence()=OcrEvidence.combine(OcrEvidence.decode(saved["frontEvidence"]),OcrEvidence.decode(saved["backEvidence"]))
    private fun learning()=CorrectionMemory(getApplication(),checkNotNull(repository?.session()).uid)
    fun setLearning(enabled:Boolean) { if(state.value.busy!=null) return; learning().setEnabled(enabled); update {it.copy(learningEnabled=enabled)} }
    fun inspectLearning() = operation("Opening learning history…") {
        val labels=withContext(Dispatchers.IO) {learning().labels()}
        val history=withContext(Dispatchers.IO) {learning().history()}
        update {it.copy(learnedLabels=labels,correctionHistory=history,learningEnabled=learning().enabled())}
    }
    fun clearLearning() = operation("Clearing learning history…") {
        historyLoad?.cancel();historyLoad?.join()
        withContext(Dispatchers.IO) {learning().clear()}
        update {it.copy(learnedLabels=emptyList(),correctionHistory=emptyList(),message="Learning history cleared from this device")}
    }
    fun cancelOperation() { operationJob?.cancel() }
    fun skipPerson() {
        if(state.value.busy != null || pendingPeople.isEmpty()) return
        val next=pendingPeople.first(); setPending(pendingPeople.drop(1)); setDraft(next)
    }
    private fun setPending(people: List<Card>) {
        pendingPeople=people
        saved["pendingPeople"]=JSONArray(people.map { JSONObject(it.record() + ("id" to it.id)) }).toString()
        update { it.copy(remainingPeople=people.size) }
    }
    private fun setWarnings(warnings: List<String>) {
        saved["extractionWarnings"]=ArrayList(warnings)
        update { it.copy(extractionWarnings=warnings) }
    }
    fun report(message: String) = update { it.copy(error=message) }
    fun clearError() = update { it.copy(error=null) }
    fun clearMessage() = update { it.copy(message=null) }
    fun retry() { clearError(); observe() }
    fun signIn(email: String, password: String, register: Boolean, displayName: String = "") = operation("Connecting securely…") {
        require(email.isNotBlank() && password.length >= (if (register) 8 else 6)) { "Enter your email and a valid password. New passwords need at least 8 characters." }
        checkNotNull(repository).signIn(email, password, register)
        if(register && displayName.isNotBlank()) checkNotNull(repository).updateDisplayName(displayName)
        observe()
        if (register) sendVerification()
    }
    fun updateDisplayName(name: String) = operation("Updating your profile…") {
        checkNotNull(repository).updateDisplayName(name)
        update { it.copy(session=repository.session(), message="Profile updated") }
    }
    fun resetPassword(email: String) = operation("Sending reset link…") {
        require(email.contains('@')) { "Enter your email address first." }
        checkNotNull(repository).resetPassword(email)
        update { it.copy(message="If an account exists for that email, you’ll receive a reset link.") }
    }
    fun verifyEmail() = operation("Sending verification…") {
        sendVerification()
    }
    private suspend fun sendVerification() {
        val owner=checkNotNull(repository?.session()).uid
        val now=System.currentTimeMillis()
        val previous=if(saved.get<String>("verificationOwner")==owner) saved.get<Long>("verificationSentAt") ?: 0L else 0L
        val remaining=(60_000L-(now-previous)).coerceIn(0L,60_000L)
        require(remaining==0L) { "A verification email was just sent. Check your inbox and spam folder, or retry in ${(remaining+999)/1000} seconds." }
        try { checkNotNull(repository).verifyEmail() }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) {
            if(e is com.google.firebase.FirebaseTooManyRequestsException || e.cause is com.google.firebase.FirebaseTooManyRequestsException) {
                saved["verificationOwner"]=owner; saved["verificationSentAt"]=now
            }
            throw e
        }
        if(repository?.session()?.verified==true) {
            update { it.copy(session=repository?.session(),message="Your email is already verified.") }
            return
        }
        saved["verificationOwner"]=owner; saved["verificationSentAt"]=System.currentTimeMillis()
        update { it.copy(session=repository?.session(),message="Verification link sent. Check your inbox and spam folder.") }
    }
    fun refreshSession() = operation("Checking account…") { repository?.refreshSession(); update { it.copy(session=repository?.session()) } }
    fun signOut() {
        if (state.value.busy != null) return
        repository?.session()?.let { learning().sessionActive(false) }
        listener?.cancel(); cleanup?.cancel(); historyLoad?.cancel(); discard(); repository?.signOut()
        visualModel.release()
        viewModelScope.launch { searchEngine.clear() }
        saved["selected"] = null
        update { VaultState(session=repository?.session(), loading=false, configured=repository != null) }
    }
    fun select(card: Card?) { saved["selected"] = card?.id; update { it.copy(selectedId=card?.id) } }
    fun edit(card: Card) {
        if (state.value.busy != null) return
        clearFiles()
        saved["scanProposals"]=JSONObject(mapOf(card.id to JSONObject(card.record()+("id" to card.id)))).toString()
        setDraft(card); update { it.copy(draftPreview=null, draftBackPreview=null) }
    }
    fun newCard() { if (state.value.busy != null) return; clearFiles(); setDraft(Card(id=UUID.randomUUID().toString())); update { it.copy(draftPreview=null, draftBackPreview=null) } }
    fun beginCapture() = newCard()
    fun cancelCapture() = discard()
    fun prepareSideEdit(card:Card,back:Boolean) {
        if(state.value.busy!=null) return
        if(state.value.draft?.id!=card.id) edit(card)
        saved["editingImages"]=true
        saved["imageEditBack"]=back
    }
    fun recrop(card:Card,back:Boolean=false) {
        if(state.value.busy!=null) return
        prepareSideEdit(card,back)
        operation("Opening original card…") {
            val bytes=withContext(Dispatchers.IO) {repository?.original(card,back)} ?: error("This side has no saved image yet.")
            val source=withContext(Dispatchers.IO) {
                File(ImageCropper.directory(getApplication()),"${UUID.randomUUID()}.source").apply {writeBytes(bytes)}
            }
            saved["cropSource"]=source.path;saved["cropBack"]=back
            update {it.copy(cropPath=source.path,cropBack=back)}
        }
    }
    fun changeDraft(card: Card) {
        if(state.value.busy!=null || state.value.draft?.id!=card.id) return
        setDraft(card)
    }
    private fun setDraft(card: Card) {
        saved["draftOwner"] = repository?.session()?.uid
        saved["draft"] = JSONObject(card.record() + ("id" to card.id)).toString()
        update { it.copy(draft=card) }
    }
    fun discard() {
        if (state.value.busy != null) return
        clearFiles()
        saved["draft"] = null
        update { it.copy(draft=null, draftPreview=null, draftBackPreview=null) }
    }
    private fun clearFiles() {
        listOf("original", "preview", "camera", "backOriginal", "backPreview", "cropSource").forEach { key -> saved.get<String>(key)?.let { File(it).delete() }; saved[key] = null }
        saved["cropBack"] = null
        saved["captureReview"]=null;saved["editingImages"]=null;saved["imageEditBack"]=null;saved["imageRevision"]=null;saved["pendingImageRevision"]=null
        update {it.copy(cropPath=null,cropBack=false,captureReview=false,scanSide=null,scanPreview=null,scanRegions=emptyList(),scanAnalysisReady=false)}
        saved["mime"] = null
        saved["backMime"] = null
        saved["draftOwner"] = null
        setPending(emptyList()); setWarnings(emptyList()); saved["scanProposals"]=null; saved["frontRegions"]=null; saved["backRegions"]=null; saved["sourceScanId"]=null
        saved["frontEvidence"]=null;saved["backEvidence"]=null
    }
    fun cameraFile(): File = File(getApplication<Application>().cacheDir, "camera/${UUID.randomUUID()}.jpg").apply {
        saved.get<String>("camera")?.let { File(it).delete() }
        parentFile?.mkdirs(); saved["camera"] = path; saved["draftOwner"] = repository?.session()?.uid
    }
    fun cameraResult(success: Boolean, back: Boolean = false) {
        val path = saved.get<String>("camera") ?: return
        if (success) stageCrop(Uri.fromFile(File(path)),back) else { File(path).delete(); saved["camera"] = null }
    }
    fun stageCrop(uri:Uri,back:Boolean=false)=operation("Preparing photo…") {
        require(!back || saved.get<String>("preview")!=null || state.value.draft?.imagePath?.isNotBlank()==true) {"Capture the front before adding the back."}
        val source=try {withContext(Dispatchers.IO) {ImageCropper.stage(getApplication(),uri)}}
        finally {saved.get<String>("camera")?.let {File(it).delete()};saved["camera"]=null}
        saved.get<String>("cropSource")?.let {File(it).delete()}
        saved["cropSource"]=source.path;saved["cropBack"]=back;saved["draftOwner"]=repository?.session()?.uid
        update {it.copy(cropPath=source.path,cropBack=back)}
    }
    fun cancelCrop() {
        if(state.value.busy!=null) return
        saved.get<String>("cropSource")?.let {File(it).delete()};saved["cropSource"]=null;saved["cropBack"]=null
        update {it.copy(cropPath=null,cropBack=false,error=null)}
    }
    fun useCrop(points:List<Float>)=operation("Cropping card…",180_000) {
        require(saved.get<String>("pendingImageRevision")==null) {"Finish retrying the pending save before changing its photos. Cancel this crop and save the card again."}
        val raw=File(checkNotNull(saved.get<String>("cropSource")))
        val back=saved.get<Boolean>("cropBack") ?: false
        val cropped=withContext(Dispatchers.IO) {ImageCropper.crop(getApplication(),raw,points)}
        // Keep the crop surface and retry source until the next screen's images actually exist.
        update {it.copy(busy="Preparing card…")}
        val files=try {ImagePipeline.prepare(getApplication(),Uri.fromFile(cropped))} finally {cropped.delete()}
        storeSide(files,back)
        val editing=saved.get<Boolean>("editingImages")==true || state.value.cards.any {it.id==state.value.draft?.id}
        if(editing) {
            val revision=UUID.randomUUID().toString();saved["imageRevision"]=revision
            setDraft(checkNotNull(state.value.draft).copy(sourceScanId=revision))
        }
        saved["captureReview"]=!editing
        raw.delete();saved["cropSource"]=null;saved["cropBack"]=null
        update {it.copy(cropPath=null,cropBack=false,captureReview=!editing)}
    }
    private fun storeSide(files:ScanFiles,back:Boolean) {
        val original=if(back) "backOriginal" else "original"
        val preview=if(back) "backPreview" else "preview"
        saved.get<String>(original)?.let {File(it).delete()};saved.get<String>(preview)?.let {File(it).delete()}
        saved[original]=files.original.path;saved[preview]=files.preview.path;saved[if(back) "backMime" else "mime"]=files.mime
        saved[if(back) "backRegions" else "frontRegions"]=OcrRegions.encode(files.regions.map {it.copy(side=if(back) 1 else 0)})
        saved[if(back) "backEvidence" else "frontEvidence"]=files.evidence.encode()
        update {it.copy(draftPreview=if(back) it.draftPreview else files.preview.path,draftBackPreview=if(back) files.preview.path else it.draftBackPreview)}
    }
    private fun preparedSide(back:Boolean):ScanFiles? {
        val original=saved.get<String>(if(back) "backOriginal" else "original") ?: return null
        return ScanFiles(File(original),File(checkNotNull(saved.get<String>(if(back) "backPreview" else "preview"))),checkNotNull(saved.get<String>(if(back) "backMime" else "mime")),"")
    }
    fun readCapturedSides(waitForPresentation:Boolean=false) = operation("Reading front…",180_000) {
        val front=checkNotNull(preparedSide(false)) {"Add the front of the card first."}
        val back=preparedSide(true)
        saved["captureReview"]=true
        scanPresentation=if(waitForPresentation) kotlinx.coroutines.CompletableDeferred() else null
        update {it.copy(scanSide=0,scanPreview=front.preview.path,scanRegions=emptyList(),scanAnalysisReady=false)}
        val frontRead=ImagePipeline.read(getApplication(),front)
        saved["frontRegions"]=OcrRegions.encode(frontRead.regions)
        saved["frontEvidence"]=frontRead.evidence.encode()
        update {it.copy(scanRegions=frontRead.regions)}
        val backRead=back?.let {
            update {state->state.copy(busy="Reading back…",scanSide=1,scanPreview=it.preview.path)}
            ImagePipeline.read(getApplication(),it).also {result->
                val regions=result.regions.map {region->region.copy(side=1)}
                saved["backRegions"]=OcrRegions.encode(regions)
                saved["backEvidence"]=result.evidence.encode()
                update {state->state.copy(scanRegions=frontRead.regions+regions)}
            }
        }
        update {it.copy(busy="Understanding your card…",scanSide=2,scanPreview=back?.preview?.path ?: front.preview.path)}
        analyzePrepared(frontRead.text,backRead?.text.orEmpty())
        update {it.copy(scanAnalysisReady=true,busy="Details ready for review")}
        // Computation runs independently of the optical presentation. Only a fast
        // result waits for the already-running handoff; backgrounded UI is bounded.
        scanPresentation?.let {kotlinx.coroutines.withTimeoutOrNull(20_000) {it.await()}}
        saved["captureReview"]=false
        update {it.copy(captureReview=false)}
    }
    fun reviewCapturedManually() {
        if(state.value.busy!=null || !state.value.captureReview) return
        val draft=state.value.draft ?: Card(id=UUID.randomUUID().toString())
        val source=saved.get<String>("sourceScanId") ?: UUID.randomUUID().toString().also {saved["sourceScanId"]=it}
        val suggestion=CardLogic.extract(draft.rawText+"\n"+draft.backRawText).copy(id=draft.id,name="",
            rawText=draft.rawText,backRawText=draft.backRawText,sourceScanId=source)
        setDraft(suggestion);setPending(emptyList());setWarnings(scanEvidence().reviewWarnings)
        saved["scanProposals"]=JSONObject(mapOf(suggestion.id to JSONObject(suggestion.record()+("id" to suggestion.id)))).toString()
        saved["captureReview"]=false
        update {it.copy(captureReview=false,error=null)}
    }
    fun scan(uri: Uri) = scanSide(uri, false)
    fun scanBack(uri: Uri) = scanSide(uri, true)
    private fun scanSide(uri: Uri, back: Boolean) = operation("Reading the card on your device…",180_000) {processScan(uri,back)}
    private suspend fun processScan(uri:Uri,back:Boolean) {
        require(!back || saved.get<String>("preview")!=null) { "Capture the front before adding the back." }
        val files = try { ImagePipeline.scan(getApplication(), uri) }
        finally { saved.get<String>("camera")?.let { File(it).delete() }; saved["camera"] = null }
        if(!back) clearFiles()
        if(back) {
            saved.get<String>("backOriginal")?.let { File(it).delete() }; saved.get<String>("backPreview")?.let { File(it).delete() }
            saved["backOriginal"]=files.original.path; saved["backPreview"]=files.preview.path; saved["backMime"]=files.mime; saved["backRegions"]=OcrRegions.encode(files.regions.map {it.copy(side=1)})
        } else { saved["original"] = files.original.path; saved["preview"] = files.preview.path; saved["mime"] = files.mime; saved["frontRegions"]=OcrRegions.encode(files.regions) }
        saved[if(back) "backEvidence" else "frontEvidence"]=files.evidence.encode()
        update { it.copy(busy="Identifying the details on your device…", draftPreview=if(back) it.draftPreview else files.preview.path) }
        val previous=state.value.draft
        val frontText=if(back) previous?.rawText.orEmpty() else files.text
        val backText=if(back) files.text else ""
        analyzePrepared(frontText,backText)
    }
    fun readPhotosAgain() = readCapturedSides(waitForPresentation=true)
    private suspend fun analyzePrepared(frontText:String,backText:String) {
        val front=File(checkNotNull(saved.get<String>("preview")))
        val reverse=saved.get<String>("backPreview")?.let(::File)
        // Persist a draft before inference so cancellation/timeouts never pair an old person with a new photo.
        setDraft(Card(id=UUID.randomUUID().toString(),rawText=frontText.take(12000),backRawText=backText.take(12000)))
        setPending(emptyList()); setWarnings(emptyList())
        update { it.copy(draftPreview=front.path,draftBackPreview=reverse?.path) }
        val result = if(visualModel.installed()) {
            try {
                val regions=scanRegions()
                val hints=withContext(Dispatchers.IO) {runCatching {learning().hints(frontText+"\n"+backText,regions)}.getOrDefault("")}
                visualModel.extract(front,reverse,frontText,backText,hints,scanEvidence())
            }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { VisualProposal(listOf(CardLogic.extract(frontText+"\n"+backText).copy(name="",rawText=frontText.take(12000),backRawText=backText.take(12000))),listOf(if(e is VisualReadingUnavailableException) e.message.orEmpty() else "Visual reading could not finish on this device. Check the photographs and enter the details, or retry.")) }
        } else VisualProposal(listOf(CardLogic.extract(frontText+"\n"+backText).copy(name="",rawText=frontText.take(12000),backRawText=backText.take(12000))),listOf("Visual reading is not installed. Download it in Settings to identify people from the photograph. These are basic text suggestions."))
        val sourceScanId=saved.get<String>("sourceScanId") ?: UUID.randomUUID().toString().also {saved["sourceScanId"]=it}
        val people=result.contacts.map { it.copy(id=UUID.randomUUID().toString(),sourceScanId=sourceScanId) }
        saved["scanProposals"]=JSONObject(people.associate {it.id to JSONObject(it.record()+("id" to it.id))}).toString()
        setPending(people.drop(1)); setWarnings((result.warnings+scanEvidence().reviewWarnings).distinct())
        setDraft(people.first())
        update { it.copy(draftPreview=front.path,draftBackPreview=reverse?.path) }
    }

    fun save() = operation("Saving your card…") {
        val card = checkNotNull(state.value.draft)
        var historyFailed=false
        CardLogic.validate(card)?.let { error(it) }
        val files = saved.get<String>("original")?.let { ScanFiles(File(it), File(checkNotNull(saved.get<String>("preview"))), checkNotNull(saved.get<String>("mime")), card.rawText) }
        require(files == null || (files.original.isFile && files.preview.isFile)) { "The scanned image is no longer available. Scan the card again." }
        val backFiles=saved.get<String>("backOriginal")?.let { ScanFiles(File(it),File(checkNotNull(saved.get<String>("backPreview"))),checkNotNull(saved.get<String>("backMime")),card.backRawText) }
        if(files!=null || backFiles!=null) saved["pendingImageRevision"]=card.sourceScanId
        val confirmed=withContext(Dispatchers.IO) { checkNotNull(repository).save(card, files, backFiles) }
        saved["pendingImageRevision"]=null
        // Use acknowledged image paths immediately; the listener can arrive a frame later.
        update {it.copy(cards=it.cards.filterNot {existing->existing.id==confirmed.id}+confirmed)}
        val baseline=saved.get<String>("scanProposals")?.let {JSONObject(it).optJSONObject(card.id)}
        if(baseline!=null) {
            val proposed=cardFrom(card.id,baseline.keys().asSequence().associateWith {baseline.get(it)})
            val regions=scanRegions()
            val recorded=withContext(Dispatchers.IO) {runCatching {learning().record(proposed,confirmed,regions)}}
            historyFailed=recorded.isFailure
            val history=withContext(Dispatchers.IO) {runCatching {learning().history()}.getOrDefault(emptyList())}
            update {it.copy(correctionHistory=history)}
            recorded.getOrNull()?.let {train ->viewModelScope.launch(Dispatchers.IO) {runCatching {train()}}}
        }
        update { it.copy(savedEvent=it.savedEvent+1,savedName=card.displayLabel) }
        saved["selected"] = card.id
        if(pendingPeople.isNotEmpty()) {
            val next=pendingPeople.first(); setPending(pendingPeople.drop(1)); setDraft(next)
            update { it.copy(selectedId=card.id,message="${card.displayLabel} saved. Review the next person from this card.") }
        } else {
            clearFiles(); saved["draft"] = null;saved["selected"] = null
            update { it.copy(draft=null, draftPreview=null, draftBackPreview=null, selectedId=null, message=if(historyFailed) "Card saved. Correction history could not be updated on this device." else "Card saved") }
        }
    }
    fun favorite(card: Card) = operation("Updating…") {
        update {it.copy(cards=it.cards.map {stored->if(stored.id==card.id) stored.copy(favorite=!card.favorite) else stored})}
        try {checkNotNull(repository).save(card.copy(favorite=!card.favorite), null)}
        catch(e:Exception) {
            update {it.copy(cards=it.cards.map {stored->if(stored.id==card.id) stored.copy(favorite=card.favorite) else stored})}
            throw e
        }
    }
    fun delete(card: Card) = operation("Removing card and images…") {
        checkNotNull(repository).delete(card); withContext(Dispatchers.IO) {runCatching {learning().forgetCard(card.id)}}; select(null); update { it.copy(message="Card deleted") }
    }
    fun deleteAccount(password: String) = operation("Deleting account and card images…") {
        val memory=learning()
        checkNotNull(repository).deleteAccount(password)
        withContext(Dispatchers.IO) {memory.clear()}
        listener?.cancel(); cleanup?.cancel(); historyLoad?.cancel(); searchEngine.clear(); clearFiles(); saved["draft"] = null; saved["selected"] = null
        update { VaultState(loading=false, message="Account deleted") }
    }
    override fun onCleared() {
        visualModel.release()
        searchEngine.close()
        super.onCleared()
    }
    suspend fun photo(card: Card, back: Boolean=false): ByteArray? = withContext(Dispatchers.IO) { repository?.photo(card,back) }
}
