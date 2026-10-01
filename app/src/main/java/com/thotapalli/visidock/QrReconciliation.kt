package com.thotapalli.visidock

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

enum class QrField(val label:String) { NAME("Name"), ROLE("Job title"), COMPANY("Company"), ADDRESS("Address"), PHONES("Phone numbers"), EMAILS("Email addresses"), WEBSITES("Websites") }
data class QrDifference(val field:QrField,val current:String,val proposed:String)

/** A QR is independent evidence: never infer that two people printed on one card are the same person. */
object QrReconciliation {
    private fun value(card:Card,field:QrField):String=when(field) {
        QrField.NAME->card.name;QrField.ROLE->card.role;QrField.COMPANY->card.company;QrField.ADDRESS->card.address
        QrField.PHONES->card.contactPhones.joinToString("\n") {listOf(it.label,it.number).filter(String::isNotBlank).joinToString(": ")}
        QrField.EMAILS->card.contactEmails.joinToString("\n");QrField.WEBSITES->card.contactWebsites.joinToString("\n")
    }
    fun differences(current:Card,qr:Card):List<QrDifference> = QrField.entries.mapNotNull {field->
        val proposed=value(qr,field).trim();val existing=value(current,field).trim()
        if(proposed.isBlank() || proposed==existing) null else QrDifference(field,existing,proposed)
    }
    /** Empty selection is a strict no-op. Repeated channels retain existing entries and primary values. */
    fun apply(current:Card,qr:Card,selected:Set<QrField>,samePersonConfirmed:Boolean):Card {
        if(selected.isEmpty()) return current
        require(samePersonConfirmed) {"Confirm this QR belongs to the person you are reviewing."}
        var result=current
        selected.forEach {field->
            require(value(qr,field).isNotBlank()) {"The QR has no ${field.label.lowercase(Locale.ROOT)} to apply."}
            result=when(field) {
                QrField.NAME->result.copy(name=qr.name.trim())
                QrField.ROLE->result.copy(role=qr.role.trim())
                QrField.COMPANY->result.copy(company=qr.company.trim())
                QrField.ADDRESS->result.copy(address=qr.address.trim())
                QrField.PHONES->{
                    val phones=(result.contactPhones+qr.contactPhones).distinctBy {it.number.filter(Char::isDigit).ifBlank {it.number}}
                    require(phones.size<=12 && phones.all {it.number.length<=80 && it.label.length<=40 && it.number.count(Char::isDigit) in 7..15 && splitPhoneNumbers(it.number).size==1}) {"Check the QR phone numbers; each must be a separate valid number, with at most 12 total."}
                    result.copy(phone=result.phone.ifBlank {phones.firstOrNull()?.number.orEmpty()},phones=phones)
                }
                QrField.EMAILS->{
                    val emails=(result.contactEmails+qr.contactEmails).distinctBy {it.lowercase(Locale.ROOT)}
                    require(emails.size<=12 && emails.all {it.length<=300 && ContactChannels.email(it)==it}) {"The combined contact must have at most 12 valid email addresses."}
                    result.copy(email=result.email.ifBlank {emails.firstOrNull().orEmpty()},emails=emails)
                }
                QrField.WEBSITES->{
                    val websites=(result.contactWebsites+qr.contactWebsites).distinctBy {it.lowercase(Locale.ROOT)}
                    require(websites.size<=12 && websites.all {it.length<=300 && ContactChannels.website(it)==it}) {"The combined contact must have at most 12 valid websites."}
                    result.copy(website=result.website.ifBlank {websites.firstOrNull().orEmpty()},websites=websites)
                }
            }
            val limit=when(field) {QrField.NAME->200;QrField.ROLE,QrField.COMPANY->300;QrField.ADDRESS->1000;else->Int.MAX_VALUE}
            require(value(qr,field).length<=limit) {"The QR ${field.label.lowercase(Locale.ROOT)} is too long. Copy a shorter value manually."}
        }
        return result
    }
}

/** Deliberately outside the scanning pipeline: only local cropped photographs are read on request. */
@Composable fun QrEvidenceReview(card:Card,frontPath:String?,backPath:String?,onChange:(Card)->Unit) {
    val latestCard by rememberUpdatedState(card)
    val latestChange by rememberUpdatedState(onChange)
    key(card.id,frontPath,backPath) {
        val scope=rememberCoroutineScope()
        var checking by remember {mutableStateOf(false)}
        var payloads by remember {mutableStateOf<List<QrPayload>>(emptyList())}
        var checked by remember {mutableStateOf(false)}
        var chosen by remember {mutableStateOf<Int?>(null)}
        var selected by remember {mutableStateOf<Set<QrField>>(emptySet())}
        var confirmed by remember {mutableStateOf(false)}
        var message by remember {mutableStateOf<String?>(null)}
        Column(Modifier.fillMaxWidth().dockReflow(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            DockOutlinedButton(enabled=!checking && (frontPath!=null || backPath!=null),onClick={
                checking=true;message=null;chosen=null;selected=emptySet();confirmed=false
                scope.launch {
                    try {
                        val files=listOfNotNull(frontPath,backPath).distinct().map(::File).filter(File::isFile)
                        require(files.isNotEmpty()) {"This card photograph is not available on this device yet."}
                        payloads=files.flatMap {QrContacts.decode(it)}.distinct().take(12);checked=true
                    } catch(cancelled:CancellationException) {throw cancelled}
                    catch(error:Exception) {message=error.message ?: "Could not read this QR. You can still edit the details manually."}
                    finally {checking=false}
                }
            }) {Text(if(checking) "Reading QR on this device…" else "Check QR on card")}
            AnimatedVisibility(checked && !checking) {
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(if(payloads.isEmpty()) "No readable QR found in these cropped photographs." else "QR details are separate evidence. Choose a contact to compare; nothing changes automatically.",style=MaterialTheme.typography.bodySmall)
                    payloads.forEachIndexed {index,payload->when(payload) {
                        is QrPayload.Contact->DockOutlinedButton(onClick={chosen=index;selected=emptySet();confirmed=false;message=null}) {Text("Compare ${payload.card.displayLabel.ifBlank {"QR contact ${index+1}"}.take(100)}")}
                        is QrPayload.Link->Text("QR link · ${payload.url.take(1000)}",style=MaterialTheme.typography.bodySmall)
                        is QrPayload.Text->Text("QR text · ${payload.text.take(1000)}",style=MaterialTheme.typography.bodySmall)
                    }}
                    val qr=(chosen?.let {payloads.getOrNull(it)} as? QrPayload.Contact)?.card
                    if(qr!=null) {
                        val differences=QrReconciliation.differences(card,qr)
                        if(differences.isEmpty()) Text("These QR details already match this contact.")
                        else {
                            Text("Reviewing: ${card.displayLabel.ifBlank {"Unnamed contact"}}",style=MaterialTheme.typography.titleSmall)
                            Text("Selected name, title, company or address replaces that field. Selected phone numbers, emails and websites are added without removing your existing entries.",style=MaterialTheme.typography.bodySmall)
                            differences.forEach {difference->
                                DockSelectionRow(label=difference.field.label,checked=difference.field in selected,onCheckedChange={yes->selected=if(yes) selected+difference.field else selected-difference.field},
                                    supportingText="Current: ${difference.current.ifBlank {"Empty"}.take(1000)}\nQR: ${difference.proposed.take(1000)}")
                            }
                            DockSelectionRow(label="This QR belongs to the person I am reviewing",checked=confirmed,onCheckedChange={confirmed=it},supportingText="For a card with several people, compare one person at a time.")
                            DockButton(enabled=confirmed && selected.isNotEmpty(),onClick={
                                try {
                                    latestChange(QrReconciliation.apply(latestCard,qr,selected,confirmed))
                                    selected=emptySet();confirmed=false;message="Selected QR details added to your review. Check the fields before saving."
                                } catch(error:IllegalArgumentException) {message=error.message}
                            }) {Text("Use selected QR details")}
                        }
                    }
                }
            }
            message?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
        }
    }
}
