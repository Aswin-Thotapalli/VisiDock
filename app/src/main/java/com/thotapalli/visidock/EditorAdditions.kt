package com.thotapalli.visidock

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun editableChannels(primary:String,values:List<String>):List<String> = when {
    values.isEmpty()->listOf(primary)
    primary.isNotBlank() && values.none {it.trim().equals(primary.trim(),true)}->listOf(primary)+values
    else->values
}

@Composable fun RepeatedFieldEvidence(state:VaultState,prefix:String) {
    (state.fieldSources.map {it.field}+state.fieldIssues.map {it.field}).filter {it.startsWith("$prefix.")}.distinct().forEach {FieldEvidence(state,it)}
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun ChannelEditor(label:String,values:List<String>,busy:Boolean,validationShown:Boolean=false,focusAttempt:Int=0,requestedField:String?=null,onChange:(List<String>)->Unit) {
    val rows=values.ifEmpty {listOf("")}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.dockReflow()) {
        rows.forEachIndexed {index,value->
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                val invalid=value.isNotBlank() && if(label=="Email") ContactChannels.email(value)!=value.trim() else ContactChannels.website(value)!=value.trim()
                val request=remember {FocusRequester()}
                val bring=remember {BringIntoViewRequester()}
                val fieldLabel="$label ${index+1}"
                LaunchedEffect(focusAttempt) {if(focusAttempt>0 && requestedField==fieldLabel) {request.requestFocus();bring.bringIntoView()}}
                DockOutlinedTextField(value,{text->onChange(rows.toMutableList().also {it[index]=text.take(300)})},Modifier.weight(1f).focusRequester(request).bringIntoViewRequester(bring),enabled=!busy,label={Text(fieldLabel)},singleLine=true,isError=invalid,supportingText={
                    AnimatedVisibility(validationShown && invalid) {Text(if(label=="Email") "Check the email address, including @ and its domain." else "Enter a website here; email addresses belong above.")}
                },keyboardOptions=KeyboardOptions(keyboardType=if(label=="Email") KeyboardType.Email else KeyboardType.Uri))
                if(rows.size>1) DockTextButton(enabled=!busy,onClick={onChange(rows.filterIndexed {i,_->i!=index})}) {Text("Remove")}
            }
        }
        DockTextButton(enabled=!busy && rows.size<12,onClick={onChange(rows+"")}) {Text("Add another ${label.lowercase()}")}
    }
}

@Composable fun CardOrganizationEditor(card:Card,busy:Boolean,onChange:(Card)->Unit) {
    var expanded by remember(card.id) {mutableStateOf(false)}
    var tagsText by remember(card.id) {mutableStateOf(card.tags.joinToString(", "))}
    var collectionsText by remember(card.id) {mutableStateOf(card.collections.joinToString(", "))}
    DockTextButton(onClick={expanded=!expanded}) {Text(if(expanded) "Hide organization and meeting details" else "Organization and meeting details")}
    AnimatedVisibility(expanded) {Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        DockOutlinedTextField(tagsText,{tagsText=it;onChange(card.copy(tags=it.split(',').map(String::trim).filter(String::isNotBlank).distinct().take(24)))},Modifier.fillMaxWidth(),enabled=!busy,label={Text("Tags, separated by commas")})
        DockOutlinedTextField(collectionsText,{collectionsText=it;onChange(card.copy(collections=it.split(',').map(String::trim).filter(String::isNotBlank).distinct().take(24)))},Modifier.fillMaxWidth(),enabled=!busy,label={Text("Collections, separated by commas")})
        DockOutlinedTextField(card.event,{onChange(card.copy(event=it.take(300)))},Modifier.fillMaxWidth(),enabled=!busy,label={Text("Where you met · event")})
        DockOutlinedTextField(card.meetingDate,{onChange(card.copy(meetingDate=it.take(10)))},Modifier.fillMaxWidth(),enabled=!busy,label={Text("Meeting date · YYYY-MM-DD")})
        DockOutlinedTextField(card.location,{onChange(card.copy(location=it.take(500)))},Modifier.fillMaxWidth(),enabled=!busy,label={Text("Meeting location · optional")})
        DockOutlinedTextField(card.transliteratedName,{onChange(card.copy(transliteratedName=it.take(200)))},Modifier.fillMaxWidth(),enabled=!busy,label={Text("Transliterated name · optional")})
        if(android.os.Build.VERSION.SDK_INT>=29) DockTextButton(enabled=!busy && card.name.isNotBlank(),onClick={
            val suggestion=runCatching {android.icu.text.Transliterator.getInstance("Any-Latin; Latin-ASCII").transliterate(card.name)}.getOrDefault(card.name)
            onChange(card.copy(transliteratedName=suggestion.take(200)))
        }) {Text("Suggest Latin spelling · keeps original name")}
        Row {DockCheckbox(card.isOwnCard,{onChange(card.copy(isOwnCard=it))},enabled=!busy);Text("This is my own card",Modifier.padding(top=12.dp))}
    }}
}

@Composable fun FieldEvidence(state:VaultState,field:String) {
    val sources=state.fieldSources.filter {it.contactIndex==state.sourceContactIndex && it.field==field}
    val issues=state.fieldIssues.filter {it.contactIndex==state.sourceContactIndex && it.field==field}
    var open by remember(state.draft?.id,field) {mutableStateOf(false)}
    issues.forEach {Text(it.reason,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
    if(sources.isNotEmpty()) DockTextButton(onClick={open=true}) {Text("Show $field on the card")}
    if(open) AlertDialog(modifier=Modifier.dockModalArrival(),onDismissRequest={open=false},title={Text("Source for $field")},text={
        Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Highlighted text supports this suggestion. Check that it belongs to this person.")
            sources.flatMap {it.regions}.groupBy {it.side}.forEach {(side,regions)->
                val path=if(side==1) state.draftBackPreview else state.draftPreview
                val bitmap by produceState<android.graphics.Bitmap?>(null,path) {value=withContext(Dispatchers.IO) {path?.let {BitmapFactory.decodeFile(it)}}}
                Text(if(side==1) "Back" else "Front",style=MaterialTheme.typography.labelLarge)
                val tint=MaterialTheme.colorScheme.primary
                bitmap?.let {photo->Box(Modifier.fillMaxWidth().aspectRatio(photo.width.toFloat()/photo.height)) {
                    Image(photo.asImageBitmap(),"Card source",Modifier.fillMaxSize())
                    Canvas(Modifier.fillMaxSize()) {regions.forEach {r->drawRect(tint,Offset(r.left*size.width,r.top*size.height),Size((r.right-r.left)*size.width,(r.bottom-r.top)*size.height),style=Stroke(2.dp.toPx()))}}
                }}
                regions.forEach {Text(it.text,style=MaterialTheme.typography.bodyMedium)}
            }
        }
    },confirmButton={DockTextButton(onClick={open=false}) {Text("Done")}})
}
