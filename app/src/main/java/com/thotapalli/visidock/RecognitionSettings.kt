package com.thotapalli.visidock

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun RecognitionSettings(onError:(String)->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var script by remember {mutableStateOf(RecognitionPreferences.script(context))}
    var diagnostics by remember {mutableStateOf(RecognitionPreferences.diagnosticsEnabled(context))}
    var expanded by remember {mutableStateOf(false)}
    val output=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri->
        if(uri!=null) scope.launch {runCatching {withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri,"wt")?.bufferedWriter()?.use {it.write(RecognitionDiagnostics.export(context))} ?: error("Could not open destination")
        }}.onFailure {onError("Diagnostics could not be saved.")}}
    }
    Text("Recognition",style=MaterialTheme.typography.titleLarge)
    Box {
        DockOutlinedButton(onClick={expanded=true}) {Text("Card script: ${script.name}")}
        DropdownMenu(expanded,{expanded=false}) {OcrScript.entries.forEach {value->DockDropdownMenuItem(text={Text(value.name)},onClick={script=value;RecognitionPreferences.setScript(context,value);expanded=false})}}
    }
    Text("Auto follows your device language. Choose the card’s script when it differs. Latin, Devanagari, Chinese, Japanese and Korean are supported; original text is retained.",style=MaterialTheme.typography.bodySmall)
    Row {Column(Modifier.weight(1f)) {Text("Private performance diagnostics");Text("Off by default. Keeps processing times on this device without card text or photos. Export only when you choose.",style=MaterialTheme.typography.bodySmall)};DockSwitch(diagnostics,{diagnostics=it;RecognitionPreferences.setDiagnosticsEnabled(context,it)})}
    if(diagnostics) DockTextButton(onClick={output.launch("VisiDock-diagnostics.json")}) {Text("Export diagnostics")}
}
