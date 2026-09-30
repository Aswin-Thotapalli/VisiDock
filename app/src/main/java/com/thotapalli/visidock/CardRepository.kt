package com.thotapalli.visidock

import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

data class Session(val uid: String, val email: String, val verified: Boolean, val displayName: String = "")
interface CardRepository {
    fun session(): Session?
    fun observe(): Flow<List<Card>>
    suspend fun signIn(email: String, password: String, register: Boolean)
    suspend fun updateDisplayName(name: String)
    suspend fun resetPassword(email: String)
    suspend fun verifyEmail()
    suspend fun refreshSession()
    fun signOut()
    suspend fun save(card: Card, files: ScanFiles?, backFiles: ScanFiles? = null): Card
    suspend fun delete(card: Card)
    suspend fun deleteAccount(password: String)
    suspend fun photo(card: Card, back: Boolean = false): ByteArray?
    suspend fun retryCleanup()
}

fun Card.record(status: String = "ready") = mapOf(
    "name" to name.trim(), "role" to role.trim(), "company" to company.trim(), "phone" to contactPhones.firstOrNull()?.number.orEmpty(),
    "phones" to contactPhones.map { mapOf("number" to it.number.trim(), "label" to it.label.trim()) },
    "email" to email.trim(), "address" to address.trim(), "website" to website.trim(), "notes" to notes,
    "rawText" to rawText, "imagePath" to imagePath, "originalPath" to originalPath,
    "favorite" to favorite, "createdAt" to createdAt, "status" to status,
    "backImagePath" to backImagePath, "backOriginalPath" to backOriginalPath, "backRawText" to backRawText, "sourceScanId" to sourceScanId
)

fun cardFrom(id: String, data: Map<String, Any?>): Card {
    fun str(key: String) = data[key] as? String ?: ""
    return Card(id, str("name"), str("role"), str("company"), str("phone"), str("email"), str("address"),
        str("website"), str("notes"), str("rawText"), str("imagePath"), str("originalPath"),
        data["favorite"] as? Boolean ?: false, (data["createdAt"] as? Number)?.toLong() ?: 0, str("backImagePath"), str("backOriginalPath"), str("backRawText"), str("sourceScanId"),
        phones = phoneNumbersFrom(data["phones"]))
}

/** Firestore returns lists/maps; SavedState JSON returns JSONArray/JSONObject. */
private fun phoneNumbersFrom(value: Any?): List<PhoneNumber> {
    val entries: List<*> = when (value) {
        is List<*> -> value
        is JSONArray -> (0 until value.length()).map { value.opt(it) }
        else -> return emptyList()
    }
    return entries.mapNotNull { item ->
        val number = when (item) { is Map<*, *> -> item["number"] as? String; is JSONObject -> item.opt("number") as? String; else -> null }
        val label = when (item) { is Map<*, *> -> item["label"] as? String; is JSONObject -> item.opt("label") as? String; else -> null }
        number?.trim()?.takeIf { it.isNotBlank() }?.let { PhoneNumber(number = it, label = label.orEmpty().trim()) }
    }
}

class CloudRepository : CardRepository {
    companion object {
        private val database by lazy {
            FirebaseFirestore.getInstance().apply {
                firestoreSettings = FirebaseFirestoreSettings.Builder().setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
            }
        }
    }
    private val auth = FirebaseAuth.getInstance()
    private val db = database
    private val images = CardImageClient(auth)
    override fun session() = auth.currentUser?.let { Session(it.uid, it.email.orEmpty(), it.isEmailVerified, it.displayName.orEmpty()) }
    private fun collection(uid: String = checkNotNull(session()).uid) = db.collection("users").document(uid).collection("cards")
    private fun requireSession(uid: String) { check(session()?.uid == uid) { "Your session changed. Sign in again." } }
    override fun observe(): Flow<List<Card>> = callbackFlow {
        val listener: ListenerRegistration = collection().addSnapshotListener { snapshot, error ->
            if (error != null) close(error)
            else trySend(snapshot?.documents.orEmpty().filter { it.getString("status") == "ready" || it.getString("status") == null }
                .map { cardFrom(it.id, it.data.orEmpty()) })
        }
        awaitClose { listener.remove() }
    }
    override suspend fun signIn(email: String, password: String, register: Boolean) {
        if (register) auth.createUserWithEmailAndPassword(email.trim(), password).await()
        else auth.signInWithEmailAndPassword(email.trim(), password).await()
    }
    override suspend fun updateDisplayName(name: String) {
        require(name.trim().length in 1..100)
        checkNotNull(auth.currentUser).updateProfile(com.google.firebase.auth.UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build()).await()
    }
    override suspend fun resetPassword(email: String) { auth.sendPasswordResetEmail(email.trim()).await() }
    override suspend fun verifyEmail() {
        try {
            val user = checkNotNull(auth.currentUser) { "Sign in again before requesting a verification email." }
            user.reload().await()
            requireSession(user.uid)
            if (user.isEmailVerified) return
            // Refresh a stale ID token before asking Firebase to send another link.
            user.getIdToken(true).await()
            requireSession(user.uid)
            user.sendEmailVerification().await()
        } catch (e: CancellationException) { throw e }
        catch (e: com.google.firebase.FirebaseTooManyRequestsException) {
            throw IllegalStateException("Firebase is temporarily limiting verification emails. Wait a few minutes, check your inbox and spam folder, then try again.", e)
        } catch (e: com.google.firebase.FirebaseNetworkException) {
            throw IllegalStateException("Could not reach Firebase. Check your connection and try sending the email again.", e)
        } catch (e: com.google.firebase.auth.FirebaseAuthException) {
            val message = when (e.errorCode) {
                "ERROR_USER_TOKEN_EXPIRED", "ERROR_INVALID_USER_TOKEN", "ERROR_USER_DISABLED", "ERROR_USER_NOT_FOUND" -> "Your sign-in session needs to be refreshed. Sign out and sign in again, then request a new verification email."
                "ERROR_TOO_MANY_REQUESTS" -> "Firebase is temporarily limiting verification emails. Wait a few minutes and try again."
                else -> "Firebase could not send the verification email (${e.errorCode}). Please try again shortly."
            }
            throw IllegalStateException(message, e)
        }
    }
    override suspend fun refreshSession() { auth.currentUser?.reload()?.await() }
    override fun signOut() = auth.signOut()
    override suspend fun save(card: Card, files: ScanFiles?, backFiles: ScanFiles?): Card {
        require(CardLogic.validate(card) == null) { CardLogic.validate(card).orEmpty() }
        val uid = checkNotNull(session()).uid
        val doc = collection(uid).document(card.id)
        // Lifecycle decisions must use acknowledged server state, never optimistic cache writes.
        val previous = doc.get(Source.SERVER).await()
        if (previous.getString("status") == "deleting") delete(uid, cardFrom(previous.id, previous.data.orEmpty()))
        if (files == null && backFiles == null) {
            require(!previous.exists() || previous.getString("status") == "ready") { "This card is still syncing. Retry its scan before editing it." }
            doc.set(card.record()).await()
            return card
        }
        images.validateConfiguration()
        val sameScan = card.sourceScanId.isNotBlank() && previous.getString("sourceScanId") == card.sourceScanId && previous.getLong("createdAt") == card.createdAt
        // The final ready write may have succeeded even when its response was lost.
        if (previous.getString("status") == "ready" && sameScan) return cardFrom(previous.id, previous.data.orEmpty())
        require(!previous.exists() || previous.getString("status") == "deleting" || (previous.getString("status") == "uploading" && sameScan)) { "Retake images as a new card to keep the saved original safe." }
        requireNotNull(files) { "Capture the front before adding the back." }
        require(files.original.isFile && files.preview.isFile) { "The scanned image is no longer available. Scan the card again." }
        val updated = card.copy(imagePath = "users/$uid/cards/${card.id}/preview.jpg", originalPath = "users/$uid/cards/${card.id}/original",
            backImagePath=if(backFiles!=null) "users/$uid/cards/${card.id}/back-preview.jpg" else "",
            backOriginalPath=if(backFiles!=null) "users/$uid/cards/${card.id}/back-original" else "")
        if (previous.getString("status") == "uploading") {
            require(previous.getString("imagePath") == updated.imagePath && previous.getString("originalPath") == updated.originalPath &&
                previous.getString("backImagePath").orEmpty() == updated.backImagePath && previous.getString("backOriginalPath").orEmpty() == updated.backOriginalPath) { "Keep both sides of this scan together when retrying." }
        }
        // The manifest exists first so even a killed upload remains discoverable for cleanup.
        doc.set(updated.record("uploading") + ("uploadStartedAt" to System.currentTimeMillis())).await()
        try {
            images.put(uid, card.id, "original", files.original, files.mime)
            images.put(uid, card.id, "preview.jpg", files.preview, "image/jpeg")
            if(backFiles!=null) {
                images.put(uid,card.id,"back-original",backFiles.original,backFiles.mime)
                images.put(uid,card.id,"back-preview.jpg",backFiles.preview,"image/jpeg")
            }
            currentCoroutineContext().ensureActive()
            requireSession(uid)
            doc.update("status", "ready").await()
        } catch (e: CancellationException) {
            // Keep the upload manifest for cleanup after process death or cancellation.
            throw e
        } catch (e: Exception) {
            // Do not delete on an ambiguous network failure: ready may already be committed.
            // An unfinished upload remains resumable; stale uploads use durable cleanup later.
            try {
                requireSession(uid)
                val confirmed = doc.get(Source.SERVER).await()
                if (confirmed.getString("status") == "ready" && confirmed.getString("sourceScanId") == updated.sourceScanId && confirmed.getLong("createdAt") == updated.createdAt) {
                    return cardFrom(confirmed.id, confirmed.data.orEmpty())
                }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            throw e
        }
        return updated
    }
    override suspend fun delete(card: Card) = delete(checkNotNull(session()).uid, card)
    private suspend fun delete(uid: String, card: Card) {
        requireSession(uid)
        if (card.originalPath.isNotBlank() || card.imagePath.isNotBlank()) images.validateConfiguration()
        val doc = collection(uid).document(card.id)
        // Keep the manifest until both objects are confirmed gone. Retrying is idempotent.
        val snapshot = doc.get(Source.SERVER).await()
        if (!snapshot.exists()) return
        doc.update("status", "deleting").await()
        val stored = cardFrom(snapshot.id, snapshot.data.orEmpty())
        if (stored.originalPath.isNotBlank()) images.delete(uid, card.id, "original")
        if (stored.imagePath.isNotBlank()) images.delete(uid, card.id, "preview.jpg")
        if(stored.backOriginalPath.isNotBlank()) images.delete(uid,card.id,"back-original")
        if(stored.backImagePath.isNotBlank()) images.delete(uid,card.id,"back-preview.jpg")
        requireSession(uid)
        doc.delete().await()
    }
    override suspend fun retryCleanup() {
        val uid = checkNotNull(session()).uid
        val docs = collection(uid).get(Source.SERVER).await().documents
        var firstFailure: Exception? = null
        for (doc in docs) {
            currentCoroutineContext().ensureActive()
            requireSession(uid)
            val staleUpload = doc.getString("status") == "uploading" && (doc.getLong("uploadStartedAt") ?: doc.getLong("createdAt") ?: 0) < System.currentTimeMillis() - 86_400_000
            if (doc.getString("status") == "deleting" || staleUpload) {
                try { delete(uid, cardFrom(doc.id, doc.data.orEmpty())) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (firstFailure == null) firstFailure = e }
            }
        }
        firstFailure?.let { throw it }
    }
    override suspend fun photo(card: Card, back: Boolean): ByteArray? = if ((if(back) card.backImagePath else card.imagePath).isBlank()) null
        else images.get(checkNotNull(session()).uid, card.id, if(back) "back-preview.jpg" else "preview.jpg")
    override suspend fun deleteAccount(password: String) {
        val user = checkNotNull(auth.currentUser)
        val uid = user.uid
        user.reauthenticate(EmailAuthProvider.getCredential(checkNotNull(user.email), password)).await()
        requireSession(uid)
        for (doc in collection(uid).get().await().documents) delete(uid, cardFrom(doc.id, doc.data.orEmpty()))
        requireSession(uid)
        user.delete().await()
    }
}

/** Clearly labelled, in-memory sandbox. Never silently substitutes for a cloud account. */
class DemoRepository : CardRepository {
    private val cards = MutableStateFlow(listOf(
        Card(id="sample-1", name="Ananya Rao", role="Brand strategist", company="Northline Studio", email="ananya@example.com", address="Bengaluru", notes="Met at the design conference. Talk about the new identity project.", favorite=true),
        Card(id="sample-2", name="Rohan Mehta", role="Blockchain consultant", company="Form Labs", email="rohan@example.com", notes="Met at the technology conference in Bengaluru.", createdAt=System.currentTimeMillis()-86_400_000),
        Card(id="sample-3", name="Maya Thomas", role="Operations director", company="Arc Manufacturing", email="maya@example.com", address="Bengaluru", notes="Introduced by a colleague. Follow up about the factory visit.", createdAt=System.currentTimeMillis()-172_800_000)
    ))
    private val photos = mutableMapOf<String, ByteArray>()
    override fun session() = Session("demo", "Demo collection", true)
    override fun observe(): Flow<List<Card>> = cards
    override suspend fun signIn(email: String, password: String, register: Boolean) = Unit
    override suspend fun updateDisplayName(name: String) = Unit
    override suspend fun resetPassword(email: String) = Unit
    override suspend fun verifyEmail() = Unit
    override suspend fun refreshSession() = Unit
    override fun signOut() = Unit
    override suspend fun save(card: Card, files: ScanFiles?, backFiles: ScanFiles?): Card {
        files?.let { photos[card.id] = it.preview.readBytes() }
        backFiles?.let { photos[card.id+"-back"] = it.preview.readBytes() }
        val stored=card.copy(imagePath=if(files!=null) "demo-front" else card.imagePath,backImagePath=if(backFiles!=null) "demo-back" else card.backImagePath)
        cards.value = cards.value.filterNot { it.id == card.id } + stored
        return stored
    }
    override suspend fun delete(card: Card) { cards.value = cards.value.filterNot { it.id == card.id }; photos.remove(card.id); photos.remove(card.id+"-back") }
    override suspend fun deleteAccount(password: String) { cards.value = emptyList(); photos.clear() }
    override suspend fun photo(card: Card, back: Boolean) = photos[card.id+if(back) "-back" else ""]
    override suspend fun retryCleanup() = Unit
}
