package com.thotapalli.visidock

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Explicit opt-in live smoke check; never runs in the demo or ordinary test suite. */
@RunWith(AndroidJUnit4::class)
class CloudImageIntegrationTest {
    @Test fun cloudRepositoryRoundTrip() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCloudTests") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(FirebaseAuth.getInstance().currentUser == null) { "Live test requires a signed-out test device." }
        val repository = CloudRepository()
        val unique = UUID.randomUUID().toString()
        val password = UUID.randomUUID().toString() + "Aa1!"
        val original = File(context.cacheDir, "smoke-$unique.png")
        val preview = File(context.cacheDir, "smoke-$unique.jpg")
        var registered = false
        try {
            val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xff225544.toInt())
            try {
                original.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                preview.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
            } finally { bitmap.recycle() }
            repository.signIn("visidock-device-$unique@example.invalid", password, true)
            registered = true
            val card = repository.save(Card(id = unique, name = "Temporary device smoke test"), ScanFiles(original, preview, "image/png", ""))
            assertTrue(card.imagePath.endsWith("/preview.jpg"))
            val bytes = checkNotNull(repository.photo(card))
            assertArrayEquals(preview.readBytes(), bytes)
            val decoded = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try { assertEquals(12, decoded.width); assertEquals(8, decoded.height) } finally { decoded.recycle() }
            repository.save(card.copy(favorite = true), null)
            repository.delete(card)
            repository.retryCleanup()
        } finally {
            original.delete()
            preview.delete()
            if (registered) repository.deleteAccount(password)
            repository.signOut()
        }
    }
}
