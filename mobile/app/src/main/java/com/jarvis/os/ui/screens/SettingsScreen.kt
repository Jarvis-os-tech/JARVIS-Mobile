package com.jarvis.os.ui.screens

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.os.ui.theme.CyberBorder
import com.jarvis.os.ui.theme.CyberSurface
import com.jarvis.os.ui.theme.ElectricCyan
import com.jarvis.os.ui.theme.ObsidianDark
import com.jarvis.os.ui.theme.TextMuted
import com.jarvis.os.ui.theme.TextWhite
import com.jarvis.os.update.MobileUpdater
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    context: Context,
    hasVoiceKey: Boolean,
    credentialSource: String,
    onConfigureKeys: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var updateBusy by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // SECTION 1: AI & CREDENTIALS VAULT
        Text(
            text = "AI INTELLIGENCE & CREDENTIALS",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = ElectricCyan,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = CyberSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, CyberBorder)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = if (hasVoiceKey) "Gemini 2.0 Flash / Live Active" else "API Key Missing",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
                Text(
                    text = if (!hasVoiceKey) "Setup required for voice synthesis and reasoning"
                    else if (credentialSource == "pc") "Credential Source: Synchronized from PC"
                    else "Credential Source: Personal API Key",
                    fontSize = 12.sp,
                    color = TextMuted
                )

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = onConfigureKeys,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)
                ) {
                    Text(
                        text = if (hasVoiceKey) "MANAGE API KEYS" else "CONFIGURE API KEY",
                        color = ObsidianDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // SECTION 2: OTA SOFTWARE UPDATES
        Text(
            text = "SOFTWARE & UPDATES",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = ElectricCyan,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = CyberSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, CyberBorder)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Current Build", fontSize = 14.sp, color = TextWhite)
                    Text(text = "v1.2.3 (Debug)", fontSize = 13.sp, color = ElectricCyan, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Updates download directly over Wi-Fi or mobile data from GitHub releases.",
                    fontSize = 11.sp,
                    color = TextMuted
                )

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            updateBusy = true
                            try {
                                updateStatus = MobileUpdater(context).update { updateStatus = it }
                            } catch (_: Exception) {
                                updateStatus = "Update check failed. Check network connection."
                            } finally {
                                updateBusy = false
                            }
                        }
                    },
                    enabled = !updateBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyberBorder)
                ) {
                    Text(
                        text = if (updateBusy) "Checking Releases…" else "CHECK FOR UPDATES",
                        color = TextWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (updateStatus.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = updateStatus, fontSize = 11.sp, color = TextMuted)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // SECTION 3: AUDIO TELEMETRY
        Text(
            text = "HARDWARE & AUDIO TELEMETRY",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = ElectricCyan,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = CyberSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, CyberBorder)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                TelemetryRow("Sampling Rate", "16,000 Hz Mono PCM")
                Spacer(modifier = Modifier.height(8.dp))
                TelemetryRow("Echo Cancellation", "Hardware AEC Active")
                Spacer(modifier = Modifier.height(8.dp))
                TelemetryRow("Wake Engine", "Vosk Gated KWS")
                Spacer(modifier = Modifier.height(8.dp))
                TelemetryRow("Standby Power Mode", "Zero-Allocation VAD")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun TelemetryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 13.sp, color = TextMuted)
        Text(text = value, fontSize = 12.sp, color = TextWhite, fontWeight = FontWeight.Medium)
    }
}
