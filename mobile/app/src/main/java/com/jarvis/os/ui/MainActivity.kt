package com.jarvis.os.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jarvis.os.service.JarvisVoiceService
import com.jarvis.os.sync.ApiCredentials
import com.jarvis.os.sync.CloudMemorySyncWorker
import com.jarvis.os.sync.QrPairingScanner
import com.jarvis.os.ui.components.CyberBottomNav
import com.jarvis.os.ui.components.TopTelemetryBar
import com.jarvis.os.ui.screens.AssistantScreen
import com.jarvis.os.ui.screens.DevicesScreen
import com.jarvis.os.ui.screens.SettingsScreen
import com.jarvis.os.ui.theme.CyberBorder
import com.jarvis.os.ui.theme.CyberSurface
import com.jarvis.os.ui.theme.ElectricCyan
import com.jarvis.os.ui.theme.JarvisCyberTheme
import com.jarvis.os.ui.theme.ObsidianDark
import com.jarvis.os.ui.theme.TextMuted
import com.jarvis.os.ui.theme.TextWhite

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        handleIntent(intent)

        setContent {
            JarvisCyberTheme {
                JarvisMobileScreen(
                    context = this,
                    onStartDaemon = { startVoiceDaemon() },
                    onStopDaemon = { stopVoiceDaemon() },
                    onRequestOverlay = { requestOverlayPermission() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (ApiCredentials.gemini(this).isNotBlank() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceDaemon()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == JarvisVoiceService.ACTION_START || intent?.action == "com.jarvis.os.action.START") {
            startVoiceDaemon()
        } else if (intent?.action == JarvisVoiceService.ACTION_STOP || intent?.action == "com.jarvis.os.action.STOP") {
            stopVoiceDaemon()
        } else if (intent?.action == "com.jarvis.os.action.SYNC_MEMORY") {
            CloudMemorySyncWorker.syncNow(this)
        }
    }

    private fun startVoiceDaemon() {
        val intent = Intent(this, JarvisVoiceService::class.java).apply {
            action = JarvisVoiceService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Starting Hey Jarvis standby", Toast.LENGTH_SHORT).show()
    }

    private fun stopVoiceDaemon() {
        val intent = Intent(this, JarvisVoiceService::class.java).apply {
            action = JarvisVoiceService.ACTION_STOP
        }
        startService(intent)
        Toast.makeText(this, "JARVIS Voice Daemon stopped", Toast.LENGTH_SHORT).show()
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }
}

@Composable
fun JarvisMobileScreen(
    context: Context,
    onStartDaemon: () -> Unit,
    onStopDaemon: () -> Unit,
    onRequestOverlay: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    var standbyActive by remember { mutableStateOf(JarvisVoiceService.isRunning) }

    val voicePrefs = remember { context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE) }
    var voiceStatus by remember { mutableStateOf(voicePrefs.getString("voice_status", "Tap Enable Hey Jarvis to begin") ?: "") }
    var executionStatus by remember { mutableStateOf(voicePrefs.getString("device_execution_status", "Phone · Connect PC to share execution") ?: "") }

    var isPaired by remember { mutableStateOf(QrPairingScanner.isPaired(context)) }
    var pairingInfo by remember { mutableStateOf(QrPairingScanner.getPairingPayload(context)) }
    var hasVoiceKey by remember { mutableStateOf(ApiCredentials.gemini(context).isNotBlank()) }
    var credentialSource by remember { mutableStateOf(ApiCredentials.source(context)) }

    var showSetupDialog by remember { mutableStateOf(!hasVoiceKey) }
    var showPairDialog by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var pairInputText by remember { mutableStateOf("") }
    var overlayGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
        )
    }

    // Lifecycle Observer to refresh status on resume
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                standbyActive = JarvisVoiceService.isRunning
                overlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.canDrawOverlays(context)
                } else true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Shared Preferences Listener for service updates
    DisposableEffect(voicePrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "voice_status") {
                voiceStatus = prefs.getString(key, "") ?: ""
                standbyActive = JarvisVoiceService.isRunning
            }
            if (key == "device_execution_status") {
                executionStatus = prefs.getString(key, "") ?: ""
            }
            if (key == "goodbye_close_requested") {
                (context as? android.app.Activity)?.finishAndRemoveTask()
            }
        }
        voicePrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { voicePrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // Core Runtime Permissions
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        overlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    Scaffold(
        bottomBar = {
            CyberBottomNav(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it }
            )
        },
        containerColor = ObsidianDark
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TopTelemetryBar(
                standbyActive = standbyActive,
                appVersion = "v1.3.0"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Crossfade(
                    targetState = selectedTab,
                    label = "TabTransition",
                    animationSpec = tween(durationMillis = 200)
                ) { tabIndex ->
                    when (tabIndex) {
                        0 -> AssistantScreen(
                            standbyActive = standbyActive,
                            voiceStatus = voiceStatus,
                            executionStatus = executionStatus,
                            hasVoiceKey = hasVoiceKey,
                            onToggleStandby = {
                                if (standbyActive) {
                                    onStopDaemon()
                                    standbyActive = false
                                } else {
                                    if (hasVoiceKey) {
                                        onStartDaemon()
                                        standbyActive = true
                                    } else {
                                        showSetupDialog = true
                                    }
                                }
                            },
                            onOpenSetup = { showSetupDialog = true },
                            onTalkTapped = {
                                if (!standbyActive) {
                                    if (hasVoiceKey) {
                                        onStartDaemon()
                                        standbyActive = true
                                    } else {
                                        showSetupDialog = true
                                    }
                                } else {
                                    Toast.makeText(
                                        context,
                                        "JARVIS is listening · Say “Hey Jarvis”",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                        1 -> DevicesScreen(
                            context = context,
                            isPaired = isPaired,
                            pairingInfo = pairingInfo,
                            overlayGranted = overlayGranted,
                            onScanPc = { showQrScanner = true },
                            onManualPair = { showPairDialog = true },
                            onSyncMemory = {
                                CloudMemorySyncWorker.schedule(context)
                                CloudMemorySyncWorker.syncNow(context)
                                Toast.makeText(context, "Memory sync dispatched", Toast.LENGTH_SHORT).show()
                            },
                            onOpenOverlay = {
                                onRequestOverlay()
                                overlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    Settings.canDrawOverlays(context)
                                } else true
                            },
                            onOpenAccessibility = {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            },
                            onOpenBrightness = {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }
                        )
                        2 -> SettingsScreen(
                            context = context,
                            hasVoiceKey = hasVoiceKey,
                            credentialSource = credentialSource,
                            onConfigureKeys = { showSetupDialog = true }
                        )
                    }
                }
            }
        }
    }

    // Dialogs
    if (showSetupDialog) {
        ApiKeySetupDialog(
            onDismiss = { showSetupDialog = false },
            onScanPc = {
                showSetupDialog = false
                showPairDialog = true
            },
            onSave = { gemini, optional ->
                try {
                    ApiCredentials.savePersonal(context, gemini, optional)
                    androidx.work.WorkManager.getInstance(context).cancelUniqueWork("jarvis_periodic_memory_sync")
                    onStopDaemon()
                    standbyActive = false
                    isPaired = false
                    pairingInfo = null
                    hasVoiceKey = true
                    credentialSource = "personal"
                    showSetupDialog = false
                    Toast.makeText(context, "Keys saved. Tap Enable Hey Jarvis to begin.", Toast.LENGTH_LONG).show()
                    null
                } catch (_: IllegalArgumentException) {
                    "Paste a complete Gemini key. Optional keys may be left blank."
                } catch (_: Exception) {
                    "Could not save keys securely. Please try again."
                }
            }
        )
    }

    if (showPairDialog) {
        AlertDialog(
            onDismissRequest = { showPairDialog = false },
            title = {
                Text(
                    text = "Pair with JARVIS Desktop",
                    color = TextWhite,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Button(
                        onClick = {
                            showPairDialog = false
                            showQrScanner = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)
                    ) {
                        Text(
                            text = "SCAN QR CODE WITH CAMERA",
                            color = ObsidianDark,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "— or paste JSON payload manually —",
                        fontSize = 12.sp,
                        color = TextMuted,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = pairInputText,
                        onValueChange = { pairInputText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Paste your PC pairing payload", color = TextMuted) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricCyan,
                            unfocusedBorderColor = CyberBorder,
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val payload = QrPairingScanner.processScanResult(context, pairInputText.trim())
                        if (payload != null) {
                            isPaired = true
                            pairingInfo = payload
                            hasVoiceKey = ApiCredentials.gemini(context).isNotBlank()
                            credentialSource = ApiCredentials.source(context)
                            showPairDialog = false
                            Toast.makeText(context, "Successfully paired with ${payload.deviceId}!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Invalid pairing payload", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)
                ) {
                    Text("Pair Now", color = ObsidianDark, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPairDialog = false }) {
                    Text("Cancel", color = TextMuted)
                }
            },
            containerColor = CyberSurface,
            shape = RoundedCornerShape(18.dp)
        )
    }

    if (showQrScanner) {
        QrScannerDialog(
            onDismissRequest = { showQrScanner = false },
            onPairingSuccess = { payload ->
                QrPairingScanner.savePairing(context, payload)
                isPaired = true
                pairingInfo = payload
                hasVoiceKey = ApiCredentials.gemini(context).isNotBlank()
                credentialSource = ApiCredentials.source(context)
                showQrScanner = false
                Toast.makeText(context, "Successfully paired with ${payload.deviceId}!", Toast.LENGTH_SHORT).show()
            }
        )
    }
}
