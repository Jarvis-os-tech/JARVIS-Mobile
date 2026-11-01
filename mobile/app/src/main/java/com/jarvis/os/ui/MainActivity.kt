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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.jarvis.os.overlay.HaloOrb
import com.jarvis.os.overlay.OverlayUiState
import com.jarvis.os.service.JarvisVoiceService
import com.jarvis.os.sync.CloudMemorySyncWorker
import com.jarvis.os.sync.ApiCredentials
import com.jarvis.os.sync.QrPairingScanner
import com.jarvis.os.update.MobileUpdater
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        handleIntent(intent)

        setContent {
            JarvisMobileScreen(
                context = this,
                onStartDaemon = { startVoiceDaemon() },
                onStopDaemon = { stopVoiceDaemon() },
                onRequestOverlay = { requestOverlayPermission() }
            )
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
    val voicePrefs = remember { context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE) }
    val updateScope = rememberCoroutineScope()
    var updateBusy by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf("") }
    var voiceStatus by remember { mutableStateOf(voicePrefs.getString("voice_status", "Tap Enable Hey Jarvis to begin") ?: "") }
    var executionStatus by remember { mutableStateOf(voicePrefs.getString("device_execution_status", "Phone · Connect PC to share execution") ?: "") }
    DisposableEffect(voicePrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "voice_status") voiceStatus = prefs.getString(key, "") ?: ""
            if (key == "device_execution_status") executionStatus = prefs.getString(key, "") ?: ""
            if (key == "goodbye_close_requested") (context as? android.app.Activity)?.finishAndRemoveTask()
        }
        voicePrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { voicePrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
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

    // Permission Request Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Refreshed on return
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA,
            Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF090A0E)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Text(
                text = "JARVIS OS",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 2.sp
            )
            Text(
                text = "Say Hey Jarvis. Your assistant is here.",
                fontSize = 12.sp,
                color = Color(0xFF00F2FE),
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(36.dp))

            OutlinedButton(onClick = { showSetupDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (hasVoiceKey) "API keys and setup" else "Set up JARVIS")
            }
            Text(if (!hasVoiceKey) "Gemini key required · Scan PC QR or use your own key"
                else if (credentialSource == "pc") "Voice key: From your PC" else "Voice key: Your Gemini key",
                color = Color.Gray, fontSize = 12.sp)

            // Ambient Halo Orb
            HaloOrb(
                size = 180.dp,
                state = OverlayUiState.Idle
            )

            Text(text = voiceStatus, color = Color(0xFF00F2FE), fontSize = 14.sp,
                modifier = Modifier.padding(top = 16.dp))
            Text(text = "Wake with “Hey Jarvis”, pause for the greeting, then ask. Returns to standby after 15 seconds of inactivity.",
                color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Text(text = executionStatus, color = Color(0xFF00F2FE), fontSize = 13.sp,
                modifier = Modifier.padding(top = 12.dp))
            Text(text = "Say “on my phone” or “on my PC” to choose where an action runs.",
                color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))

            Spacer(modifier = Modifier.height(36.dp))

            // System Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF14161E)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x3300F2FE))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "SYSTEM STATUS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.Gray,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    StatusRow(
                        title = "Desktop Pairing",
                        value = if (isPaired) "Paired (${pairingInfo?.deviceId})" else "Not Paired",
                        active = isPaired
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    StatusRow(
                        title = "Overlay Permission",
                        value = if (overlayGranted) "Granted" else "Requires Setup",
                        active = overlayGranted
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    StatusRow(
                        title = "Cloud Memory Sync",
                        value = if (isPaired) "Paired PC" else "Optional · PC not paired",
                        active = isPaired
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Actions Section
            Button(
                onClick = { if (hasVoiceKey) onStartDaemon() else showSetupDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00F2FE))
            ) {
                Text(
                    text = "ENABLE HEY JARVIS",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = { onStopDaemon() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text(text = "TURN OFF ASSISTANT")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { showPairDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(text = "Pair Desktop", fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = {
                        CloudMemorySyncWorker.schedule(context)
                        CloudMemorySyncWorker.syncNow(context)
                        Toast.makeText(context, "Memory sync dispatched", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(text = "Sync Cloud", fontSize = 13.sp)
                }
            }

            if (!overlayGranted) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        onRequestOverlay()
                        overlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            Settings.canDrawOverlays(context)
                        } else true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9B51E0))
                ) {
                    Text(text = "Enable Floating Siri Overlay", color = Color.White)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Enable voice navigation", color = Color.White)
            }
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Enable brightness control", color = Color.White)
            }
            Text("Contacts and calls use this phone. Voice navigation lets JARVIS follow your commands inside apps.",
                color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                updateScope.launch {
                    updateBusy = true
                    try {
                        updateMessage = MobileUpdater(context).update { updateMessage = it }
                    } catch (_: Exception) {
                        updateMessage = "Update unavailable. Check your internet connection and try again."
                    } finally { updateBusy = false }
                }
            }, enabled = !updateBusy, modifier = Modifier.fillMaxWidth()) {
                Text(if (updateBusy) "Checking update…" else "Update JARVIS")
            }
            Text("Get phone updates over Wi-Fi or mobile data. No PC connection needed.", color = Color.Gray, fontSize = 12.sp)
            if (updateMessage.isNotBlank()) Text(updateMessage, color = Color.Gray, fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp))
        }
    }

    if (showSetupDialog) {
        ApiKeySetupDialog(onDismiss = { showSetupDialog = false }, onScanPc = {
            showSetupDialog = false
            showPairDialog = true
        }, onSave = { gemini, optional ->
            try {
                ApiCredentials.savePersonal(context, gemini, optional)
                androidx.work.WorkManager.getInstance(context).cancelUniqueWork("jarvis_periodic_memory_sync")
                onStopDaemon()
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
        })
    }

    // Pairing Dialog
    if (showPairDialog) {
        AlertDialog(
            onDismissRequest = { showPairDialog = false },
            title = { Text(text = "Pair with JARVIS Desktop") },
            text = {
                Column {
                    Button(
                        onClick = {
                            showPairDialog = false
                            showQrScanner = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00F2FE))
                    ) {
                        Text("SCAN QR CODE WITH CAMERA", color = Color.Black, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "— or paste JSON payload manually —",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = pairInputText,
                        onValueChange = { pairInputText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Paste your PC pairing payload") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        maxLines = 4
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
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
                }) {
                    Text("Pair Now")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPairDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Camera QR Scanner View
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

@Composable
fun StatusRow(title: String, value: String, active: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, color = Color.White, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (active) Color(0xFF00F2FE) else Color(0xFFFF5252))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = value,
                color = if (active) Color(0xFFE0E0E0) else Color.Gray,
                fontSize = 13.sp
            )
        }
    }
}
