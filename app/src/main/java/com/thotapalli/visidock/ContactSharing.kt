package com.thotapalli.visidock

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun ContactSharing(card:Card,vm:VaultViewModel) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var open by remember(card.id) {mutableStateOf(false)}
    var qr by remember(card.id) {mutableStateOf(false)}
    var fields by remember(card.id) {mutableStateOf(ShareFields())}
    var busy by remember {mutableStateOf(false)}
    fun share(photo:Boolean,back:Boolean=false) {scope.launch {
        busy=true
        try {
            val output=withContext(Dispatchers.IO) {
                val directory=File(context.cacheDir,"camera").apply {mkdirs()}
                val file=File(directory,"share-${UUID.randomUUID()}.${if(photo) "jpg" else "vcf"}")
                if(photo) file.writeBytes(vm.photo(card,back) ?: error("This photograph is not available on this device yet."))
                else file.writeText(ContactExport.vcard(card,fields))
                file
            }
            val uri=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",output)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(if(photo) "image/jpeg" else "text/vcard").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Share ${card.displayLabel}"))
        } catch(e:Exception) {vm.report(e.message ?: "Sharing could not be opened.")}
        finally {busy=false}
    }}
    DockOutlinedButton(onClick={open=true}) {Text("Share card")}
    if(open) AlertDialog(modifier=Modifier.dockModalArrival(),onDismissRequest={if(!busy) open=false},title={Text("Choose what to share")},text={Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(card.displayLabel,style=MaterialTheme.typography.titleMedium)
        ShareFieldControls(fields) {fields=it}
        DockButton(onClick={qr=true},enabled=!busy) {Text("Show contact QR")}
        HorizontalDivider()
        Text("Photographs include everything printed on that side, regardless of the field choices above.",style=MaterialTheme.typography.bodySmall)
        if(card.hasFrontImage) DockOutlinedButton(onClick={share(true)},enabled=!busy) {Text("Share front photograph")}
        if(card.hasBackImage) DockOutlinedButton(onClick={share(true,true)},enabled=!busy) {Text("Share back photograph")}
    }},confirmButton={DockButton(onClick={share(false)},enabled=!busy) {Text("Share contact file")}},dismissButton={DockTextButton(onClick={open=false},enabled=!busy) {Text("Close")}})
    if(qr) ContactQrPreview(card,fields) {qr=false}
}
