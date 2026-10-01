package com.thotapalli.visidock

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** Root starts its normal front/back crop flow; the queue is acknowledged only after successful save. */
@Composable fun ImportWorkspace(accountId:String,onImportPair:(BatchImportItem)->Unit,onQrContact:(Card)->Unit,onClose:()->Unit) {
    val context=LocalContext.current
    val queue=remember(accountId) {ImportQueue(context,accountId)}
    val scope=rememberCoroutineScope()
    var entries by remember(accountId) {mutableStateOf<List<BatchImportItem>>(emptyList())}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var payload by remember {mutableStateOf<List<QrPayload>>(emptyList())}
    var batchCamera by rememberSaveable(accountId) {mutableStateOf(false)}
    var shot by remember {mutableStateOf<File?>(null)}
    val revision by IncomingImports.revisions.collectAsState()
    val stagingError by IncomingImports.error.collectAsState()
    suspend fun refresh() {entries=queue.items()}
    fun run(action:suspend ()->Unit) {if(!busy) {busy=true;error=null;scope.launch {
        try {action();refresh()} catch(e:CancellationException) {throw e} catch(e:Exception) {batchCamera=false;error=e.message ?: "The import could not be completed."} finally {busy=false}
    }}}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {uris->if(uris.isNotEmpty()) run {queue.import(uris)}}
    LaunchedEffect(accountId,revision) {busy=true
        try {IncomingImports.claim(context,accountId,queue);refresh()} catch(e:CancellationException) {throw e} catch(e:Exception) {error=e.message ?: "The incoming document could not be opened."} finally {busy=false}
    }
    if(batchCamera) {
        CaptureScreen(back=false,file={File(context.cacheDir,"camera").apply {mkdirs()}.let {File(it,"batch-${UUID.randomUUID()}.jpg")}.also {shot=it}},
            onResult={success->if(success) shot?.let {captured->run {queue.import(listOf(Uri.fromFile(captured)));captured.delete()}}},
            onClose={batchCamera=false},batchMode=true,handoffBusy=busy,capturedCount=entries.size)
        return
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=20.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("Import queue",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall)
            DockTextButton(onClose,enabled=!busy) {Text("Close")}
        }
        Text("Pair front and back before review. Your queue stays on this device until you save or remove it.",style=MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            DockButton({batchCamera=true},enabled=!busy) {Text("Batch camera")}
            DockOutlinedButton({picker.launch(arrayOf("image/*","application/pdf"))},enabled=!busy) {Text("Images or PDF")}
        }
        if(busy) {LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical=12.dp));Text("Preparing documents…")}
        (error ?: stagingError)?.let {message->Row(verticalAlignment=Alignment.CenterVertically) {
            Text(message,color=MaterialTheme.colorScheme.error,modifier=Modifier.weight(1f).padding(vertical=8.dp))
            DockTextButton({error=null;IncomingImports.clearError()}) {Text("Dismiss")}
        }}
        if(entries.isEmpty() && !busy) Text("Images and PDF pages appear here. You can also share them to VisiDock from another app.",Modifier.padding(vertical=24.dp))
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(vertical=16.dp)) {
            itemsIndexed(entries,key={_,item->item.id}) {index,item->
                Surface(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("Card ${index+1}"+if(item.backPath!=null) " · both sides" else " · front only",style=MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            ImportThumbnail(item.frontPath,"Front",Modifier.weight(1f))
                            item.backPath?.let {ImportThumbnail(it,"Back",Modifier.weight(1f))}
                        }
                        DockButton({onImportPair(item)},enabled=!busy) {Text("Crop and review")}
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            if(item.backPath!=null) DockTextButton({run {queue.separate(item.id)}},enabled=!busy) {Text("Separate sides")}
                            else if(entries.getOrNull(index+1)?.backPath==null && index+1<entries.size) DockTextButton({run {queue.pair(item.id,entries[index+1].id)}},enabled=!busy) {Text("Next image is back")}
                            DockTextButton({run {queue.complete(item.id)}},enabled=!busy) {Text("Remove")}
                        }
                        DockTextButton({run {queue.duplicateSource(item.id)}},enabled=!busy) {Text("Crop another card from this image")}
                        DockTextButton({run {payload=QrContacts.decode(File(item.frontPath));if(payload.isEmpty()) error="No readable QR code was found in this image."}},enabled=!busy) {Text("Read QR code")}
                    }
                }
            }
        }
    }
    payload.firstOrNull()?.let {decoded->QrPayloadPreview(decoded,onDismiss={payload=payload.drop(1)},onContact={card->payload=emptyList();onQrContact(card)})}
}

@Composable private fun ImportThumbnail(path:String,label:String,modifier:Modifier) {
    val photo by produceState<Bitmap?>(null,path) {value=withContext(Dispatchers.IO) {runCatching {ImageCropper.decode(File(path),500)}.getOrNull()}}
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(112.dp),contentAlignment=Alignment.Center) {photo?.let {Image(it.asImageBitmap(),label,Modifier.fillMaxSize(),contentScale=ContentScale.Fit)}}
        Text(label,style=MaterialTheme.typography.labelSmall)
    }
}

@Composable fun QrPayloadPreview(payload:QrPayload,onDismiss:()->Unit,onContact:(Card)->Unit) {
    val context=LocalContext.current
    AlertDialog(onDismissRequest=onDismiss,title={Text(when(payload) {is QrPayload.Contact->"Contact in QR code";is QrPayload.Link->"Website in QR code";else->"QR content"})},
        text={Text(when(payload) {
            is QrPayload.Contact->listOf(payload.card.name,payload.card.role,payload.card.company,payload.card.contactPhones.joinToString("\n") {it.number},payload.card.contactEmails.joinToString("\n"),payload.card.contactWebsites.joinToString("\n"),payload.card.address).filter(String::isNotBlank).joinToString("\n")
            is QrPayload.Link->payload.url
            is QrPayload.Text->payload.text.take(2000)
        })},confirmButton={when(payload) {
            is QrPayload.Contact->DockButton({onContact(payload.card)}) {Text("Review contact")}
            is QrPayload.Link->DockButton({runCatching {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(payload.url)))};onDismiss()}) {Text("Open website")}
            is QrPayload.Text->DockButton(onDismiss) {Text("Done")}
        }},dismissButton={if(payload !is QrPayload.Text) DockTextButton(onDismiss) {Text("Cancel")}})
}

/** Call only after choosing the same ShareFields shown in the sharing preview. */
@Composable fun ContactQrPreview(card:Card,fields:ShareFields=ShareFields(),onClose:()->Unit) {
    val context=LocalContext.current
    var error by remember {mutableStateOf<String?>(null)}
    val bitmap by produceState<Bitmap?>(null,card,fields) {value=withContext(Dispatchers.Default) {runCatching {QrContacts.encode(card,fields)}.onFailure {error=it.message}.getOrNull()}}
    val scope=rememberCoroutineScope()
    var sharing by remember {mutableStateOf(false)}
    AlertDialog(onDismissRequest=onClose,title={Text("Share contact QR")},text={Column {
        bitmap?.let {Image(it.asImageBitmap(),"Contact QR code",Modifier.fillMaxWidth().aspectRatio(1f))}
        Text(error ?: "Only the fields selected in the sharing preview are included.")
    }},confirmButton={DockButton({scope.launch {
        sharing=true
        try {
            val file=withContext(Dispatchers.IO) {File(context.cacheDir,"camera").apply {mkdirs()}.let {File(it,"contact-qr-${UUID.randomUUID()}.png")}.also {f->f.outputStream().use {check(bitmap!!.compress(Bitmap.CompressFormat.PNG,100,it))}}}
            val uri=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Share contact QR"))
        } catch(e:Exception) {error="The QR image could not be shared."} finally {sharing=false}
    }},enabled=bitmap!=null && !sharing) {Text("Share QR image")}},dismissButton={DockTextButton(onClose) {Text("Close")}})
}
