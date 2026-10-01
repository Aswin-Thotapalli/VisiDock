package com.thotapalli.visidock

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable fun CollectionTools(state:VaultState,vm:VaultViewModel,onOwnCamera:()->Unit,onOwnImport:()->Unit) {
    var transfer by remember {mutableStateOf(false)}
    var backup by remember {mutableStateOf(false)}
    var restore by remember {mutableStateOf(false)}
    var organize by remember {mutableStateOf(false)}
    var tag by remember {mutableStateOf("")}
    var collection by remember {mutableStateOf("")}
    var password by remember {mutableStateOf("")}
    var format by rememberSaveable {mutableStateOf("vcf")}
    var pendingUri by rememberSaveable {mutableStateOf<String?>(null)}
    var fields by rememberSaveable(stateSaver=Saver<ShareFields,List<Boolean>>(save={listOf(it.phones,it.emails,it.websites,it.company,it.address,it.notes)},restore={ShareFields(it[0],it[1],it[2],it[3],it[4],it[5])})) {mutableStateOf(ShareFields())}
    var selection by rememberSaveable(stateSaver=Saver<Set<String>,ArrayList<String>>(save={ArrayList(it)},restore={it.toSet()})) {mutableStateOf(state.cards.map {it.id}.toSet())}
    val exportTarget=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {uri->
        if(uri!=null) vm.exportCards(uri,format,fields,selection)
        transfer=false
    }
    val backupTarget=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {uri->
        if(uri!=null && password.length<10) {pendingUri=uri.toString();backup=true;restore=false}
        else {if(uri!=null) vm.backup(uri,password.toCharArray());password="";backup=false}
    }
    val restoreSource=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        if(uri!=null && password.length<10) {pendingUri=uri.toString();restore=true;backup=false}
        else {if(uri!=null) vm.restoreBackup(uri,password.toCharArray());password="";restore=false}
    }
    Text("My card",style=MaterialTheme.typography.titleLarge)
    Text("Add your own visiting card to keep its photograph and share the details you choose.")
    state.cards.filter {it.isOwnCard}.forEach {card->DockOutlinedButton(onClick={vm.select(card)},enabled=state.busy==null) {Text(card.displayLabel)}}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        DockButton(onClick=onOwnCamera,enabled=state.busy==null) {Text("Scan mine")}
        DockOutlinedButton(onClick=onOwnImport,enabled=state.busy==null) {Text("Import photo")}
    }
    DockTextButton(onClick=vm::newOwnCard,enabled=state.busy==null) {Text("Enter my details")}
    HorizontalDivider()
    Text("Take your collection with you",style=MaterialTheme.typography.titleLarge)
    DockOutlinedButton(onClick={transfer=true},enabled=state.busy==null && state.cards.isNotEmpty()) {Text("Export selected contacts")}
    DockOutlinedButton(onClick={backup=true},enabled=state.busy==null) {Text("Encrypted backup")}
    DockTextButton(onClick={restore=true},enabled=state.busy==null) {Text("Restore a backup")}
    DockOutlinedButton(onClick={organize=true},enabled=state.busy==null && state.cards.isNotEmpty()) {Text("Organize multiple cards")}
    if(organize) AlertDialog(modifier=Modifier.dockModalArrival(),onDismissRequest={organize=false},title={Text("Organize cards")},text={Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        DockOutlinedTextField(tag,{tag=it.take(80)},label={Text("Add tag · optional")})
        DockOutlinedTextField(collection,{collection=it.take(80)},label={Text("Add to collection · optional")})
        state.cards.forEach {card->Row {DockCheckbox(card.id in selection,{checked->selection=if(checked) selection+card.id else selection-card.id});Text(card.displayLabel,Modifier.padding(top=12.dp))}}
    }},confirmButton={DockButton(enabled=selection.isNotEmpty() && (tag.isNotBlank() || collection.isNotBlank()),onClick={vm.organize(selection,tag.trim(),collection.trim());organize=false}) {Text("Apply")}},dismissButton={DockTextButton(onClick={organize=false}) {Text("Cancel")}})
    if(transfer) AlertDialog(modifier=Modifier.dockModalArrival(),onDismissRequest={transfer=false},title={Text("Export contacts")},text={
        Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row {DockFilterChip(format=="vcf",{format="vcf"},label={Text("vCard")});Spacer(Modifier.width(8.dp));DockFilterChip(format=="csv",{format="csv"},label={Text("CSV")})}
            DockFilterChip(format=="zip",{format="zip"},label={Text("Photos + CSV archive")})
            if(format=="zip") Text("Photographs include every printed detail, regardless of selected CSV fields.",style=MaterialTheme.typography.bodySmall)
            ShareFieldControls(fields) {fields=it}
            HorizontalDivider()
            Row {DockTextButton(onClick={selection=state.cards.map {it.id}.toSet()}) {Text("Select all")};DockTextButton(onClick={selection=emptySet()}) {Text("Clear")}}
            state.cards.forEach {card->Row {DockCheckbox(card.id in selection,{checked->selection=if(checked) selection+card.id else selection-card.id});Text(card.displayLabel,Modifier.padding(top=12.dp))}}
        }
    },confirmButton={DockButton(enabled=selection.isNotEmpty(),onClick={exportTarget.launch("VisiDock.$format")}) {Text("Choose destination")}},dismissButton={DockTextButton(onClick={transfer=false}) {Text("Cancel")}})
    if(backup || restore) AlertDialog(modifier=Modifier.dockModalArrival(),onDismissRequest={backup=false;restore=false;password="";pendingUri=null},title={Text(if(restore) "Restore encrypted backup" else "Protect your backup")},text={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(if(restore) "Enter the backup password. Restored cards are added as new entries; existing cards remain available." else "Includes card details, notes, organization and photographs. Keep the password somewhere safe: VisiDock cannot recover it.")
            DockOutlinedTextField(password,{password=it},label={Text("Backup password")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
        }
    },confirmButton={DockButton(enabled=password.length>=10,onClick={
        val destination=pendingUri
        if(destination!=null) {
            if(restore) vm.restoreBackup(android.net.Uri.parse(destination),password.toCharArray()) else vm.backup(android.net.Uri.parse(destination),password.toCharArray())
            pendingUri=null;password="";backup=false;restore=false
        } else if(restore) restoreSource.launch(arrayOf("application/octet-stream","*/*")) else backupTarget.launch("VisiDock-backup.vdb")
    }) {Text(if(pendingUri!=null) "Continue" else if(restore) "Choose backup" else "Choose destination")}},dismissButton={DockTextButton(onClick={backup=false;restore=false;password="";pendingUri=null}) {Text("Cancel")}})
}

@Composable fun ShareFieldControls(fields:ShareFields,onChange:(ShareFields)->Unit) {
    val rows=listOf("Phone numbers" to fields.phones,"Email addresses" to fields.emails,"Websites" to fields.websites,"Company and title" to fields.company,"Address" to fields.address,"Private notes" to fields.notes)
    rows.forEachIndexed {index,(label,checked)->Row {
        DockCheckbox(checked,{value->onChange(when(index) {0->fields.copy(phones=value);1->fields.copy(emails=value);2->fields.copy(websites=value);3->fields.copy(company=value);4->fields.copy(address=value);else->fields.copy(notes=value)})})
        Text(label,Modifier.padding(top=12.dp))
    }}
}
