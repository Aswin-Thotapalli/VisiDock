package com.thotapalli.visidock

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ImportQueueTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun photo():File {
        val image=Bitmap.createBitmap(240,140,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
        return File(context.cacheDir,"import-test-${UUID.randomUUID()}.png").also {file->
            try {file.outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}} finally {image.recycle()}
        }
    }
    @Test fun pairingPersistsAcrossInstancesAndAccountsCannotReadEachOthersQueue()=runBlocking {
        val account="test-${UUID.randomUUID()}";val sourceA=photo();val sourceB=photo()
        val queue=ImportQueue(context,account)
        try {
            val added=queue.import(listOf(Uri.fromFile(sourceA),Uri.fromFile(sourceB)))
            queue.pair(added[0].id,added[1].id)
            val restored=ImportQueue(context,account).items().single()
            assertEquals(added[0].id,restored.id);assertEquals(File(added[1].frontPath).canonicalFile,File(checkNotNull(restored.backPath)).canonicalFile)
            assertTrue(File(restored.frontPath).exists());assertTrue(File(restored.backPath!!).exists())
            assertTrue(ImportQueue(context,"other-$account").items().isEmpty())
            queue.separate(restored.id)
            assertEquals(2,ImportQueue(context,account).items().size)
        } finally {queue.items().forEach {queue.complete(it.id)};sourceA.delete();sourceB.delete()}
    }
    @Test fun completingOneCropDoesNotDeleteTheOtherCropSource()=runBlocking {
        val source=photo();val queue=ImportQueue(context,"test-${UUID.randomUUID()}")
        try {
            val first=queue.import(listOf(Uri.fromFile(source))).single()
            queue.duplicateSource(first.id)
            queue.complete(first.id)
            val second=ImportQueue(context,"unused-account").items()
            assertTrue(second.isEmpty())
            val retained=queue.items().single()
            assertTrue(File(retained.frontPath).exists())
            queue.complete(retained.id)
            assertFalse(File(retained.frontPath).exists())
        } finally {queue.items().forEach {queue.complete(it.id)};source.delete()}
    }
    @Test fun encodedSelectiveQrCanBeReadBackWithoutPrivateFields()=runBlocking {
        val source=File(context.cacheDir,"qr-test-${UUID.randomUUID()}.png")
        val bitmap=QrContacts.encode(Card(name="Mira Rao",email="mira@example.com",notes="Do not share",address="Private address"))
        try {
            source.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            val card=(QrContacts.decode(source).single() as QrPayload.Contact).card
            assertEquals("Mira Rao",card.name);assertEquals("mira@example.com",card.email)
            assertTrue(card.notes.isEmpty());assertTrue(card.address.isEmpty())
        } finally {bitmap.recycle();source.delete()}
    }
}
