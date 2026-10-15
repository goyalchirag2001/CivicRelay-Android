package org.civicrelay.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.civicrelay.app.CivicService
import org.civicrelay.app.SmsContactPolicy

/** Phone numbers stay in the existing encrypted vault. No permission to read the device address book. */
@Composable
fun SmsContactsScreen(civic: CivicService, onUseForSms: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val revision = civic.revision // Compose state invalidation after save/delete
    var name by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var permission by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    val saved = remember(revision) { civic.vault.smsContacts() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Parents & SMS contacts", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)
        Text("Add a parent's or trusted person's phone number here. It stays encrypted in your vault. " +
            "You can create a message draft from the SOS screen; your SMS app must send it using a cellular network.")
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Save a trusted number", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(value = name, onValueChange = { name = it.take(48) },
                    label = { Text("Name (for example, Mum)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = number, onValueChange = { number = it.take(30) },
                    label = { Text("Phone number (include +91 if applicable)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Row {
                    Checkbox(checked = permission, onCheckedChange = { permission = it })
                    Text("I have permission to save and contact this person",
                        modifier = Modifier.padding(top = 11.dp), style = MaterialTheme.typography.bodySmall)
                }
                Button(modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && name.isNotBlank() && number.isNotBlank() && permission,
                    onClick = {
                        val contactName = name.trim()
                        val phone = try { SmsContactPolicy.normalize(number) }
                        catch (e: IllegalArgumentException) {
                            feedback = e.message ?: "Check the phone number"; return@Button
                        }
                        busy = true; feedback = "Saving contact…"
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { civic.addSmsContact(contactName, phone) }
                                name = ""; number = ""; permission = false
                                feedback = "Saved $contactName. Use it from the SOS tab to open an SMS draft."
                            } catch (e: Exception) {
                                feedback = "Could not save: ${e.localizedMessage ?: "Please try again"}"
                            } finally { busy = false }
                        }
                    }) { Text(if (busy) "Saving…" else "Save phone number") }
                if (feedback.isNotBlank()) Text(feedback, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Saved numbers (${saved.size}/10)", style = MaterialTheme.typography.titleMedium)
        if (saved.isEmpty()) Text("No numbers saved yet.", style = MaterialTheme.typography.bodySmall)
        saved.forEach { (alias, phone) ->
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(alias, fontWeight = FontWeight.Bold)
                    Text(phone)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onUseForSms(phone) }, enabled = !busy) { Text("Draft SMS") }
                        OutlinedButton(onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { civic.removeSmsContact(phone) }
                                    feedback = "Contact removed"
                                } catch (e: Exception) {
                                    feedback = "Could not remove contact: ${e.localizedMessage}"
                                } finally { busy = false }
                            }
                        }, enabled = !busy) { Text("Remove") }
                    }
                }
            }
        }
        Text("SMS is not encrypted end-to-end. It requires a working carrier network, " +
            "and the app does not secretly send messages or share your live location.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
