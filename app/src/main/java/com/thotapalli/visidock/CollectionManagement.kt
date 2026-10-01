package com.thotapalli.visidock

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable fun RecoveryAndSync(state:VaultState,vm:VaultViewModel) {
    var show by remember {mutableStateOf(false)}
    var purge by remember {mutableStateOf<Card?>(null)}
    Text("On this device",style=MaterialTheme.typography.titleLarge)
    Text(if(state.localVault.pendingCount==0) "No queued changes" else "${state.localVault.pendingCount} changes waiting to sync")
    state.localVault.lastSyncError?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    state.localVault.conflicts.forEach {conflict->
        Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.secondaryContainer) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Two versions of ${conflict.local.displayLabel}",style=MaterialTheme.typography.titleMedium)
            Text("This device: ${conflict.local.fields().filter(String::isNotBlank).joinToString(" · ")}")
            Text("Cloud: ${conflict.remote.fields().filter(String::isNotBlank).joinToString(" · ")}")
            DockButton(enabled=state.busy==null,onClick={vm.resolveConflict(conflict.cardId,true)}) {Text("Keep this device’s version")}
            DockOutlinedButton(enabled=state.busy==null,onClick={vm.resolveConflict(conflict.cardId,false)}) {Text("Use cloud version")}
        }}
    }
    DockOutlinedButton(onClick={show=true}) {Text("Recovery bin · ${state.localVault.deletedCards.size}")}
    if(show) AlertDialog(modifier=Modifier.dockModalArrival(),onDismissRequest={show=false},title={Text("Recovery bin")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(state.localVault.deletedCards.isEmpty()) Text("Removed cards will appear here until you delete them permanently.")
        state.localVault.deletedCards.forEach {card->Column {
            Text(card.displayLabel,style=MaterialTheme.typography.titleMedium)
            Row {DockTextButton(enabled=state.busy==null,onClick={vm.restoreCard(card)}) {Text("Restore")};DockTextButton(enabled=state.busy==null,onClick={purge=card}) {Text("Delete permanently")}}
        }}
    }},confirmButton={DockTextButton(onClick={show=false}) {Text("Done")}})
    purge?.let {card->AlertDialog(modifier=Modifier.dockModalArrival(),onDismissRequest={purge=null},title={Text("Permanently delete ${card.displayLabel}?")},text={Text("Its stored details and photographs will be removed. This cannot be undone.")},confirmButton={DockButton(onClick={vm.purgeCard(card);purge=null}) {Text("Delete permanently")}},dismissButton={DockTextButton(onClick={purge=null}) {Text("Cancel")}})}
}

@Composable fun CardHistoryAndNotes(card:Card,vm:VaultViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var history by remember(card.id) {mutableStateOf(false)}
    var note by remember(card.id) {mutableStateOf("")}
    var submittedNote by remember(card.id) {mutableStateOf<Pair<String,Long>?>(null)}
    LaunchedEffect(card.datedNotes) {submittedNote?.let {(text,at)->if(card.datedNotes.any {it.text==text && it.createdAt>=at}) {if(note==text) note="";submittedNote=null}}}
    var version by remember(card.id) {mutableStateOf<CardVersion?>(null)}
    var merge by remember(card.id) {mutableStateOf<Card?>(null)}
    Text("Meeting & timeline",style=MaterialTheme.typography.titleMedium)
    listOf(card.event,card.meetingDate,card.location).filter(String::isNotBlank).forEach {Text(it)}
    if(card.tags.isNotEmpty() || card.collections.isNotEmpty()) Text((card.tags+card.collections).distinct().joinToString(" · "),style=MaterialTheme.typography.labelLarge)
    card.datedNotes.sortedByDescending {it.createdAt}.forEach {entry->Column {
        Text(CardLogic.date(entry.createdAt),style=MaterialTheme.typography.labelSmall)
        Text(entry.text)
    }}
    DockOutlinedTextField(note,{note=it.take(4000)},Modifier.fillMaxWidth(),label={Text("Add a dated note")})
    DockTextButton(enabled=note.isNotBlank() && state.busy==null,onClick={submittedNote=note to System.currentTimeMillis();vm.addDatedNote(card,note)}) {Text("Save note")}
    DockTextButton(enabled=state.busy==null,onClick={history=true;vm.loadVersions(card)}) {Text("Version history")}
    val colleagues=state.cards.filter {it.id!=card.id && card.company.isNotBlank() && it.company.equals(card.company,true)}
    if(colleagues.isNotEmpty()) {
        Text("Also at ${card.company}",style=MaterialTheme.typography.titleMedium)
        colleagues.forEach {person->DockTextButton(onClick={vm.select(person)}) {Text(listOf(person.displayLabel,person.role,person.address).filter(String::isNotBlank).joinToString(" · "))}}
    }
    CardLogic.duplicates(card,state.cards).forEach {other->DockOutlinedButton(enabled=state.busy==null,onClick={merge=other}) {Text("Review possible duplicate: ${other.displayLabel}")}}
    if(history) AlertDialog(modifier=Modifier.dockModalArrival(),onDismissRequest={history=false},title={Text("Version history")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(state.versions.isEmpty()) Text(if(state.busy!=null) "Loading history…" else "Earlier saved versions will appear here.")
        state.versions.forEach {item->Column {Text(CardLogic.date(item.savedAt));Text(listOf(item.card.displayLabel,item.card.role,item.card.company).joinToString(" · "));DockTextButton(onClick={version=item}) {Text("Review this version")}}}
    }},confirmButton={DockTextButton(onClick={history=false}) {Text("Done")}})
    version?.let {item->AlertDialog(modifier=Modifier.dockModalArrival(),onDismissRequest={version=null},title={Text("Restore earlier details?")},text={Text(item.card.fields().filter(String::isNotBlank).joinToString("\n"))},confirmButton={DockButton(onClick={vm.restoreVersion(card,item);version=null}) {Text("Restore version")}},dismissButton={DockTextButton(onClick={version=null}) {Text("Cancel")}})}
    merge?.let {other->
        val proposal=runCatching {CardMerge.propose(card,other)}
        AlertDialog(modifier=Modifier.dockModalArrival(),onDismissRequest={merge=null},title={Text("Review merge")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) {
            Text("Keep ${card.displayLabel} as the primary card. Its existing identity and photographs remain; missing fields and contact channels are combined. ${other.displayLabel} moves to recovery.")
            Text("Only merge if these records describe the same person. A shared office number is not enough.")
            Text(proposal.getOrNull()?.fields()?.filter(String::isNotBlank)?.joinToString("\n") ?: proposal.exceptionOrNull()?.message.orEmpty())
        }},confirmButton={DockButton(enabled=proposal.isSuccess && state.busy==null,onClick={vm.mergeCards(card,other);merge=null}) {Text("Merge these records")}},dismissButton={DockTextButton(onClick={merge=null}) {Text("Keep separate")}})
    }
}
