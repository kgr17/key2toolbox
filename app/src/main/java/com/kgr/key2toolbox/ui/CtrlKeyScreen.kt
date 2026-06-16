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
import com.kgr.key2toolbox.modules.CtrlKeyController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CtrlKeyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var keymapState by remember { mutableStateOf(CtrlKeyController.State.UNKNOWN) }
    var persisted by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            keymapState = CtrlKeyController.currentKeymapState()
            persisted = CtrlKeyController.isPersisted()
        }
    }

    ScreenScaffold(title = Screen.CtrlKey.title, onBack = onBack) {
        Text("Live keymap: ${keymapState.name}")
        Text("Persisted (runs at boot): ${if (persisted) "Yes" else "No"}")

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Enabled")
            Switch(
                checked = keymapState == CtrlKeyController.State.CTRL,
                enabled = !busy,
                onCheckedChange = { enable ->
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        if (enable) {
                            CtrlKeyController.enablePersist(context)
                            CtrlKeyController.applyLiveOn()
                        } else {
                            CtrlKeyController.disablePersist()
                            CtrlKeyController.applyLiveOff()
                        }
                        keymapState = CtrlKeyController.currentKeymapState()
                        persisted = CtrlKeyController.isPersisted()
                        busy = false
                        statusMessage =
                            "Ctrl remap ${if (enable) "enabled" else "disabled"}. " +
                                "Persisted: ${if (persisted) "yes" else "NO - check service.d write permissions"}."
                    }
                }
            )
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}
