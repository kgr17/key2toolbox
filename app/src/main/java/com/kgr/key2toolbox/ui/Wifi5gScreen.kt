package com.kgr.key2toolbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kgr.key2toolbox.modules.WifiRegdomainController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun Wifi5gScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var forced by remember { mutableStateOf(false) }
    var country by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    suspend fun refresh() {
        val p = WifiRegdomainController.isPersisted()
        val c = WifiRegdomainController.currentCountry()
        withContext(Dispatchers.Main) { forced = p; country = c; loaded = true }
    }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { refresh() } }

    ScreenScaffold(title = Screen.Wifi5g.title, onBack = onBack) {
        Text(
            "On this build no EU WiFi region exposes any 5GHz hotspot (SoftAP) " +
                "channels - only the US region does. This forces the WiFi country " +
                "code to US so you can run a 5GHz hotspot, and re-applies it on every " +
                "boot. Needs root.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Caveats: this also applies to WiFi as a client - you lose 2.4GHz " +
                "channels 12-13 and EU-only 5GHz channels, so it can stop you " +
                "connecting to APs that use them. US allows the upper 5GHz channels " +
                "(149-165) that aren't licensed in the EU; for an EU-legal hotspot " +
                "keep it on the lower channels (36-48). Turn off to restore your " +
                "region.",
            style = MaterialTheme.typography.bodySmall
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Force US WiFi region")
            Switch(
                checked = forced,
                enabled = loaded && !busy,
                onCheckedChange = { checked ->
                    forced = checked
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        if (checked) WifiRegdomainController.enable(context)
                        else WifiRegdomainController.disable()
                        kotlinx.coroutines.delay(1500) // let the driver re-apply
                        refresh()
                        withContext(Dispatchers.Main) { busy = false }
                    }
                }
            )
        }

        Text(
            "Applied WiFi region: " + country.ifEmpty { "…" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
