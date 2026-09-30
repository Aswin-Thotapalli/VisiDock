package com.thotapalli.visidock

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Timer
import java.util.TimerTask

/** Only the Firebase ID token crosses the wire; object keys and account ownership are server-derived. */
internal class CardImageClient(
    private val auth: FirebaseAuth,
    private val baseUrl: String = BuildConfig.IMAGE_API_URL
) {
    fun validateConfiguration() { imageApiBase(baseUrl) }

    suspend fun put(uid: String, cardId: String, image: String, file: File, mime: String) {
        require(file.isFile && file.length() in 1..ImagePipeline.MAX_ORIGINAL) { "Choose an image smaller than 20 MB." }
        require(!image.endsWith("preview.jpg") || (file.length() <= MAX_DOWNLOAD && mime == "image/jpeg")) { "The preview must be a JPEG image smaller than 6 MB." }
        require(mime in setOf("image/jpeg", "image/png", "image/webp")) { "Choose a JPEG, PNG or WebP image." }
        request(uid, cardId, image, "PUT", file, mime)
    }

    suspend fun delete(uid: String, cardId: String, image: String) {
        request(uid, cardId, image, "DELETE")
    }

    suspend fun get(uid: String, cardId: String, image: String): ByteArray =
        checkNotNull(request(uid, cardId, image, "GET"))

    private suspend fun request(
        uid: String, cardId: String, image: String, method: String,
        file: File? = null, mime: String? = null
    ): ByteArray? = withContext(Dispatchers.IO) {
        val endpoint = imageApiEndpoint(baseUrl, cardId, image)
        val user = checkNotNull(auth.currentUser) { "Sign in to access your card images." }
        check(user.uid == uid) { "Your session changed. Sign in again." }
        val token = checkNotNull(user.getIdToken(false).await().token) { "Sign in again to access your card images." }
        check(auth.currentUser?.uid == uid) { "Your session changed. Sign in again." }
        currentCoroutineContext().ensureActive()
        val connection = endpoint.openConnection() as HttpURLConnection
        // HttpURLConnection has no write timeout; disconnect also bounds a stalled upload.
        val watchdog = Timer("visidock-image-timeout", true)
        watchdog.schedule(object : TimerTask() { override fun run() { connection.disconnect() } }, 45_000L)
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            // Never forward the bearer token to a redirect destination.
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", if (method == "GET") "image/*" else "application/json")
            if (file != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", checkNotNull(mime))
                connection.setFixedLengthStreamingMode(file.length())
                connection.outputStream.use { output ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            val status = connection.responseCode
            if (status !in 200..299) throw IOException(imageApiError(status))
            if (method != "GET") return@withContext null
            require(connection.contentLengthLong <= MAX_DOWNLOAD) { "This card image is too large to display." }
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(bytes.size().toLong() + count <= MAX_DOWNLOAD) { "This card image is too large to display." }
                    bytes.write(buffer, 0, count)
                }
            }
            check(auth.currentUser?.uid == uid) { "Your session changed. Sign in again." }
            bytes.toByteArray()
        } finally {
            watchdog.cancel()
            connection.disconnect()
        }
    }

    private companion object { const val MAX_DOWNLOAD = 6L * 1024 * 1024 }
}

internal fun imageApiBase(value: String): URI {
    val uri = try { URI(value.trim()) } catch (_: Exception) { throw IllegalArgumentException("The card image service URL is invalid.") }
    require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
        "Card image storage is not available yet. Your draft is still here. Please try again later."
    }
    return uri
}

internal fun imageApiEndpoint(base: String, cardId: String, image: String): URL {
    require(cardId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "This card has an invalid identifier." }
    require(image in setOf("original", "preview.jpg", "back-original", "back-preview.jpg")) { "This image type is not supported." }
    return URL(imageApiBase(base).toASCIIString().trimEnd('/') + "/v1/cards/$cardId/$image")
}

internal fun imageApiError(status: Int): String = when (status) {
    401 -> "Your image service session expired. Sign in again."
    403 -> "Your account cannot access this card image."
    404 -> "This card image is no longer available."
    409 -> "This card is not ready for image changes. Retry sync and try again."
    413 -> "Choose an image smaller than 20 MB."
    415 -> "Choose a JPEG, PNG or WebP image."
    429 -> "The image service is busy. Wait a moment and try again."
    in 500..599 -> "The image service is temporarily unavailable. Your draft is still here; try again shortly."
    else -> "The image service could not complete this request. Check its configuration and try again."
}
