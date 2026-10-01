package com.thotapalli.visidock

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OfflineCardRepositoryTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private class Remote(val account:String):CardRepository {
        var online=false
        var user:Session?=Session(account,"test@example.com",true)
        val cards=MutableStateFlow<List<Card>>(emptyList())
        val operations=mutableMapOf<String,Card>()
        val images=mutableMapOf<String,ByteArray>()
        override fun session()=user
        override fun observe()=cards
        override suspend fun signIn(email:String,password:String,register:Boolean)=Unit
        override suspend fun updateDisplayName(name:String)=Unit
        override suspend fun resetPassword(email:String)=Unit
        override suspend fun verifyEmail()=Unit
        override suspend fun refreshSession()=Unit
        override fun signOut() {user=null}
        override suspend fun save(card:Card,files:ScanFiles?,backFiles:ScanFiles?)=commit(card,files,backFiles,UUID.randomUUID().toString())
        override suspend fun commit(card:Card,files:ScanFiles?,backFiles:ScanFiles?,operationId:String):Card {
            check(online) {"Offline"}
            operations[operationId]?.let {return it}
            val existing=cards.value.firstOrNull {it.id==card.id}
            if(existing!=null && existing.revision!=card.revision) throw CardConflictException(existing)
            val front=files?.let {"$operationId/front".also {path->images[path]=it.preview.readBytes()}}
            val back=backFiles?.let {"$operationId/back".also {path->images[path]=it.preview.readBytes()}}
            val saved=card.copy(revision=card.revision+1,imagePath=front ?: card.imagePath,backImagePath=back ?: card.backImagePath)
            cards.value=cards.value.filterNot {it.id==card.id}+saved;operations[operationId]=saved;return saved
        }
        override suspend fun delete(card:Card) {check(online);cards.value=cards.value.filterNot {it.id==card.id}}
        override suspend fun deleteAccount(password:String) {cards.value=emptyList();user=null}
        override suspend fun photo(card:Card,back:Boolean):ByteArray?=images[if(back) card.backImagePath else card.imagePath]
        override suspend fun retryCleanup()=Unit
    }
    @Test fun offlineRestartPhotosHistoryRecoveryAndAccountIsolation()=runBlocking {
        val account="offline-test-${UUID.randomUUID()}";val remote=Remote(account)
        val local=LocalCardVault(context,account)
        try {
            val repository=OfflineCardRepository(context,remote,false)
            val original=File.createTempFile("vault-test",".png",context.cacheDir).apply {writeBytes(byteArrayOf(1,2,3,4))}
            val preview=File.createTempFile("vault-test",".jpg",context.cacheDir).apply {writeBytes(byteArrayOf(5,6,7))}
            val first=repository.save(Card(id="one",name="Private person"),ScanFiles(original,preview,"image/png",""))
            assertTrue(first.hasFrontImage);assertFalse(first.hasBackImage)
            assertFalse(first.record().containsKey("hasLocalFrontImage"))
            original.delete();preview.delete()
            assertFalse(repository.syncNow())
            val restarted=OfflineCardRepository(context,remote,false)
            assertEquals("Private person",restarted.observe().first().single().name)
            assertTrue(restarted.observe().first().single().hasFrontImage)
            assertArrayEquals(byteArrayOf(5,6,7),restarted.photo(first))
            assertArrayEquals(byteArrayOf(1,2,3,4),restarted.original(first))
            val edited=restarted.save(first.copy(role="New role"),null)
            assertEquals(1,restarted.versions(first.id).size)
            restarted.delete(edited)
            assertTrue(restarted.observe().first().isEmpty())
            val deleted=restarted.observeLocalState().first().deletedCards.single()
            restarted.restore(deleted)
            assertEquals(1,restarted.observe().first().size)
            val other=Remote("other-${UUID.randomUUID()}")
            assertTrue(OfflineCardRepository(context,other,false).observe().first().isEmpty())
            remote.online=true;assertTrue(restarted.syncNow())
            assertEquals(0,restarted.observeLocalState().first().pendingCount)
            assertEquals("New role",remote.cards.value.single().role)
            // No contact plaintext should exist in durable snapshot or encrypted photo files.
            val vaultRoot=File(context.noBackupFilesDir,"card-vault")
            assertFalse(vaultRoot.walkTopDown().filter {it.isFile}.any {it.readBytes().toString(Charsets.ISO_8859_1).contains("Private person")})
        } finally {local.clear()}
    }
    @Test fun remoteConflictNeverOverwritesUntilExplicitResolution()=runBlocking {
        val account="conflict-test-${UUID.randomUUID()}";val remote=Remote(account);val local=LocalCardVault(context,account)
        try {
            val repository=OfflineCardRepository(context,remote,false)
            repository.save(Card(id="one",name="Local"),null)
            remote.cards.value=listOf(Card(id="one",name="Remote",revision=4))
            remote.online=true;assertTrue(repository.syncNow())
            assertEquals("Remote",remote.cards.value.single().name)
            assertEquals(1,repository.observeLocalState().first().conflicts.size)
            repository.resolveConflict("one",true);assertTrue(repository.syncNow())
            assertEquals("Local",remote.cards.value.single().name)
            assertEquals(5L,remote.cards.value.single().revision)
        } finally {local.clear()}
    }
    private suspend fun withPhotos(front:ByteArray,back:ByteArray?=null,block:suspend (ScanFiles,ScanFiles?)->Unit) {
        val files=mutableListOf<File>()
        fun scan(bytes:ByteArray):ScanFiles {
            val original=File.createTempFile("vault-original",".jpg",context.cacheDir).also {it.writeBytes(bytes);files+=it}
            val preview=File.createTempFile("vault-preview",".jpg",context.cacheDir).also {it.writeBytes(bytes);files+=it}
            return ScanFiles(original,preview,"image/jpeg","")
        }
        try {block(scan(front),back?.let(::scan))} finally {files.forEach(File::delete)}
    }
    @Test fun restoringTextOnlyVersionRetainsPendingFrontAndBackUploads()=runBlocking {
        val account="version-photos-${UUID.randomUUID()}";val remote=Remote(account);val local=LocalCardVault(context,account)
        try {
            val repository=OfflineCardRepository(context,remote,false)
            val text=repository.save(Card(id="one",name="Original name"),null)
            withPhotos(byteArrayOf(1,2,3),byteArrayOf(4,5,6)) {front,back->
                repository.save(text.copy(name="Changed name"),front,back)
            }
            val version=repository.versions("one").single {it.card.name=="Original name"}
            val restored=repository.restoreVersion("one",version.id)
            assertEquals("Original name",restored.name)
            assertArrayEquals(byteArrayOf(1,2,3),repository.photo(restored))
            assertArrayEquals(byteArrayOf(4,5,6),repository.photo(restored,true))
            remote.online=true;assertTrue(repository.syncNow())
            val uploaded=remote.cards.value.single()
            assertTrue(uploaded.imagePath.isNotBlank());assertTrue(uploaded.backImagePath.isNotBlank())
            assertArrayEquals(byteArrayOf(1,2,3),remote.photo(uploaded,false))
            assertArrayEquals(byteArrayOf(4,5,6),remote.photo(uploaded,true))
        } finally {local.clear()}
    }
    @Test fun keepingLocalTextInvalidatesOnlyTheRemotelyChangedPhotoCache()=runBlocking {
        val account="conflict-cache-${UUID.randomUUID()}";val remote=Remote(account);val local=LocalCardVault(context,account)
        try {
            val repository=OfflineCardRepository(context,remote,false)
            withPhotos(byteArrayOf(1),byteArrayOf(2)) {front,back->repository.save(Card(id="one",name="Original"),front,back)}
            remote.online=true;assertTrue(repository.syncNow())
            val original=remote.cards.value.single()
            repository.save(original.copy(name="My corrected name"),null)
            remote.images["remote/front"]=byteArrayOf(9)
            remote.cards.value=listOf(original.copy(revision=original.revision+1,imagePath="remote/front"))
            assertTrue(repository.syncNow());assertEquals(1,repository.observeLocalState().first().conflicts.size)
            repository.resolveConflict("one",true)
            val resolved=local.read().cards.getValue("one")
            assertEquals("My corrected name",resolved.name)
            assertFalse(resolved.hasLocalFrontImage);assertTrue(resolved.hasLocalBackImage)
            assertArrayEquals(byteArrayOf(9),repository.photo(resolved))
            assertArrayEquals(byteArrayOf(2),repository.photo(resolved,true))
            assertTrue(repository.syncNow());assertEquals("My corrected name",remote.cards.value.single().name)
        } finally {local.clear()}
    }
    @Test fun keepingLocalPhotoReplacementPreservesItsPendingUploadDuringConflict()=runBlocking {
        val account="conflict-upload-${UUID.randomUUID()}";val remote=Remote(account);val local=LocalCardVault(context,account)
        try {
            val repository=OfflineCardRepository(context,remote,false)
            repository.save(Card(id="one",name="Original"),null)
            remote.online=true;assertTrue(repository.syncNow())
            val original=remote.cards.value.single()
            withPhotos(byteArrayOf(7,8)) {front,_->repository.save(original.copy(name="Mine"),front)}
            remote.images["remote/front"]=byteArrayOf(9)
            remote.cards.value=listOf(original.copy(revision=original.revision+1,imagePath="remote/front"))
            assertTrue(repository.syncNow());repository.resolveConflict("one",true)
            val resolved=local.read().cards.getValue("one")
            assertTrue(resolved.hasLocalFrontImage);assertArrayEquals(byteArrayOf(7,8),repository.photo(resolved))
            assertTrue(repository.syncNow());val uploaded=remote.cards.value.single()
            assertArrayEquals(byteArrayOf(7,8),remote.photo(uploaded,false));assertEquals("Mine",uploaded.name)
        } finally {local.clear()}
    }

}
