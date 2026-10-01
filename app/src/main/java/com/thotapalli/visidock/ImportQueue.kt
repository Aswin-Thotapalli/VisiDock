package com.thotapalli.visidock

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** A source may intentionally serve several separate crops. Completing one never deletes another's source. */
data class BatchImportItem(val id:String,val frontPath:String,val backPath:String?=null) {
    val front:Uri get()=Uri.fromFile(File(frontPath))
    val back:Uri? get()=backPath?.let {Uri.fromFile(File(it))}
}
private fun accountImportKey(account:String)=MessageDigest.getInstance("SHA-256").digest(account.toByteArray()).joinToString("") {"%02x".format(it)}
private fun copyBounded(context:Context,uri:Uri,destination:File,maxBytes:Long=40L*1024*1024) {
    try {
        context.contentResolver.openInputStream(uri)?.use {input->destination.outputStream().use {out->
            val buffer=ByteArray(64*1024);var count=0L
            while(true) {val size=input.read(buffer);if(size<0) break;count+=size;require(count<=maxBytes) {"Each import must be smaller than 40 MB."};out.write(buffer,0,size)}
            require(count>0) {"The selected file is empty."}
        }} ?: error("The selected file cannot be opened.")
    } catch(error:Throwable) {destination.delete();throw error}
}
private fun writeJson(file:File,json:JSONObject) {
    val atomic=AtomicFile(file);val stream=atomic.startWrite()
    try {stream.write(json.toString().toByteArray());atomic.finishWrite(stream)} catch(error:Throwable) {atomic.failWrite(stream);throw error}
}

class ImportQueue(context:Context,accountId:String) {
    private val context=context.applicationContext
    private val root=File(context.filesDir,"import-queue/${accountImportKey(accountId)}").apply {require(accountId.isNotBlank());mkdirs()}
    private val index=File(root,"queue.json")
    private val lock=locks.getOrPut(root.absolutePath) {Any()}
    companion object {private val locks=ConcurrentHashMap<String,Any>()}
    private fun read():List<BatchImportItem> {
        if(!index.exists()) return emptyList()
        val array=JSONObject(AtomicFile(index).openRead().use {it.readBytes().toString(Charsets.UTF_8)}).getJSONArray("items")
        return (0 until array.length()).map {i->val item=array.getJSONObject(i)
            BatchImportItem(item.getString("id"),File(root,item.getString("front")).canonicalPath,item.optString("back").takeIf(String::isNotBlank)?.let {File(root,it).canonicalPath})
        }.onEach {item->require(File(item.frontPath).parentFile==root.canonicalFile && (item.backPath==null || File(item.backPath).parentFile==root.canonicalFile))}
    }
    private fun write(items:List<BatchImportItem>) {writeJson(index,JSONObject().put("items",JSONArray(items.map {JSONObject().put("id",it.id).put("front",File(it.frontPath).name).put("back",it.backPath?.let {p->File(p).name}.orEmpty())})))}
    suspend fun items()=withContext(Dispatchers.IO) {synchronized(lock) {read()}}
    suspend fun import(uris:List<Uri>):List<BatchImportItem> =withContext(Dispatchers.IO) {synchronized(lock) {
        require(uris.size<=24) {"Choose at most 24 documents per import."}
        val current=read().toMutableList();val staged=mutableListOf<File>()
        try {
            for(uri in uris) {
                require(current.size<100) {"Review the queued cards before adding more."}
                require((root.listFiles()?.sumOf {it.length()} ?: 0)<256L*1024*1024) {"The import queue is full. Review or remove some items first."}
                val mime=context.contentResolver.getType(uri).orEmpty()
                val source=File(root,"${UUID.randomUUID()}.source").also {staged+=it}
                copyBounded(context,uri,source)
                val isPdf=source.inputStream().use {input->val header=ByteArray(5);input.read(header)==5 && header.toString(Charsets.US_ASCII)=="%PDF-"}
                if(isPdf || mime=="application/pdf") {
                    PdfRenderer(ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY)).use {pdf->
                        require(pdf.pageCount in 1..24 && current.size+pdf.pageCount<=100) {"A PDF can contain at most 24 pages per import."}
                        repeat(pdf.pageCount) {pageIndex->pdf.openPage(pageIndex).use {page->
                            val scale=2400f/maxOf(page.width,page.height)
                            val image=Bitmap.createBitmap((page.width*scale).roundToInt().coerceAtLeast(1),(page.height*scale).roundToInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                            val rendered=File(root,"${UUID.randomUUID()}.jpg").also {staged+=it}
                            try {image.eraseColor(Color.WHITE);page.render(image,null,null,PdfRenderer.Page.RENDER_MODE_FOR_PRINT);rendered.outputStream().use {image.compress(Bitmap.CompressFormat.JPEG,95,it)}} finally {image.recycle()}
                            current+=BatchImportItem(UUID.randomUUID().toString(),rendered.absolutePath)
                        }}
                    }
                    source.delete()
                } else {
                    val dimensions=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
                    android.graphics.BitmapFactory.decodeFile(source.absolutePath,dimensions)
                    require(dimensions.outWidth>0 && dimensions.outHeight>0) {"Choose an image or PDF document."}
                    current+=BatchImportItem(UUID.randomUUID().toString(),source.absolutePath)
                }
            }
            write(current);current
        } catch(error:Throwable) {staged.forEach(File::delete);throw error}
    }}
    suspend fun pair(frontId:String,backId:String)=withContext(Dispatchers.IO) {synchronized(lock) {
        val items=read();val front=items.first {it.id==frontId};val back=items.first {it.id==backId}
        require(front.id!=back.id && front.backPath==null && back.backPath==null)
        write(items.filter {it.id!=backId}.map {if(it.id==frontId) it.copy(backPath=back.frontPath) else it})
    }}
    suspend fun separate(id:String)=withContext(Dispatchers.IO) {synchronized(lock) {
        val items=read();val item=items.first {it.id==id};val back=item.backPath ?: return@synchronized
        write(items.flatMap {if(it.id==id) listOf(it.copy(backPath=null),BatchImportItem(UUID.randomUUID().toString(),back)) else listOf(it)})
    }}
    suspend fun duplicateSource(id:String)=withContext(Dispatchers.IO) {synchronized(lock) {
        val items=read();require(items.size<100);val item=items.first {it.id==id}
        write(items+item.copy(id=UUID.randomUUID().toString()))
    }}
    suspend fun complete(id:String)=withContext(Dispatchers.IO) {synchronized(lock) {
        val old=read();val remaining=old.filter {it.id!=id};write(remaining)
        val retained=remaining.flatMap {listOfNotNull(it.frontPath,it.backPath)}.toSet()
        old.filter {it.id==id}.flatMap {listOfNotNull(it.frontPath,it.backPath)}.filterNot {it in retained}.forEach {File(it).delete()}
    }}
}

internal object IncomingImportPolicy {
    fun allowsExternal(scheme:String?,authority:String?,ownAuthority:String):Boolean =
        scheme.equals("content",ignoreCase=true) && !authority.isNullOrBlank() &&
            authority.matches(Regex("[A-Za-z0-9_.-]+")) && !authority.equals(ownAuthority,ignoreCase=true)
}

/** Incoming URI grants are consumed immediately into private staging, not retained in public paths. */
object IncomingImports {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val changes=MutableStateFlow(0L)
    val revisions=changes.asStateFlow()
    private val lock=Any()
    private val claimMutex=Mutex()
    private val issue=MutableStateFlow<String?>(null)
    val error=issue.asStateFlow()
    fun clearError() {issue.value=null}
    @Suppress("DEPRECATION")
    fun accept(context:Context,intent:Intent,owner:String?,onReady:()->Unit) {
        val candidates=when(intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> return
        }.ifEmpty {intent.clipData?.let {clip->(0 until clip.itemCount).mapNotNull {clip.getItemAt(it).uri}}.orEmpty()}.take(24)
        val streams=candidates.filter {IncomingImportPolicy.allowsExternal(it.scheme,it.authority,"${context.packageName}.fileprovider")}
        val rejected=candidates.size!=streams.size
        if(streams.isEmpty()) {
            if(rejected) {issue.value="This attachment could not be imported. Share it from your photos or document app.";changes.value=System.currentTimeMillis();onReady()}
            return
        }
        scope.launch {
            issue.value=if(rejected) "Some attachments were rejected. Share images or PDFs from your photos or document app." else null
            synchronized(lock) {
                val root=File(context.filesDir,"incoming-imports").apply {mkdirs()}
                streams.forEach {uri->
                    val id=UUID.randomUUID().toString();val source=File(root,"$id.source")
                    runCatching {
                        require((root.listFiles()?.sumOf {it.length()} ?: 0)<128L*1024*1024)
                        copyBounded(context,uri,source)
                        writeJson(File(root,"$id.json"),JSONObject().put("owner",owner.orEmpty()).put("source",source.name))
                    }.onFailure {source.delete();issue.value="Some shared files could not be copied. Choose images or PDFs smaller than 40 MB, or clear space in the import queue."}
                }
            }
            changes.value=System.currentTimeMillis()
            withContext(Dispatchers.Main) {onReady()}
        }
    }
    suspend fun claim(context:Context,accountId:String,queue:ImportQueue)=withContext(Dispatchers.IO+NonCancellable) {
        claimMutex.withLock {
        val root=File(context.filesDir,"incoming-imports")
        val manifests=synchronized(lock) {root.listFiles()?.filter {it.extension=="json"}.orEmpty()}
        for(manifest in manifests) {
            val data=runCatching {JSONObject(manifest.readText())}.getOrNull() ?: continue
            val owner=data.optString("owner")
            if(owner.isNotBlank() && owner!=accountId) continue
            val file=File(root,data.getString("source"))
            if(file.canonicalFile.parentFile!=root.canonicalFile) continue
            queue.import(listOf(Uri.fromFile(file)))
            synchronized(lock) {manifest.delete();file.delete()}
        }
        }
    }
}
