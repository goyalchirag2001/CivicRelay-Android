package org.civicrelay.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.civicrelay.app.crypto.Crypto
import org.civicrelay.app.media.CameraSession
import org.civicrelay.app.media.ContactQr
import org.civicrelay.app.media.ContactScanner
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
            MaterialTheme(colorScheme = darkColorScheme()) { CivicScreen(civic, this) }
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
        Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center) {
            Text("CivicRelay", style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.copyright_notice), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(12.dp))
            Text("Experimental offline safety app — NOT verified for high-risk use", color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(16.dp))
            Text(if (civic.vault.exists) "Unlock private vault" else "Create your private vault")
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(passphrase, { passphrase = it }, label = { Text("Vault passphrase (12+ characters)") },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            if (!civic.vault.exists) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(confirmPass, { confirmPass = it }, label = { Text("Confirm passphrase") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                if (passphrase.length < 12 || (!civic.vault.exists && passphrase != confirmPass)) {
                    error = "Passphrase must have 12+ characters and both entries must match"
                } else {
                    val entered = passphrase
                    passphrase = ""; confirmPass = ""
                    execute {
                        check(civic.unlock(entered)) { "Vault could not be opened. Verify your passphrase." }
                    }
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (civic.vault.exists) "Unlock" else "Create vault")
            }
            Text("No recovery service. A forgotten passphrase means losing access to local records.",
                style = MaterialTheme.typography.bodySmall)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            Text(civic.notice, style = MaterialTheme.typography.bodySmall)
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("CivicRelay", style = MaterialTheme.typography.titleLarge)
                Text("Experimental • Offline first", style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.copyright_notice), style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { civic.lock(); passphrase = ""; tab = "SOS" }) { Text("Lock") }
        }
        Text(civic.notice, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("SOS", "Updates", "Evidence", "Incident", "Contacts", "Mesh").forEach { title ->
                FilterChip(selected = title == tab, onClick = { tab = title }, label = { Text(title) })
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        key(tab) {
            when (tab) {
                "SOS" -> SosTab(civic, activity, ::execute)
                "Updates" -> UpdatesTab(civic, ::execute)
                "Evidence" -> EvidenceTab(civic, ::execute, ::exportEvidence,
                    { importLauncher.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) })
                "Incident" -> IncidentTab(civic, ::execute)
                "Contacts" -> ContactsTab(civic, ::execute)
                "Mesh" -> MeshTab(civic)
            }
        }
    }
}

@Composable private fun SosTab(civic: CivicService, activity: MainActivity, execute: (() -> Unit) -> Unit) {
    var message by remember { mutableStateOf("I need assistance. This is an emergency alert.") }
    var includeLocation by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var smsPhone by remember { mutableStateOf("") }
    var smsIncludeLocation by remember { mutableStateOf(false) }
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Private emergency SOS", style = MaterialTheme.typography.headlineSmall)
        Text("Only your trusted contacts can decrypt the alert. Unknown nearby phones may forward its ciphertext.")
        OutlinedTextField(message, { message = it.take(500) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Emergency details") }, minLines = 3)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(includeLocation, { includeLocation = it }); Text("Share precise location with trusted contacts")
        }
        Button(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth(),
            enabled = civic.vault.contacts().isNotEmpty()) { Text("QUEUE ENCRYPTED SOS") }
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
            fun showSms(loc: android.location.Location?) {
                try {
                    val coordinates = if (smsIncludeLocation) {
                        if (loc != null) " Coordinates: ${loc.latitude},${loc.longitude}." else " Location unavailable."
                    } else ""
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(smsPhone)}"))
                    intent.putExtra("sms_body", "Emergency: ${message.trim()}.$coordinates Sent from CivicRelay.")
                    activity.startActivity(intent)
                } catch (_: Exception) { status = "No compatible SMS application is available" }
            }
            if (smsIncludeLocation && ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                    .addOnSuccessListener { showSms(it) }.addOnFailureListener { showSms(null) }
            } else showSms(null)
        }, enabled = smsPhone.isNotBlank()) { Text("Open SMS app for manual sending") }
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
        Text("Trusted recipients", style = MaterialTheme.typography.headlineSmall)
        Text("A public key is not a secret. Verify the 40-character fingerprint in person or through another trusted channel.")
        Text("Your fingerprint: ${Crypto.fingerprint(civic.vault.pub())}", style = MaterialTheme.typography.bodySmall)
        ContactQr(civic.contactCode(), Modifier.fillMaxWidth().height(230.dp))
        Button(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("CivicRelay public contact code", civic.contactCode()))
        }) { Text("Copy my public contact code") }
        Text("For offline exchange, send the code using a direct local file-transfer option or display it to the other person beforehand.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(alias, { alias = it }, label = { Text("Contact name") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { if (cameraGranted) scanning = !scanning else cameraPermission.launch(Manifest.permission.CAMERA) }) {
            Text(if (scanning) "Stop QR scanner" else "Scan trusted contact QR")
        }
        if (scanning && cameraGranted) {
            val scanner = remember { ContactScanner(context) { scanned -> code = scanned; scanning = false } }
            DisposableEffect(scanner) { onDispose { scanner.close() } }
            AndroidView(modifier = Modifier.fillMaxWidth().height(230.dp),
                factory = { ctx -> PreviewView(ctx).also { scanner.attach(it, lifecycleOwner) } })
        }
        OutlinedTextField(code, { code = it; fingerprintChecked = false },
            label = { Text("Their CRCONTACT1 public code") },
            minLines = 3, modifier = Modifier.fillMaxWidth())
        val theirFingerprint = remember(code) {
            runCatching { Crypto.fingerprint(code.trim().removePrefix("CRCONTACT1:")) }.getOrNull()
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
        }, enabled = alias.isNotBlank() && code.startsWith("CRCONTACT1:") && fingerprintChecked) {
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
    chooseImport: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permitted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var status by remember { mutableStateOf("") }
    var selectedKey by remember { mutableStateOf("") }

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
        media.forEach { item ->
            RecordCard("${item.optString("kind")} • ${whenText(item.optLong("created"))}",
                "SHA-256: ${item.optString("sha256")}\nOCR suggestion: ${item.optString("ocrSuggestion").take(180)}")
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
}

@Composable private fun MeshTab(civic: CivicService) {
    val ctx = LocalContext.current
    val mesh = civic.mesh
    var permissionsReady by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        permissionsReady = results.values.all { it }
        if (permissionsReady) mesh.start()
    }
    val required = remember {
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES)
        else if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Nearby mesh", style = MaterialTheme.typography.headlineSmall)
        Text("Requires Google Play services, Bluetooth/Wi-Fi radios enabled, and participating nearby devices.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
            }) { Text("Bluetooth settings") }
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
            }) { Text("Wi-Fi settings") }
        }
        Text("Devices can detect radio activity and nearby participation. Public posts are readable by strangers. Direct evidence pairing reveals your persistent contact public key to nearby peers challenged by this app; do not assume anonymity.")
        Text("Connection state: ${mesh.status}")
        Text("Connected peers: ${mesh.peerCount}")
        Button(onClick = {
            if (mesh.active) mesh.stop()
            else if (required.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) mesh.start()
            else permissionLauncher.launch(required)
        }, modifier = Modifier.fillMaxWidth()) { Text(if (mesh.active) "Stop nearby sharing" else "Start nearby sharing") }
        Text("Forwarding only occurs while the app remains running and nearby sharing is on. Only cryptographically validated trusted-contact receipt updates are shown; no guaranteed cross-city routes.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun RecordCard(title: String, body: String) {
    OutlinedCard(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(5.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
