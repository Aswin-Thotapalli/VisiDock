package com.thotapalli.visidock

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Local commit is the save boundary. Cloud acknowledgement is independently observable and retryable. */
class OfflineCardRepository(context:Context,private val remote:CardRepository,private val automaticSync:Boolean=true):CardRepository by remote {
    private val context=context.applicationContext
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val activity=MutableStateFlow<Pair<Boolean,String?>>(false to null)
    private fun uid()=checkNotNull(session()) {"Sign in to open your collection."}.uid
    private fun requireAccount(expected:String) {check(session()?.uid==expected) {"The signed-in account changed."}}
    private fun vault(account:String)=LocalCardVault(context,account)

    override fun observe():Flow<List<Card>> = channelFlow {
        val account=uid();val local=vault(account)
        val snapshots=launch(Dispatchers.IO) {
            local.changes.collect {
                requireAccount(account)
                val cards=local.mutex.withLock {local.read().cards.values.filter {it.deletedAt==0L}.sortedByDescending {it.createdAt}}
                requireAccount(account);send(cards)
            }
        }
        val cloud=launch(Dispatchers.IO) {
            try {
                remote.observeSnapshots().collect {collection->
                    val cards=collection.cards
                    requireAccount(account)
                    local.mutex.withLock {
                        val snapshot=local.read();var changed=false
                        cards.forEach {card->
                            val old=snapshot.cards[card.id]
                            if(card.id !in snapshot.pending && (old==null || card.revision>=old.revision)) {
                                val imageChanged=old!=null && (old.imagePath!=card.imagePath || old.backImagePath!=card.backImagePath)
                                val photos=if(imageChanged) LocalPhotos() else snapshot.photos[card.id] ?: LocalPhotos()
                                val next=card.copy(hasLocalFrontImage=photos.front.isNotBlank(),hasLocalBackImage=photos.back.isNotBlank())
                                if(old!=next) {
                                    if(old!=null) addHistory(snapshot,old,snapshot.photos[card.id] ?: LocalPhotos())
                                    if(imageChanged) snapshot.photos.remove(card.id)
                                    snapshot.cards[card.id]=next;changed=true
                                }
                            }
                        }
                        if(collection.authoritative) {
                            val ids=cards.mapTo(hashSetOf()) {it.id}
                            snapshot.cards.keys.filter {it !in ids && it !in snapshot.pending}.forEach {id->
                                snapshot.cards.remove(id);snapshot.photos.remove(id);snapshot.history.remove(id);snapshot.conflicts.remove(id);changed=true
                            }
                        }
                        // Absence is meaningful only in an acknowledged full server snapshot.
                        if(changed) {local.write(snapshot);if(collection.authoritative) local.collectUnusedPhotos(snapshot)}
                    }
                }
            } catch(error:CancellationException) {throw error}
            catch(error:Exception) {activity.value=false to "Cloud sync is unavailable. Your saved device copy is available."}
        }
        schedule(account)
        awaitClose {snapshots.cancel();cloud.cancel()}
    }
    override fun observeLocalState():Flow<LocalVaultState> = channelFlow {
        val account=uid();val local=vault(account)
        suspend fun publish() {
            requireAccount(account)
            val state=local.mutex.withLock {
                val s=local.read();LocalVaultState(s.pending.size,s.cards.values.filter {it.deletedAt>0}.sortedByDescending {it.deletedAt},s.conflicts.values.toList(),activity.value.first,activity.value.second)
            }
            requireAccount(account);send(state)
        }
        val a=launch(Dispatchers.IO) {local.changes.collect {publish()}}
        val b=launch(Dispatchers.IO) {activity.collect {publish()}}
        awaitClose {a.cancel();b.cancel()}
    }
    override suspend fun save(card:Card,files:ScanFiles?,backFiles:ScanFiles?):Card = withContext(Dispatchers.IO) {
        CardLogic.validate(card)?.let {error(it)}
        val account=uid();val local=vault(account)
        val saved=local.mutex.withLock {
            requireAccount(account);val state=local.read()
            check(card.id !in state.conflicts) {"Resolve this card's cloud conflict before editing it."}
            val old=state.cards[card.id]
            if(old!=null && old.revision!=card.revision && card.id !in state.pending) throw CardConflictException(old)
            val previousPhotos=state.photos[card.id] ?: LocalPhotos()
            val front=files?.let(local::savePhotos)
            val back=backFiles?.let(local::savePhotos)
            val existing=state.pending[card.id]
            check(existing?.purge!=true) {"This card is queued for permanent deletion."}
            val updated=card.copy(revision=old?.revision ?: card.revision,updatedAt=System.currentTimeMillis(),
                hasLocalFrontImage=front!=null||previousPhotos.front.isNotBlank(),hasLocalBackImage=back!=null||previousPhotos.back.isNotBlank(),
                sourceScanId=if(files!=null || backFiles!=null) UUID.randomUUID().toString().replace("-","") else card.sourceScanId)
            if(old!=null) addHistory(state,old,previousPhotos)
            state.cards[card.id]=updated
            state.photos[card.id]=LocalPhotos(front ?: previousPhotos.front,back ?: previousPhotos.back)
            state.pending[card.id]=PendingCard(UUID.randomUUID().toString(),card.id,front ?: existing?.front.orEmpty(),back ?: existing?.back.orEmpty())
            requireAccount(account);local.write(state);updated
        }
        schedule(account);saved
    }
    override suspend fun delete(card:Card) {save(card.copy(deletedAt=System.currentTimeMillis()),null)}
    override suspend fun restore(card:Card):Card=save(card.copy(deletedAt=0),null)
    override suspend fun purge(card:Card)=withContext(Dispatchers.IO) {
        require(card.deletedAt>0) {"Move this card to the recovery bin first."}
        val account=uid();val local=vault(account)
        local.mutex.withLock {
            requireAccount(account);val s=local.read()
            check(card.id !in s.conflicts) {"Resolve the conflict before permanently deleting this card."}
            s.pending[card.id]=PendingCard(UUID.randomUUID().toString(),card.id,purge=true);local.write(s)
        }
        schedule(account)
    }
    override suspend fun versions(cardId:String):List<CardVersion> = withContext(Dispatchers.IO) {
        val account=uid();val local=vault(account)
        local.mutex.withLock {requireAccount(account);local.read().history[cardId].orEmpty().map {it.version}.reversed()}
    }
    override suspend fun restoreVersion(cardId:String,versionId:String):Card = withContext(Dispatchers.IO) {
        val account=uid();val local=vault(account)
        val restored=local.mutex.withLock {
            requireAccount(account);val s=local.read()
            check(cardId !in s.conflicts) {"Resolve the cloud conflict first."}
            val current=s.cards[cardId] ?: error("This card is no longer available.")
            val entry=s.history[cardId].orEmpty().firstOrNull {it.version.id==versionId} ?: error("This version is unavailable.")
            addHistory(s,current,s.photos[cardId] ?: LocalPhotos())
            // Metadata restoration keeps current cloud photos: old cloud objects may already be retired.
            // Locally retained originals are queued again as a new immutable image revision.
            val front=entry.photos.front.takeIf {local.photo(it,true)!=null}.orEmpty()
            val back=entry.photos.back.takeIf {local.photo(it,true)!=null}.orEmpty()
            val result=entry.version.card.copy(revision=current.revision,updatedAt=System.currentTimeMillis(),deletedAt=0,
                imagePath=current.imagePath,originalPath=current.originalPath,backImagePath=current.backImagePath,backOriginalPath=current.backOriginalPath,
                sourceScanId=if(front.isNotEmpty()||back.isNotEmpty()) UUID.randomUUID().toString().replace("-","") else current.sourceScanId,
                hasLocalFrontImage=front.isNotBlank()||current.hasLocalFrontImage,hasLocalBackImage=back.isNotBlank()||current.hasLocalBackImage)
            s.cards[cardId]=result
            s.photos[cardId]=LocalPhotos(front.ifBlank {s.photos[cardId]?.front.orEmpty()},back.ifBlank {s.photos[cardId]?.back.orEmpty()})
            val pending=s.pending[cardId]
            // Restoring metadata must not discard an unsynced photograph that remains on this card.
            s.pending[cardId]=PendingCard(UUID.randomUUID().toString(),cardId,
                front.ifBlank {pending?.front.orEmpty()},back.ifBlank {pending?.back.orEmpty()})
            local.write(s);result
        }
        schedule(account);restored
    }
    override suspend fun resolveConflict(cardId:String,keepLocal:Boolean)=withContext(Dispatchers.IO) {
        val account=uid();val local=vault(account)
        local.mutex.withLock {
            requireAccount(account);val s=local.read();val conflict=s.conflicts.remove(cardId) ?: return@withLock
            if(keepLocal) {
                val pending=s.pending[cardId]
                val previous=s.photos[cardId] ?: LocalPhotos()
                // A metadata-only local edit adopts the remote image revision. Its old cached pixels
                // must not masquerade as that revision; pending local replacements remain authoritative.
                val photos=LocalPhotos(
                    pending?.front?.takeIf(String::isNotBlank) ?: previous.front.takeIf {
                        conflict.local.imagePath==conflict.remote.imagePath && conflict.local.originalPath==conflict.remote.originalPath
                    }.orEmpty(),
                    pending?.back?.takeIf(String::isNotBlank) ?: previous.back.takeIf {
                        conflict.local.backImagePath==conflict.remote.backImagePath && conflict.local.backOriginalPath==conflict.remote.backOriginalPath
                    }.orEmpty())
                if(photos!=previous) addHistory(s,conflict.local,previous)
                s.photos[cardId]=photos
                s.cards[cardId]=conflict.local.copy(revision=conflict.remote.revision,createdAt=conflict.remote.createdAt,
                    imagePath=conflict.remote.imagePath,originalPath=conflict.remote.originalPath,
                    backImagePath=conflict.remote.backImagePath,backOriginalPath=conflict.remote.backOriginalPath,
                    sourceScanId=if(pending?.front?.isNotBlank()==true||pending?.back?.isNotBlank()==true) conflict.local.sourceScanId else conflict.remote.sourceScanId,
                    hasLocalFrontImage=photos.front.isNotBlank(),hasLocalBackImage=photos.back.isNotBlank())
                s.pending[cardId]=(pending ?: PendingCard("",cardId)).copy(id=UUID.randomUUID().toString())
            } else {
                addHistory(s,conflict.local,s.photos[cardId] ?: LocalPhotos())
                s.cards[cardId]=conflict.remote.copy(hasLocalFrontImage=false,hasLocalBackImage=false);s.pending.remove(cardId);s.photos.remove(cardId)
            }
            local.write(s)
        }
        schedule(account)
    }
    override suspend fun merge(target:Card,source:Card):Card=withContext(Dispatchers.IO) {
        val account=uid();val local=vault(account)
        val merged=local.mutex.withLock {
            requireAccount(account);val s=local.read()
            check(target.id !in s.conflicts && source.id !in s.conflicts) {"Resolve cloud conflicts before merging."}
            val a=s.cards[target.id] ?: target;val b=s.cards[source.id] ?: source
            require(a.deletedAt==0L && b.deletedAt==0L) {"Restore deleted cards before merging."}
            val result=CardMerge.propose(a,b).copy(updatedAt=System.currentTimeMillis())
            CardLogic.validate(result)?.let {error(it)}
            addHistory(s,a,s.photos[a.id] ?: LocalPhotos());addHistory(s,b,s.photos[b.id] ?: LocalPhotos())
            s.cards[a.id]=result;s.cards[b.id]=b.copy(deletedAt=System.currentTimeMillis(),updatedAt=System.currentTimeMillis())
            listOf(a.id,b.id).forEach {id->s.pending[id]=(s.pending[id] ?: PendingCard("",id)).copy(id=UUID.randomUUID().toString())}
            local.write(s);result
        }
        schedule(account);merged
    }
    override suspend fun photo(card:Card,back:Boolean):ByteArray?=readPhoto(card,back,false)
    override suspend fun original(card:Card,back:Boolean):ByteArray?=readPhoto(card,back,true)
    private suspend fun readPhoto(card:Card,back:Boolean,original:Boolean):ByteArray?=withContext(Dispatchers.IO) {
        val account=uid();val local=vault(account)
        val cached=local.mutex.withLock {
            requireAccount(account);val p=local.read().photos[card.id]
            local.photo(if(back) p?.back.orEmpty() else p?.front.orEmpty(),original)
        }
        if(cached!=null) {requireAccount(account);return@withContext cached}
        val bytes=if(original) remote.original(card,back) else remote.photo(card,back)
        requireAccount(account)
        if(bytes!=null && !original) local.mutex.withLock {
            requireAccount(account);val s=local.read();val current=s.cards[card.id]
            if(current!=null && current.imagePath==card.imagePath && current.backImagePath==card.backImagePath && card.id !in s.pending) {
                val p=s.photos[card.id] ?: LocalPhotos();val id=local.cachePhoto(bytes)
                s.photos[card.id]=if(back) p.copy(back=id) else p.copy(front=id);local.write(s)
            }
        }
        bytes
    }
    override fun signOut() {
        val account=session()?.uid
        remote.signOut();activity.value=false to null
        if(account!=null) WorkManager.getInstance(context).cancelUniqueWork("visidock-sync-$account")
    }
    override suspend fun deleteAccount(password:String) {
        val account=uid();remote.deleteAccount(password)
        withContext(Dispatchers.IO) {val local=vault(account);local.mutex.withLock {local.clear()}}
        WorkManager.getInstance(context).cancelUniqueWork("visidock-sync-$account")
    }
    override suspend fun retryCleanup() {syncNow();remote.retryCleanup()}
    suspend fun syncNow():Boolean=withContext(Dispatchers.IO) {
        val account=uid();val local=vault(account)
        local.syncMutex.withLock {
            requireAccount(account);local.cleanUploadTemps(context)
            activity.value=true to null
            try {
                while(true) {
                    requireAccount(account)
                    val task=local.mutex.withLock {
                        val s=local.read();s.pending.values.firstOrNull {it.cardId !in s.conflicts}?.let {it to (s.cards[it.cardId] ?: error("Queued card is unavailable."))}
                    } ?: return@withLock true
                    val (pending,card)=task
                    var front:ScanFiles?=null;var back:ScanFiles?=null
                    try {
                        front=local.materialize(pending.front,context);back=local.materialize(pending.back,context)
                        val confirmed=if(pending.purge) {remote.purge(card);null} else remote.commit(card,front,back,pending.id)
                        requireAccount(account)
                        local.mutex.withLock {
                            val s=local.read();val latest=s.pending[card.id]
                            if(latest?.id==pending.id) {
                                s.pending.remove(card.id);s.conflicts.remove(card.id)
                                if(confirmed==null) {s.cards.remove(card.id);s.photos.remove(card.id);s.history.remove(card.id)} else {
                                    val photos=s.photos[card.id] ?: LocalPhotos()
                                    s.cards[card.id]=confirmed.copy(hasLocalFrontImage=photos.front.isNotBlank(),hasLocalBackImage=photos.back.isNotBlank())
                                }
                            } else if(confirmed!=null && latest!=null) {
                                // An edit made during upload remains queued, rebased onto our own acknowledged predecessor.
                                s.cards[card.id]=checkNotNull(s.cards[card.id]).copy(revision=confirmed.revision,
                                    imagePath=confirmed.imagePath,originalPath=confirmed.originalPath,
                                    backImagePath=confirmed.backImagePath,backOriginalPath=confirmed.backOriginalPath)
                                s.pending[card.id]=latest.copy(front=latest.front.takeUnless {it==pending.front}.orEmpty(),back=latest.back.takeUnless {it==pending.back}.orEmpty())
                            }
                            local.write(s)
                            if(confirmed==null) local.collectUnusedPhotos(s)
                        }
                    } catch(error:CancellationException) {throw error}
                    catch(error:Exception) {
                        val conflict=generateSequence<Throwable>(error) {it.cause}.filterIsInstance<CardConflictException>().firstOrNull()
                        if(conflict!=null) local.mutex.withLock {
                            requireAccount(account);val s=local.read();val current=s.cards[card.id] ?: card
                            s.conflicts[card.id]=CardConflict(card.id,current,conflict.remote);local.write(s)
                        } else {
                            activity.value=false to "Saved on this device. Cloud sync will retry when a connection is available."
                            return@withLock false
                        }
                    } finally {
                        listOfNotNull(front,back).forEach {it.original.delete();it.preview.delete()}
                    }
                }
                @Suppress("UNREACHABLE_CODE") false
            } finally {activity.value=false to activity.value.second}
        }
    }
    private fun schedule(account:String) {
        if(!automaticSync) return
        val request=OneTimeWorkRequestBuilder<CardOutboxWorker>()
            .setInputData(workDataOf("account" to account))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("visidock-sync-$account",ExistingWorkPolicy.APPEND_OR_REPLACE,request)
        scope.launch {try {if(session()?.uid==account) syncNow()} catch(_:Exception) { /* Durable worker owns retries. */ }}
    }
    private fun addHistory(state:VaultSnapshot,card:Card,photos:LocalPhotos) {
        val list=state.history.getOrPut(card.id) {mutableListOf()}
        if(list.lastOrNull()?.version?.card==card) return
        list.add(StoredVersion(CardVersion(UUID.randomUUID().toString(),card,System.currentTimeMillis()),photos))
    }
}

class CardOutboxWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {
        val expected=inputData.getString("account") ?: return Result.failure()
        return try {
            val cloud=CloudRepository()
            if(cloud.session()?.uid!=expected) return Result.success()
            if(OfflineCardRepository(applicationContext,cloud).syncNow()) Result.success() else Result.retry()
        } catch(error:CancellationException) {throw error} catch(_:Exception) {Result.retry()}
    }
}
