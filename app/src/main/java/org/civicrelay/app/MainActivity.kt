package org.civicrelay.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ComponentName
import android.app.StatusBarManager
import android.graphics.drawable.Icon
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.os.CancellationSignal
import android.hardware.biometrics.BiometricPrompt
import android.hardware.biometrics.BiometricManager
import android.widget.Toast
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
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
import androidx.lifecycle.lifecycleScope
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
    private val app get() = application as CivicRelayApp
    private val civic get() = app.civic

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // Remove abandoned pre-encryption camera files (deletion is best effort).
        File(cacheDir, "capture").listFiles()?.forEach { it.delete() }
        cacheDir.listFiles()?.filter { it.name.endsWith(".working-crv") || it.name.endsWith(".crb") }
            ?.forEach { it.delete() }
        setContent { CivicTheme { CivicScreen(civic, this) } }
    }
    override fun onStart() { super.onStart(); app.cancelPrivateLock() }
    override fun onStop() {
        // Keep the unlocked vault briefly for common flows such as SMS and document picker.
        // Radio stays in a separate, visible foreground service until explicitly stopped.
        app.schedulePrivateLock()
        super.onStop()
    }
    override fun onDestroy() { super.onDestroy() } // application owns CivicService

    /** Android 13+ can offer a one-tap tile installation prompt; older phones use manual Edit Tiles. */
    fun requestQuickSosTile(result: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) {
            result("Swipe down twice → Edit (pencil) → add CivicRelay SOS to Quick Settings.")
            return
        }
        try {
            val statusBar = getSystemService(StatusBarManager::class.java)
            if (statusBar == null) {
                result("Quick Settings service unavailable. Add CivicRelay SOS manually from Edit Tiles.")
                return
            }
            statusBar.requestAddTileService(
                ComponentName(this, EmergencyTileService::class.java),
                "CivicRelay SOS", Icon.createWithResource(this, R.drawable.ic_civic_sos),
                mainExecutor
            ) { response ->
                result(when (response) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Shortcut added. Test with a paired phone and synthetic SOS."
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "SOS tile is already in Quick Settings."
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "Shortcut wasn't added. Open Quick Settings → Edit to add it."
                    else -> "Android didn't add the tile ($response). Try Quick Settings → Edit manually."
                })
            }
        } catch (failure: Exception) {
            result("Tile setup unavailable: ${failure.localizedMessage ?: "Android blocked the request"}. Add it manually using Edit Tiles.")
        }
    }

    fun biometricStatus(): String {
        if (Build.VERSION.SDK_INT < 30) return "Android 11 or newer is needed for this quick-unlock setup."
        return try {
            val manager = getSystemService(BiometricManager::class.java)
                ?: return "Biometric manager unavailable on this phone."
            when (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
                BiometricManager.BIOMETRIC_SUCCESS -> "Strong biometric ready"
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "No strong fingerprint enrolled in phone settings"
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "No strong biometric sensor on this device"
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Fingerprint hardware is temporarily unavailable"
                BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "Biometric sensor requires a security update"
                else -> "Strong biometric unavailable or restricted by Android"
            }
        } catch (_: Exception) { "Could not check fingerprint hardware" }
    }
    fun openBiometricSettings() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) startActivity(Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
                putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                    BiometricManager.Authenticators.BIOMETRIC_STRONG)
            }) else startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }.onFailure { Toast.makeText(this, "Open phone Settings → Security → Fingerprint", Toast.LENGTH_LONG).show() }
    }
    fun canUseFingerprint(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        // Some OEM builds return null or throw while querying biometric hardware.
        // Never let an optional convenience feature crash the entire More screen.
        return runCatching {
            val manager = getSystemService(BiometricManager::class.java) ?: return@runCatching false
            manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
        }.onFailure { Log.w("CivicRelayPrivacy", "Biometric availability check failed", it) }
            .getOrDefault(false)
    }

    /** Fingerprint never substitutes for the vault key. Keystore authorizes key unwrap. */
    fun fingerprint(enroll: Boolean) {
        if (!canUseFingerprint()) {
            Toast.makeText(this, "Strong fingerprint not available; use passphrase", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val cipher = if (enroll) civic.biometrics.prepareEnroll()
                else civic.biometrics.prepareUnlock()
            val prompt = BiometricPrompt.Builder(this)
                .setTitle(if (enroll) "Enable fingerprint unlock" else "Unlock CivicRelay")
                .setSubtitle("Private vault · Your passphrase remains available")
                .setNegativeButton("Use passphrase", mainExecutor) { _, _ -> }
                .build()
            prompt.authenticate(BiometricPrompt.CryptoObject(cipher), CancellationSignal(), mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val authorized = result.cryptoObject?.cipher ?: return
                        lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    if (enroll) civic.enableBiometric(authorized)
                                    else check(civic.unlockWithBiometric(authorized)) { "Unlock failed" }
                                }
                                Toast.makeText(this@MainActivity,
                                    if (enroll) "Fingerprint quick unlock enabled" else "Vault unlocked",
                                    Toast.LENGTH_SHORT).show()
                            } catch (_: Exception) {
                                Toast.makeText(this@MainActivity,
                                    "Fingerprint unavailable. Unlock with passphrase and re-enable it.",
                                    Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        // The negative button is handled by setNegativeButton's listener.
                        // BIOMETRIC_ERROR_NEGATIVE_BUTTON is not a public SDK constant.
                        if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED) {
                            Toast.makeText(this@MainActivity, errString, Toast.LENGTH_SHORT).show()
                        }
                    }
                })
        } catch (_: Exception) {
            Toast.makeText(this, "Fingerprint setup unavailable. Use the passphrase.", Toast.LENGTH_LONG).show()
        }
    }
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
    var preferredSmsPhone by remember { mutableStateOf("") }
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
            Image(painterResource(R.drawable.civicrelay_mark), contentDescription = "CivicRelay brand",
                modifier = Modifier.size(92.dp))
            Spacer(Modifier.height(20.dp))
            Text("CivicRelay", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text("Your evidence. Your trusted circle.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(20.dp))
            ElevatedCard(shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (civic.vault.exists) "Welcome back" else "Set up your private vault",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Your information stays on your phone", style = MaterialTheme.typography.bodySmall,
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
                    if (civic.vault.exists && civic.biometrics.enrolled && activity.canUseFingerprint()) {
                        FilledTonalButton(onClick = { activity.fingerprint(false) },
                            modifier = Modifier.fillMaxWidth()) { Text("Unlock with fingerprint") }
                    }
                    Text("Keep your passphrase safe. Fingerprint is optional and device-only.",
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
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Image(painterResource(R.drawable.civicrelay_mark), contentDescription = "CivicRelay logo",
                            modifier = Modifier.size(42.dp))
                    Column {
                        Text("CivicRelay", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(if (civic.mesh.active) {
                            if (civic.mesh.peerCount > 0) "Nearby on · ${civic.mesh.peerCount} connected"
                            else "Nearby on · Looking for phones"
                        } else "Nearby off · Tap Nearby to connect",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    }
                    TextButton(onClick = { civic.lock(); passphrase = ""; tab = "SOS" }) {
                        Text("Lock vault")
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
                listOf(
                    Triple("SOS", R.drawable.ic_civic_sos, "Home"),
                    Triple("Updates", R.drawable.ic_civic_news, "Community"),
                    Triple("Evidence", R.drawable.ic_civic_evidence, "Evidence"),
                    Triple("Mesh", R.drawable.ic_civic_nearby, "Connect"),
                    Triple("More", R.drawable.ic_civic_more, "More")
                ).forEach { (section, image, label) ->
                    NavigationBarItem(
                        selected = if (section == "More") tab in listOf("More", "Incident", "Contacts", "SmsContacts", "SafetyMap") else tab == section,
                        onClick = { tab = section },
                        icon = { Icon(painterResource(image), contentDescription = label, modifier = Modifier.size(21.dp)) },
                        label = { Text(label) }, alwaysShowLabel = true
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
            if (tab in setOf("Contacts", "SmsContacts", "Incident", "SafetyMap")) {
                TextButton(onClick = { tab = "More" }) { Text("← Back to tools") }
            }
            key(tab) {
                when (tab) {
                    "SOS" -> SosTab(civic, activity, ::execute, preferredSmsPhone,
                        onManageSms = { tab = "SmsContacts" }, onManageAppContacts = { tab = "Contacts" })
                    "Updates" -> UpdatesTab(civic, ::execute)
                    "Evidence" -> EvidenceTab(civic, ::execute, ::exportEvidence,
                        { importLauncher.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) },
                        ::chooseDecryptedDestination)
                    "Incident" -> IncidentTab(civic, ::execute)
                    "Contacts" -> ContactsTab(civic, ::execute)
                    "SmsContacts" -> org.civicrelay.app.ui.SmsContactsScreen(civic,
                        onUseForSms = { phone -> preferredSmsPhone = phone; tab = "SOS" })
                    "Mesh" -> MeshTab(civic)
                    "SafetyMap" -> SafetyMapTab(civic, ::execute)
                    else -> MoreTab(civic, activity) { tab = it }
                }
            }
        }
    }

}


@Composable private fun MoreTab(civic: CivicService, activity: MainActivity, navigate: (String) -> Unit) {
    val profile = remember(civic.revision) { civic.vault.publicProfile() }
    var name by remember(profile) { mutableStateOf(profile.optString("name")) }
    var role by remember(profile) { mutableStateOf(profile.optString("role")) }
    var region by remember(profile) { mutableStateOf(profile.optString("region")) }
    var consent by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val fingerprintSupported = remember { activity.canUseFingerprint() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings & your identity", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold)
        Text("Manage what you share. Nothing in your profile is uploaded unless you publish a community post.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ElevatedCard(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Public posting profile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Your chosen name and basic details appear alongside public posts. Use only information you are comfortable sharing widely.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it.take(48) }, label = { Text("Display name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(role, { role = it.take(32) }, label = { Text("Public role (optional)") },
                    placeholder = { Text("Observer, volunteer, journalist…") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(region, { region = it.take(40) }, label = { Text("Area (optional)") },
                    placeholder = { Text("District or city, not home address") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = consent, onCheckedChange = { consent = it })
                    Text("I understand public posts may be copied and redistributed", style = MaterialTheme.typography.bodySmall)
                }
                Button(enabled = name.isNotBlank() && consent, modifier = Modifier.fillMaxWidth(), onClick = {
                    runCatching { civic.savePublicProfile(name, role, region) }
                        .onSuccess { message = "Public posting profile saved locally" }
                        .onFailure { message = "Could not save profile: ${it.localizedMessage}" }
                }) { Text("Save posting profile") }
                Text("Signed posts prove control of this device key, NOT someone's legal identity. Government ID verification is not enabled.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
            }
        }
        ElevatedCard(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Privacy & unlocking", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Your vault has a passphrase. Nearby can remain on while the vault is locked.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                if (fingerprintSupported) {
                    if (civic.biometrics.enrolled) {
                        OutlinedButton(onClick = {
                            runCatching { civic.disableBiometric() }
                                .onFailure { message = "Couldn't disable fingerprint: ${it.localizedMessage}" }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Disable fingerprint unlock") }
                    } else {
                        Button(onClick = { activity.fingerprint(true) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Enable fingerprint unlock")
                        }
                    }
                } else {
                    Text(activity.biometricStatus(), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { activity.openBiometricSettings() },
                        modifier = Modifier.fillMaxWidth()) { Text("Open phone fingerprint settings") }
                    Text("CivicRelay needs a strong enrolled biometric for its encrypted vault key. " +
                        "Some face-unlock and fingerprint sensors are classified as weak by Android.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        listOf(
            Triple("Contacts", "CivicRelay trusted people", "Pair another app for encrypted SOS and evidence sharing"),
            Triple("SmsContacts", "Parents & SMS contacts", "Save phone numbers for manual emergency text messages"),
            Triple("Incident", "Record an incident", "Keep witness notes and badge details encrypted"),
            Triple("SafetyMap", "Detention reports map", "Privacy-protected area summaries, never an individual tracking map")
        ).forEach { (route, title, description) ->
            ElevatedCard(onClick = { navigate(route) }, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(description, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Text("© 2026 Chirag Goyal. All rights reserved.", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun SosTab(civic: CivicService, activity: MainActivity, execute: (() -> Unit) -> Unit,
    preferredSmsPhone: String, onManageSms: () -> Unit, onManageAppContacts: () -> Unit) {
    var message by remember { mutableStateOf("I need assistance. This is an emergency alert.") }
    var includeLocation by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var checkinStatus by remember { mutableStateOf("") }
    var checkinConfirm by remember { mutableStateOf(false) }
    var smsPhone by remember { mutableStateOf("") }
    LaunchedEffect(preferredSmsPhone) { if (preferredSmsPhone.isNotBlank()) smsPhone = preferredSmsPhone }
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
            val phone = SmsContactPolicy.normalize(smsPhone)
            val messageText = SmsDraft.build(message, loc?.latitude, loc?.longitude,
                loc?.accuracy, source, smsIncludeLocation)
            val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", phone, null))
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
    fun shareTrustedCheckIn() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            checkinStatus = "Grant precise location permission to share a check-in. Nothing was sent."
            return
        }
        checkinStatus = "Getting current position…"
        locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { current ->
                if (current == null) checkinStatus = "No current location; nothing was shared."
                else {
                    execute { civic.sendLocationCheckIn(current.latitude, current.longitude, current.accuracy) }
                    checkinStatus = "Encrypted check-in queued for trusted contacts; delivery not guaranteed."
                }
            }.addOnFailureListener { checkinStatus = "Location unavailable; nothing was shared." }
    }
    val checkinLocationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) shareTrustedCheckIn()
        else checkinStatus = "Permission denied; nothing was shared."
    }
    val smsLocationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (awaitingSmsLocationPermission) {
            awaitingSmsLocationPermission = false
            if (granted) prepareSms() else openManualSms(null, "permission denied")
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ElevatedCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(if (civic.mesh.active) "Nearby sharing is on" else "Nearby sharing is off",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(if (civic.mesh.active)
                        "${civic.mesh.peerCount} connected phones · continues in background"
                        else "Use the Nearby tab to connect with local phones",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text(if (civic.mesh.active) "●" else "○",
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (civic.mesh.active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
                Text("Reach your trusted people", style = MaterialTheme.typography.headlineSmall,
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
            enabled = civic.vault.contacts().isNotEmpty()) { Text("Send protected SOS") }
        if (civic.vault.contacts().isEmpty()) Text("Add a trusted contact before sending an SOS.")
        ElevatedCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Quick emergency shortcut", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                Text("1. Pair a CivicRelay trusted person. 2. Arm the one-time SOS. " +
                    "3. Add the SOS shortcut to Quick Settings. 4. Keep Nearby sharing active " +
                    "and tap the tile to queue it. It is NOT an automatic SMS or live GPS alert.",
                    style = MaterialTheme.typography.bodySmall)
                if (civic.vault.contacts().isEmpty()) {
                    Text("No CivicRelay recipient yet. SMS phone numbers do NOT count as encrypted-app contacts.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onManageAppContacts, modifier = Modifier.fillMaxWidth()) {
                        Text("Pair a CivicRelay contact")
                    }
                }
                if (!civic.mesh.active) Text("Nearby sharing is off. The tile can queue an alert, but it cannot reach a peer until a route is available.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text(if (civic.emergency.ready()) "READY until ${whenText(civic.emergency.expiry())}"
                     else "NOT ARMED", style = MaterialTheme.typography.labelMedium,
                    color = if (civic.emergency.ready()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error)
                Button(onClick = { execute { civic.armQuickSos() } },
                    enabled = civic.vault.contacts().isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text("Arm emergency shortcut")
                }
                var tileInstallStatus by remember { mutableStateOf("") }
                OutlinedButton(onClick = { activity.requestQuickSosTile { tileInstallStatus = it } },
                    modifier = Modifier.fillMaxWidth()) { Text("Add SOS shortcut to Quick Settings") }
                if (tileInstallStatus.isNotBlank()) Text(tileInstallStatus, style = MaterialTheme.typography.bodySmall)
                Text("Quick SOS is one-time: arm again after triggering or expiry. " +
                    "It queues encrypted data but never guarantees delivery. Test first with invented details.",
                    style = MaterialTheme.typography.labelSmall)
                if (civic.emergency.ready()) OutlinedButton(onClick = { execute { civic.stopQuickSos() } }) {
                    Text("Disarm shortcut")
                }
                Text("Alternative: Start Nearby and use SOS action in the Nearby notification. " +
                    "Android's multi-press Power-button emergency shortcut cannot be overridden by this app.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        Text("Queued is not delivered. Internet, SMS, and emergency-service calls are NOT initiated by this app.",
            color = MaterialTheme.colorScheme.error)
        ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Check in with trusted people", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                Text("Share your current location privately, without sending an emergency alert. This is one-time sharing, not continuous online tracking.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = { checkinConfirm = true }, modifier = Modifier.fillMaxWidth(),
                    enabled = civic.vault.contacts().isNotEmpty()) { Text("Share my current position once") }
                if (checkinStatus.isNotBlank()) Text(checkinStatus, style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider()
        Text("Optional cellular SMS fallback", style = MaterialTheme.typography.titleMedium)
        Text("SMS does not need internet, but requires a working cellular network. It is NOT end-to-end encrypted; the phone carrier can see its contents.")
        val savedSmsContacts = civic.vault.smsContacts()
        if (savedSmsContacts.isEmpty()) Text("No SMS recipients saved yet. Add a parent or trusted number below.",
            style = MaterialTheme.typography.bodySmall)
        else {
            Text("Choose a saved SMS contact", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                savedSmsContacts.forEach { (name, phone) ->
                    FilterChip(selected = smsPhone == phone, onClick = { smsPhone = phone },
                        label = { Text(name) })
                }
            }
        }
        FilledTonalButton(onClick = onManageSms, modifier = Modifier.fillMaxWidth()) {
            Text(if (savedSmsContacts.isEmpty()) "Add a parent or SMS contact" else "Manage saved SMS contacts")
        }
        OutlinedTextField(smsPhone, { smsPhone = it.take(25) },
            label = { Text("SMS recipient number") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
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
            Text(if (smsIncludeLocation) "Open SMS with location" else "Open SMS without location")
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
    if (checkinConfirm) AlertDialog(onDismissRequest = { checkinConfirm = false },
        title = { Text("Share location with trusted contacts?") },
        text = { Text("Your precise current coordinates are encrypted for every saved trusted contact. Nearby relays cannot read them. Delivery requires a route and is not guaranteed. The location will NOT appear on any public map.") },
        confirmButton = { TextButton(onClick = {
            checkinConfirm = false
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)
                checkinLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            else shareTrustedCheckIn()
        }) { Text("Share once") } },
        dismissButton = { TextButton(onClick = { checkinConfirm = false }) { Text("Cancel") } })
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
    var selectedCategory by remember { mutableStateOf("Community") }
    val profile = remember(civic.revision) { civic.vault.publicProfile() }
    val name = profile.optString("name")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Community", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Local updates from nearby devices · Messages are public and may spread further.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        ElevatedCard(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Share an update", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(if (name.isNotBlank()) "Posting as $name · Signed by your device"
                    else "Set your display name in More → Settings before posting",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Community", "Safety", "Help").forEach { category ->
                        FilterChip(selected = selectedCategory == category, onClick = { selectedCategory = category },
                            label = { Text(category) })
                    }
                }
                OutlinedTextField(text, { text = it.take(280) }, label = { Text("What did you observe?") },
                    placeholder = { Text("Share first-hand, verifiable information…") },
                    modifier = Modifier.fillMaxWidth(), minLines = 3)
                Button(onClick = {
                    val post = text
                    val category = selectedCategory
                    execute { civic.post(post, category) }
                    text = ""
                }, enabled = text.isNotBlank() && name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text("Publish to nearby community")
                }
                Text("A valid device signature does not prove a person's name or facts are verified.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
        Text("Latest nearby reports", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        val entries = civic.vault.records("bulletins")
        if (entries.isEmpty()) Text("No updates yet. Nearby sharing works without internet; distant sync requires a server.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
            items(entries) { item ->
                ElevatedCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                                Text(item.optString("displayName", "?").take(1).uppercase(),
                                    Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.optString("displayName").ifBlank { "Unidentified poster" }, fontWeight = FontWeight.Bold)
                                Text("${item.optString("role")} · ${item.optString("region")} · ${whenText(item.optLong("time"))}",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(item.optString("text"), style = MaterialTheme.typography.bodyLarge)
                        Text("${item.optString("category", "Community")} · ${if (item.optBoolean("postingKeySignatureVerified")) "Verified key signature" else "Legacy/unverified signature"} · Name not ID-verified",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
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
    var evidenceSection by remember { mutableStateOf("Library") }
    val direct = civic.mesh.evidence
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Evidence vault", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Capture securely. Review and verify your recordings. Share encrypted copies only with trusted people.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf("Library", "Camera", "Share").forEachIndexed { index, section ->
                SegmentedButton(shape = SegmentedButtonDefaults.itemShape(index, 3),
                    selected = evidenceSection == section, onClick = { evidenceSection = section }) { Text(section) }
            }
        }
        if (evidenceSection == "Camera") {
        Text("Capture a record", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
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
        }
        if (evidenceSection == "Share") {
        HorizontalDivider()
        Text("Share encrypted evidence", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Send a protected photo or video to a trusted person nearby. Both vaults must be unlocked to approve an evidence copy; Nearby can stay enabled in the background.",
            style = MaterialTheme.typography.bodySmall)
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
        if (contacts.isEmpty()) Text("No trusted people saved. Add one from More → Trusted people before sharing.",
            style = MaterialTheme.typography.bodySmall)
        contacts.forEach { (name, publicKey) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selectedKey == publicKey, onClick = { selectedKey = publicKey })
                Text(name + " (" + Crypto.fingerprint(publicKey).take(10) + "…)")
            }
        }
        }
        if (evidenceSection == "Library") {
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
        Text("${media.size} recent encrypted recordings", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold)
        if (media.isEmpty()) {
            ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("📷", style = MaterialTheme.typography.displaySmall)
                    Text("No recordings yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Open Camera to capture your first encrypted photo or video.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(onClick = { evidenceSection = "Camera" }) { Text("Open camera") }
                }
            }
        }
        media.forEach { item ->
            ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer) {
                            Text(if (item.optString("kind") == "photo") "📷" else "▶",
                                Modifier.padding(14.dp), style = MaterialTheme.typography.titleLarge)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (item.optString("kind") == "photo") "Encrypted photo" else "Encrypted video",
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(whenText(item.optLong("created")), color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Locked", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall)
                    }
                    Text(if (item.has("importedFrom")) "Received from a trusted contact" else "Captured on this device",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.optString("ocrSuggestion").isNotBlank()) {
                        Text("Recognized text: ${item.optString("ocrSuggestion").take(140)}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Button(enabled = !previewBusy, modifier = Modifier.fillMaxWidth(), onClick = {
                        viewVerified(item.getString("id"))
                    }) { Text("Verify and view") }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        confirmPlainExport = item.getString("id") to item.getString("kind")
                    }) { Text("Export an unencrypted copy") }
                }
            }
        }
        }
        if (evidenceSection == "Share") {
            civic.vault.records("media").take(20).forEach { item ->
                ElevatedCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${item.optString("kind").replaceFirstChar { it.uppercase() }} · ${whenText(item.optLong("created"))}",
                            fontWeight = FontWeight.SemiBold)
                        Button(enabled = selectedKey.isNotBlank() && civic.mesh.active &&
                            direct.trustedEndpointFor(selectedKey), onClick = {
                            execute { civic.offerNearbyEvidence(item.getString("id"), selectedKey) }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Send securely to selected person") }
                        OutlinedButton(enabled = selectedKey.isNotBlank(), onClick = {
                            exportEvidence(item.getString("id"), selectedKey)
                        }, modifier = Modifier.fillMaxWidth()) { Text("Save an encrypted copy to transfer") }
                    }
                }
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

@Composable private fun SafetyMapTab(civic: CivicService, execute: (() -> Unit) -> Unit) {
    val context = LocalContext.current
    var locality by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf(false) }
    val reports = civic.vault.records("incidents")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Detention reports", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Anonymous area-level reports only. Exact individual locations and names will never appear on a public map.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Report an area", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                OutlinedTextField(locality, { locality = it.take(80) }, label = { Text("Broad area / neighborhood") },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it.take(280) }, label = { Text("What was observed? (no names or exact addresses)") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(acknowledged, { acknowledged = it })
                    Text("I will not identify or reveal anyone's precise whereabouts", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = {
                    val l = locality; val d = description
                    execute { civic.saveIncident("Area report: $d", "", l) }
                    locality = ""; description = ""; acknowledged = false
                }, enabled = acknowledged && locality.isNotBlank() && description.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()) { Text("Save private area report") }
            }
        }
        ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Public situation map", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Not available yet: a public hotspot should require multiple independent corroborating reports, coarse geolocation, delays, moderation, and a deployed server. Private incident notes never publish automatically.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Local private reports: ${reports.size}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org")))
                }, modifier = Modifier.fillMaxWidth()) { Text("Open map (internet required)") }
            }
        }
        Text("Never assume reports are confirmed detentions. False or stale locations can place people at risk.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
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
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun startSelected(mode: org.civicrelay.app.mesh.NearbyMesh.Mode) {
        try {
            NearbyForegroundService.start(ctx, mode)
            // Notification permission is optional for the FGS, but makes radio status visible.
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (_: Exception) {
            permissionError = "Unable to start background Nearby service. Check permissions and device settings."
        }
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
        Text("Connect with nearby phones", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Share local updates and relay encrypted alerts without mobile data. Start Nearby once; it stays active while you use other apps, with a visible notification.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ElevatedCard(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("GET CONNECTED", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Text("Phone A", fontWeight = FontWeight.Bold)
                Button(onClick = { launchRole(org.civicrelay.app.mesh.NearbyMesh.Mode.ADVERTISE) },
                    modifier = Modifier.fillMaxWidth()) { Text("Make my phone visible") }
                Text("Phone B", fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { launchRole(org.civicrelay.app.mesh.NearbyMesh.Mode.DISCOVER) },
                    modifier = Modifier.fillMaxWidth()) { Text("Search for phones") }
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
        OutlinedButton(onClick = { NearbyForegroundService.stop(ctx) }, enabled = mesh.active,
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
        Text("Nearby continues with the vault locked, but only encrypted/public messages can be relayed. Open and unlock CivicRelay to read private alerts or transfer evidence. Bluetooth, Wi-Fi, battery limits, and Android may still interrupt service. Tap Stop to end sharing.",
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
