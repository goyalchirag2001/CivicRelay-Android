package org.civicrelay.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.location.Location
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.civicrelay.app.crypto.Crypto
import org.civicrelay.app.media.CameraSession
import org.civicrelay.app.media.ContactQr
import org.civicrelay.app.media.ContactScanner
import org.civicrelay.app.media.VerifiedPhotoPreview
import org.civicrelay.app.media.VerifiedVideoPreview
import org.civicrelay.app.ui.CivicTheme
import org.civicrelay.app.storage.VerifiedEvidence
import org.civicrelay.app.storage.EvidenceViewPolicy
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private lateinit var civic: CivicService
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        civic = CivicService(applicationContext)
        // Remove orphaned plaintext captures from interrupted prior sessions (not secure erasure).
        File(cacheDir, "capture").listFiles()?.forEach { it.delete() }
        cacheDir.listFiles()?.filter { it.name.endsWith(".working-crv") || it.name.endsWith(".crb") }
            ?.forEach { it.delete() }
        setContent {
            CivicTheme { CivicScreen(civic, this) }
        }
    }
    override fun onStop() {
        // Emergency prototype prioritizes locking over background operation.
        // Recording interrupted by backgrounding may be lost.
        civic.lock()
        File(cacheDir, "capture").listFiles()?.forEach { it.delete() }
        super.onStop()
    }
    override fun onDestroy() { civic.shutdown(); super.onDestroy() }
}

private fun whenText(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    .format(Date(millis))

@Composable private fun CivicScreen(civic: CivicService, activity: MainActivity) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val revision = civic.revision // track vault changes across screens
    var passphrase by remember { mutableStateOf("") }
    var confirmPass by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf("SOS") }
    var exportPath by remember { mutableStateOf<String?>(null) }
    var plainExportRequestedId by remember { mutableStateOf<String?>(null) }
    var pendingPlainExport by remember { mutableStateOf<Pair<String, Uri>?>(null) }
    val plainPhotoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        val id = plainExportRequestedId
        plainExportRequestedId = null
        if (id != null && uri != null) pendingPlainExport = id to uri
    }
    val plainVideoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val id = plainExportRequestedId
        plainExportRequestedId = null
        if (id != null && uri != null) pendingPlainExport = id to uri
    }
    fun chooseDecryptedDestination(id: String, kind: String) {
        plainExportRequestedId = id
        val name = "civicrelay-export-${id.take(8)}${EvidenceViewPolicy.fileExtension(kind)}"
        if (kind == "photo") plainPhotoLauncher.launch(name) else plainVideoLauncher.launch(name)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val path = exportPath
        exportPath = null
        if (path != null) {
            if (uri == null) File(path).delete()
            else scope.launch {
                try {
                    withContext(Dispatchers.IO) { civic.savePreparedBundle(uri, File(path)) }
                } catch (e: Exception) { error = "Save failed: ${e.localizedMessage}" }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) { civic.stageEvidenceImport(uri) }
            } catch (e: Exception) { error = "Evidence selection failed: ${e.localizedMessage}" }
        }
    }
    fun exportEvidence(mediaId: String, contactKey: String) {
        scope.launch {
            busy = true
            error = ""
            try {
                val file = withContext(Dispatchers.IO) { civic.prepareEvidenceBundle(mediaId, contactKey) }
                exportPath = file.absolutePath
                exportLauncher.launch("civicrelay-encrypted-$mediaId.crb")
            } catch (e: Exception) { error = "Evidence export failed: ${e.localizedMessage}" }
            finally { busy = false }
        }
    }

    fun execute(action: () -> Unit) {
        scope.launch {
            busy = true; error = ""
            try { withContext(Dispatchers.IO) { action() } }
            catch (e: Exception) { error = e.message ?: "Operation failed" }
            finally { busy = false }
        }
    }

    if (!civic.vault.unlocked) {
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.surfaceVariant))).verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.Center) {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Text("CR", Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(20.dp))
            Text("CivicRelay", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text("Your evidence. Your trusted circle.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(20.dp))
            ElevatedCard(shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (civic.vault.exists) "Unlock private vault" else "Create your encrypted vault",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Stored locally • No central account", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(passphrase, { passphrase = it }, label = { Text("Vault passphrase") },
                        supportingText = { Text("At least 12 characters") },
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                        singleLine = true)
                    if (!civic.vault.exists) {
                        OutlinedTextField(confirmPass, { confirmPass = it }, label = { Text("Confirm passphrase") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                    Button(onClick = {
                        if (passphrase.length < 12 || (!civic.vault.exists && passphrase != confirmPass)) {
                            error = "Passphrase must have 12+ characters and both entries must match"
                        } else {
                            val entered = passphrase
                            passphrase = ""; confirmPass = ""
                            execute { check(civic.unlock(entered)) { "Vault could not be opened. Verify your passphrase." } }
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (civic.vault.exists) "Unlock vault →" else "Create secure vault →")
                    }
                    Text("No recovery service: forgetting your passphrase means losing access to stored data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("EXPERIMENTAL ALPHA • Not verified for emergency reliance",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.copyright_notice),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(shadowElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("CIVICRELAY", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(if (civic.mesh.peerCount > 0) "● ${civic.mesh.peerCount} nearby connected" else
                            "Private vault • Experimental alpha", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalButton(onClick = { civic.lock(); passphrase = ""; tab = "SOS" }) {
                        Text("Lock vault")
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 7.dp) {
                listOf(Triple("SOS", "●", "SOS"), Triple("Updates", "≋", "Updates"),
                    Triple("Evidence", "◈", "Evidence"), Triple("More", "☷", "More"))
                    .forEach { (title, symbol, description) ->
                        NavigationBarItem(
                            selected = if (title == "More") tab in listOf("More", "Incident", "Contacts", "Mesh") else tab == title,
                            onClick = { tab = title },
                            icon = { Text(symbol, style = MaterialTheme.typography.titleMedium) },
                            label = { Text(description) },
                            alwaysShowLabel = true
                        )
                    }
            }
        }
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)) {
            if (pendingPlainExport != null) {
                Text("Decrypted export can be accessed by other apps. Unlock the vault and confirm to continue.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = {
                        val pending = pendingPlainExport ?: return@Button
                        pendingPlainExport = null
                        execute { civic.exportVerifiedPlaintext(pending.first, pending.second) }
                    }) { Text("Finalize export") }
                    OutlinedButton(onClick = {
                        val pending = pendingPlainExport
                        pendingPlainExport = null
                        if (pending != null) runCatching { activity.contentResolver.delete(pending.second, null, null) }
                    }) { Text("Cancel") }
                }
            }
            if (error.isNotBlank()) {
                Surface(color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(error, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            if (tab in setOf("Contacts", "Mesh", "Incident")) {
                TextButton(onClick = { tab = "More" }) { Text("← Back to tools") }
            }
            key(tab) {
                when (tab) {
                    "SOS" -> SosTab(civic, activity, ::execute)
                    "Updates" -> UpdatesTab(civic, ::execute)
                    "Evidence" -> EvidenceTab(civic, ::execute, ::exportEvidence,
                        { importLauncher.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) },
                        ::chooseDecryptedDestination)
                    "Incident" -> IncidentTab(civic, ::execute)
                    "Contacts" -> ContactsTab(civic, ::execute)
                    "Mesh" -> MeshTab(civic)
                    else -> MoreTab(civic) { tab = it }
                }
            }
        }
    }

}


@Composable private fun MoreTab(civic: CivicService, navigate: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Tools & connections", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold)
        Text("Local features stay on this phone. Nearby exchanges need participating phones and permissions.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(
            Triple("Contacts", "Trusted contacts & QR", "Exchange public keys with a person you know, then verify fingerprints"),
            Triple("Mesh", "Nearby connectivity", "Make a phone discoverable, find nearby peers, and inspect diagnostics"),
            Triple("Incident", "Document an incident", "Save encrypted observations, badge details and witness notes")
        ).forEach { (route, title, subtitle) ->
            ElevatedCard(onClick = { navigate(route) }, shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("→", style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Text("© 2026 Chirag Goyal. All rights reserved.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun SosTab(civic: CivicService, activity: MainActivity, execute: (() -> Unit) -> Unit) {
    var message by remember { mutableStateOf("I need assistance. This is an emergency alert.") }
    var includeLocation by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var smsPhone by remember { mutableStateOf("") }
    var smsIncludeLocation by remember { mutableStateOf(true) }
    var smsStatus by remember { mutableStateOf("") }
    var awaitingSmsLocationPermission by remember { mutableStateOf(false) }
    val locationClient = remember { LocationServices.getFusedLocationProviderClient(activity) }

    fun queue(loc: android.location.Location?) {
        execute { civic.sendSos(message, loc?.latitude, loc?.longitude, loc?.accuracy) }
        status = if (loc == null) "Location unavailable — SOS queued without coordinates" else "Location included if SOS is received"
    }
    fun requestLocation() {
        if (!includeLocation) { queue(null); return }
        try {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                status = "Precise location permission not granted; sending without coordinates"
                queue(null)
                return
            }
            locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { queue(it) }
                .addOnFailureListener { queue(null) }
        } catch (_: Exception) { queue(null) }
    }
    val requestLocationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestLocation() else queue(null)
    }
    fun openManualSms(loc: Location?, source: String) {
        try {
            val messageText = SmsDraft.build(message, loc?.latitude, loc?.longitude,
                loc?.accuracy, source, smsIncludeLocation)
            val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", smsPhone.trim(), null))
                .putExtra("sms_body", messageText)
            activity.startActivity(intent)
            smsStatus = if (loc == null && smsIncludeLocation) "Location unavailable; SMS draft says so explicitly" else
                "SMS draft opened. You must press Send in your SMS app."
        } catch (e: Exception) { smsStatus = "SMS app unavailable: ${e.localizedMessage}" }
    }
    fun prepareSms() {
        if (!smsIncludeLocation) { openManualSms(null, "disabled"); return }
        smsStatus = "Fetching location (GPS may take a moment)…"
        try {
            locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { current ->
                    if (current != null) openManualSms(current, "current GPS")
                    else locationClient.lastLocation
                        .addOnSuccessListener { last ->
                            val fresh = last?.takeIf {
                                val ageNanos = SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos
                                ageNanos >= 0L && ageNanos <= 10L * 60 * 1_000_000_000L
                            }
                            openManualSms(fresh, if (fresh == null) "unavailable" else "recent location, up to 10 min old")
                        }.addOnFailureListener { openManualSms(null, "unavailable") }
                }.addOnFailureListener { openManualSms(null, "unavailable") }
        } catch (_: SecurityException) { smsStatus = "Location permission missing; allow precise location in app settings" }
    }
    val smsLocationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (awaitingSmsLocationPermission) {
            awaitingSmsLocationPermission = false
            if (granted) prepareSms() else openManualSms(null, "permission denied")
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (civic.notice.isNotBlank()) Text(civic.notice,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ElevatedCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.surfaceVariant))).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("EMERGENCY", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge)
                Text("Get help to your trusted circle", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
                Text("SOS is end-to-end encrypted for verified contacts. Nearby devices can only relay ciphertext.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedTextField(message, { message = it.take(500) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Emergency details") }, minLines = 3)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(includeLocation, { includeLocation = it }); Text("Share precise location with trusted contacts")
        }
        Button(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth(),
            enabled = civic.vault.contacts().isNotEmpty()) { Text("Queue private SOS  →") }
        if (civic.vault.contacts().isEmpty()) Text("Add a trusted contact before sending an SOS.")
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        Text("Queued is not delivered. Internet, SMS, and emergency-service calls are NOT initiated by this app.",
            color = MaterialTheme.colorScheme.error)
        HorizontalDivider()
        Text("Optional cellular SMS fallback", style = MaterialTheme.typography.titleMedium)
        Text("SMS does not need internet, but requires a working cellular network. It is NOT end-to-end encrypted; the phone carrier can see its contents.")
        OutlinedTextField(smsPhone, { smsPhone = it.take(25) }, label = { Text("Parent's phone number (not saved)") }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(smsIncludeLocation, { smsIncludeLocation = it })
            Text("Include unencrypted coordinates in SMS (if permitted)")
        }
        OutlinedButton(onClick = {
            if (smsIncludeLocation && ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                awaitingSmsLocationPermission = true
                smsLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            } else prepareSms()
        }, enabled = smsPhone.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Prepare SMS with location")
        }
        if (smsStatus.isNotBlank()) Text(smsStatus, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary)
        Text("You choose whether to send. SMS and its location are NOT encrypted end-to-end.",
            style = MaterialTheme.typography.labelSmall)
        HorizontalDivider()
        Text("SOS activity", style = MaterialTheme.typography.titleMedium)
        civic.vault.records("sosInbox").take(20).forEach { item ->
            RecordCard("${item.optString("sender")} • ${whenText(item.optLong("time"))}",
                "${item.optString("message")}\nLocation: ${item.optString("location")}\n" +
                when (item.optString("status")) {
                    "RECEIVED_BY_TRUSTED_APP" -> "Confirmed by at least one trusted app; not proof of reading or assistance"
                    "RECEIVED_ON_THIS_DEVICE" -> "Received locally on this device"
                    else -> "Queued, no trusted-recipient receipt"
                })
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Confirm emergency alert") },
        text = { Text("Encrypted alert will be queued for trusted recipients and offered to connected nearby devices. There is no delivery guarantee.") },
        confirmButton = { TextButton(onClick = {
            confirm = false
            if (includeLocation && ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            else requestLocation()
        }) { Text("Queue SOS") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}

@Composable private fun UpdatesTab(civic: CivicService, execute: (() -> Unit) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Nearby public updates", style = MaterialTheme.typography.headlineSmall)
        Text("Everything posted here may be read, copied or redistributed by strangers. Reports are unverified.")
        OutlinedTextField(text, { text = it.take(280) }, label = { Text("Short public update") },
            modifier = Modifier.fillMaxWidth(), minLines = 2)
        Button(onClick = { val post = text; execute { civic.post(post) }; text = "" }, enabled = text.isNotBlank()) {
            Text("Publish to nearby mesh")
        }
        HorizontalDivider()
        val entries = civic.vault.records("bulletins")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries) { item -> RecordCard("Unverified • ${item.optString("sender")} • ${whenText(item.optLong("time"))}", item.optString("text")) }
        }
    }
}

@Composable private fun ContactsTab(civic: CivicService, execute: (() -> Unit) -> Unit) {
    var alias by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var fingerprintChecked by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var scanning by remember { mutableStateOf(false) }
    var cameraGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted; if (granted) scanning = true
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Trusted contacts", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold)
        Text("A public key is not a secret. Verify the 40-character fingerprint in person or through another trusted channel.")
        Text("Your fingerprint: ${Crypto.fingerprint(civic.vault.pub())}", style = MaterialTheme.typography.bodySmall)
        ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("My contact QR", fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium)
                Text("Have a trusted person scan this using CivicRelay on their phone.",
                    style = MaterialTheme.typography.bodySmall)
                ContactQr(civic.contactCode(), Modifier.fillMaxWidth().height(320.dp))
            }
        }
        Button(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("CivicRelay public contact code", civic.contactCode()))
        }) { Text("Copy my public contact code") }
        Text("QR exchange works without internet. If the camera cannot scan, copy the public code and transfer it in person.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(alias, { alias = it }, label = { Text("Contact name") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { if (cameraGranted) scanning = !scanning else cameraPermission.launch(Manifest.permission.CAMERA) },
            modifier = Modifier.fillMaxWidth()) {
            Text(if (scanning) "Stop QR scanner" else "Scan trusted contact QR")
        }
        if (scanning && cameraGranted) {
            val scanner = remember {
                runCatching { ContactScanner(context) { scanned -> code = scanned; scanning = false } }.getOrNull()
            }
            if (scanner == null) {
                Text("QR camera unavailable. Paste a public contact code below instead.",
                    color = MaterialTheme.colorScheme.error)
            } else {
                DisposableEffect(scanner) { onDispose { scanner.close() } }
                AndroidView(modifier = Modifier.fillMaxWidth().height(300.dp),
                    factory = { ctx -> PreviewView(ctx).also { scanner.attach(it, lifecycleOwner) } })
            }
        }
        OutlinedTextField(code, { code = it; fingerprintChecked = false },
            label = { Text("Their CRCONTACT1 public code") },
            minLines = 3, modifier = Modifier.fillMaxWidth())
        val theirFingerprint = remember(code) {
            runCatching {
                val payload = code.trim().removePrefix("CRCONTACT1:")
                // Validate the actual cryptographic key before displaying a fingerprint.
                Crypto.publicKey(payload)
                Crypto.fingerprint(payload)
            }.getOrNull()
        }
        if (!theirFingerprint.isNullOrBlank()) {
            Text("Their key fingerprint: $theirFingerprint", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(fingerprintChecked, { fingerprintChecked = it })
            Text("I compared the complete fingerprint through a trusted independent channel")
        }
        Button(onClick = {
            val a = alias; val c = code
            execute { civic.addContact(a, c) }
            alias = ""; code = ""; fingerprintChecked = false
        }, enabled = alias.isNotBlank() && theirFingerprint != null &&
            code.trim().startsWith("CRCONTACT1:") && fingerprintChecked) {
            Text("Save verified contact")
        }
        HorizontalDivider()
        civic.vault.contacts().forEach { (name, pub) ->
            RecordCard(name, "Fingerprint: ${Crypto.fingerprint(pub)}")
        }
    }
}

@Composable private fun IncidentTab(civic: CivicService, execute: (() -> Unit) -> Unit) {
    var officer by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Incident / detention log", style = MaterialTheme.typography.headlineSmall)
        Text("Record what you personally observed. Do not infer identity from facial appearance; OCR can be wrong.")
        OutlinedTextField(officer, { officer = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Visible badge / nameplate / unit / vehicle details") })
        OutlinedTextField(location, { location = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Observed location") })
        OutlinedTextField(notes, { notes = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Incident notes and witness information") }, minLines = 4)
        Button(onClick = {
            val a = notes; val b = officer; val c = location
            execute { civic.saveIncident(a, b, c) }; notes = ""; officer = ""; location = ""
        }, enabled = notes.isNotBlank()) { Text("Save encrypted incident record") }
        Text("When online, independently check documented badge numbers against a verified official directory, where one exists. No face-to-person matching.",
            style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        civic.vault.records("incidents").take(20).forEach { item ->
            RecordCard(whenText(item.optLong("time")), "Officer details: ${item.optString("officerDetails")}\n${item.optString("notes")}")
        }
    }
}

@Composable private fun EvidenceTab(
    civic: CivicService,
    execute: (() -> Unit) -> Unit,
    exportEvidence: (String, String) -> Unit,
    chooseImport: () -> Unit,
    exportPlaintext: (String, String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permitted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var status by remember { mutableStateOf("") }
    var selectedKey by remember { mutableStateOf("") }
    var opened by remember { mutableStateOf<VerifiedEvidence?>(null) }
    var previewBusy by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf("") }
    var confirmPlainExport by remember { mutableStateOf<Pair<String, String>?>(null) }
    val previewScope = rememberCoroutineScope()

    // Screen disposal (including vault lock/background) releases verified plaintext in memory.
    DisposableEffect(Unit) {
        onDispose { opened?.close(); opened = null }
    }

    fun viewVerified(id: String) {
        previewScope.launch {
            previewBusy = true
            previewError = ""
            opened?.close()
            opened = null
            try {
                val verified = withContext(Dispatchers.IO) { civic.openVerifiedEvidence(id) }
                if (!isActive || !civic.vault.unlocked) verified.close()
                else opened = verified
            } catch (e: Exception) { previewError = "Cannot view evidence: ${e.localizedMessage}" }
            finally { previewBusy = false }
        }
    }

    val camera = remember { CameraSession(context, { file, kind, ocr ->
        civic.persistCapturedMedia(file, kind, ocr)
    }, { status = it }) }
    DisposableEffect(camera, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) camera.close()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            camera.close()
        }
    }
    val grant = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { p ->
        permitted = p[Manifest.permission.CAMERA] == true
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Private evidence camera", style = MaterialTheme.typography.headlineSmall)
        Text("Photos/videos temporarily exist as plaintext in private app cache before encryption. Not seizure-proof.",
            color = MaterialTheme.colorScheme.error)
        if (!permitted) {
            Button(onClick = { grant.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }) {
                Text("Allow camera and microphone")
            }
        } else {
            AndroidView(modifier = Modifier.fillMaxWidth().height(230.dp),
                factory = { ctx -> PreviewView(ctx).also { camera.attach(it, lifecycleOwner) } })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { camera.photo() }) { Text("Take photo") }
                Button(onClick = { camera.toggleVideo() }) { Text("Start / stop video") }
            }
            if (status.isNotBlank()) Text(status)
        }
        HorizontalDivider()
        Text("Encrypted copies for trusted contacts", style = MaterialTheme.typography.titleMedium)
        Text("Share recipient-encrypted evidence directly over Nearby with an authenticated trusted contact. The recipient must approve before the file is sent. Both phones must remain unlocked with Mesh enabled.",
            style = MaterialTheme.typography.bodySmall)
        val direct = civic.mesh.evidence
        Text("Trusted nearby contacts: ${direct.trustedNearby} • Progress: ${direct.progress}%")
        Text(direct.status, style = MaterialTheme.typography.bodySmall)
        val pendingOffer = direct.offer
        if (pendingOffer != null) {
            val trustedName = civic.vault.contacts().firstOrNull { it.second == pendingOffer.sender }?.first ?: "Trusted contact"
            Text("Incoming encrypted evidence: $trustedName (${pendingOffer.bytes / (1024 * 1024)} MiB)",
                style = MaterialTheme.typography.titleMedium)
            Text("Approving uses your phone storage. Content is verified before being added to your vault.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { execute { direct.approve(pendingOffer.id) } }) { Text("Accept encrypted copy") }
                OutlinedButton(onClick = { direct.decline(pendingOffer.id) }) { Text("Decline") }
            }
        }
        val contacts = civic.vault.contacts()
        contacts.forEach { (name, publicKey) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selectedKey == publicKey, onClick = { selectedKey = publicKey })
                Text(name + " (" + Crypto.fingerprint(publicKey).take(10) + "…)")
            }
        }
        val media = civic.vault.records("media").take(20)
        Text("Every preview is decrypted and SHA-256 verified. Viewing uses short-lived memory only; exported copies are NOT encrypted.",
            style = MaterialTheme.typography.bodySmall)
        if (previewBusy) CircularProgressIndicator()
        if (previewError.isNotBlank()) Text(previewError, color = MaterialTheme.colorScheme.error)
        val currentPreview = opened
        if (currentPreview != null) {
            HorizontalDivider()
            Text("Integrity verified: ${currentPreview.sha256}", style = MaterialTheme.typography.labelSmall)
            Text(if (currentPreview.importedFrom == null) "Original local evidence" else
                "Recovered copy imported from trusted key ${currentPreview.importedFrom}")
            when (currentPreview.kind) {
                "photo" -> VerifiedPhotoPreview(currentPreview)
                "video" -> VerifiedVideoPreview(currentPreview)
            }
            OutlinedButton(onClick = { opened?.close(); opened = null }) { Text("Close private viewer") }
            HorizontalDivider()
        }
        media.forEach { item ->
            RecordCard("${item.optString("kind")} • ${whenText(item.optLong("created"))}",
                "SHA-256: ${item.optString("sha256")}\nOCR suggestion: ${item.optString("ocrSuggestion").take(180)}" +
                if (item.has("importedFrom")) "\nRecovered from trusted contact" else "\nRecorded on this phone")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !previewBusy, onClick = { viewVerified(item.getString("id")) }) {
                    Text("Verify & view")
                }
                OutlinedButton(onClick = {
                    confirmPlainExport = item.getString("id") to item.getString("kind")
                }) { Text("Export decrypted copy") }
            }
            Button(enabled = selectedKey.isNotBlank() && civic.mesh.active &&
                direct.trustedEndpointFor(selectedKey), onClick = {
                execute { civic.offerNearbyEvidence(item.getString("id"), selectedKey) }
            }) { Text("Send encrypted copy over nearby") }
            OutlinedButton(enabled = selectedKey.isNotBlank(), onClick = {
                exportEvidence(item.getString("id"), selectedKey)
            }) { Text("Export encrypted .crb file manually") }
        }
        HorizontalDivider()
        Text("Receive a trusted contact's evidence", style = MaterialTheme.typography.titleMedium)
        Text("Select an encrypted .crb file. It will only be accepted if its signature is from a saved trusted contact and it is addressed to you.",
            style = MaterialTheme.typography.bodySmall)
        Button(onClick = chooseImport) {
            Text("Select encrypted evidence file")
        }
        if (civic.hasPendingImport()) Button(onClick = { execute { civic.acceptPendingEvidenceImport() } }) {
            Text("Import pending copy from trusted contact")
        }
        val transfers = civic.vault.records("transfers").take(10)
        if (transfers.isNotEmpty()) {
            HorizontalDivider()
            Text("Encrypted evidence transfer history", style = MaterialTheme.typography.titleMedium)
            transfers.forEach { item ->
                RecordCard(whenText(item.optLong("created")),
                    "Recipient fingerprint: ${item.optString("recipientFingerprint").take(16)}…\n" +
                    when (item.optString("status")) {
                        "VERIFIED_STORED_BY_TRUSTED_APP" -> "Stored and verified by intended trusted app; not human-reviewed"
                        else -> "Offered; no signed storage receipt"
                    })
            }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        Text("A signed 'stored' receipt confirms only that the intended trusted app imported and verified the file. It is not proof someone viewed it or that the copy will remain available.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    val request = confirmPlainExport
    if (request != null) AlertDialog(
        onDismissRequest = { confirmPlainExport = null },
        title = { Text("Export an UNENCRYPTED copy?") },
        text = { Text("The destination file will be readable outside CivicRelay, possibly included in cloud backups or accessible to other apps. Only export to a location you control. Selecting a destination locks the vault; unlock again and finalize the export.") },
        confirmButton = { TextButton(onClick = {
            confirmPlainExport = null
            opened?.close(); opened = null
            exportPlaintext(request.first, request.second)
        }) { Text("Choose destination") } },
        dismissButton = { TextButton(onClick = { confirmPlainExport = null }) { Text("Cancel") } }
    )
}

@Composable private fun MeshTab(civic: CivicService) {
    val ctx = LocalContext.current
    val mesh = civic.mesh
    var requestedMode by remember { mutableStateOf(org.civicrelay.app.mesh.NearbyMesh.Mode.ADVERTISE) }
    var permissionError by remember { mutableStateOf("") }
    val required = remember {
        when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION)
            Build.VERSION.SDK_INT >= 31 -> arrayOf(Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION)
            else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    fun startSelected(mode: org.civicrelay.app.mesh.NearbyMesh.Mode) {
        if (mode == org.civicrelay.app.mesh.NearbyMesh.Mode.ADVERTISE) mesh.startAsAdvertiser()
        else mesh.startAsDiscoverer()
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val denied = required.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (denied.isEmpty()) { permissionError = ""; startSelected(requestedMode) }
        else permissionError = "Permissions denied: ${denied.joinToString { it.substringAfterLast('.') }}. Open app settings to allow Nearby Devices."
    }
    fun launchRole(mode: org.civicrelay.app.mesh.NearbyMesh.Mode) {
        requestedMode = mode
        val denied = required.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (denied.isEmpty()) { permissionError = ""; startSelected(mode) }
        else permissionLauncher.launch(denied.toTypedArray())
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Nearby connection", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Use two physical Android phones with Google Play services. Grant nearby-radio and location permissions, turn on Bluetooth/Wi-Fi/Location services, and keep both apps open and unlocked. Permission does not publish GPS coordinates.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ElevatedCard(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("CONNECT TWO PHONES", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Text("Phone A", fontWeight = FontWeight.Bold)
                Button(onClick = { launchRole(org.civicrelay.app.mesh.NearbyMesh.Mode.ADVERTISE) },
                    modifier = Modifier.fillMaxWidth()) { Text("1. Make this phone discoverable") }
                Text("Phone B", fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { launchRole(org.civicrelay.app.mesh.NearbyMesh.Mode.DISCOVER) },
                    modifier = Modifier.fillMaxWidth()) { Text("2. Find nearby phones") }
                Text("After connecting, both phones can exchange updates, private SOS envelopes and approved encrypted evidence.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (mesh.peerCount > 0) "● Connected" else "○ Not connected",
                    fontWeight = FontWeight.Bold, color = if (mesh.peerCount > 0)
                        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(mesh.status)
                Text("Mode: ${mesh.mode?.name ?: "Stopped"} · Peers: ${mesh.peerCount} · Found: ${mesh.foundCount}",
                    style = MaterialTheme.typography.bodySmall)
                Text("Radio diagnostics: ${mesh.diagnostics}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (permissionError.isNotBlank()) Text(permissionError, color = MaterialTheme.colorScheme.error)
            }
        }
        OutlinedButton(onClick = { mesh.stop() }, enabled = mesh.active,
            modifier = Modifier.fillMaxWidth()) { Text("Stop nearby connection") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { ctx.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
                Text("Bluetooth")
            }
            OutlinedButton(onClick = { ctx.startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) }) {
                Text("Wi-Fi")
            }
            OutlinedButton(onClick = { ctx.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) {
                Text("Location")
            }
        }
        Text("If no devices appear, confirm both phones use the same build, have radio permissions, and are close together. Radio activity may disclose participation. Delivery is not guaranteed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Nearby stops when CivicRelay backgrounds or the vault locks.",
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun RecordCard(title: String, body: String) {
    ElevatedCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
