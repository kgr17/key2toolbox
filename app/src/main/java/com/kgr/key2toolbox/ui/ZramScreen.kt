package com.kgr.key2toolbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kgr.key2toolbox.modules.ZramController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ZramScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedSize by remember { mutableStateOf(ZramController.Size.OFF) }
    var availableAlgorithms by remember { mutableStateOf(listOf(ZramController.DEFAULT_ALGORITHM)) }
    var selectedAlgorithm by remember { mutableStateOf(ZramController.DEFAULT_ALGORITHM) }
    var liveSizeBytes by remember { mutableStateOf<Long?>(null) }
    var liveAlgorithm by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showApplyWarning by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            selectedSize = ZramController.persistedSize() ?: ZramController.Size.OFF
            availableAlgorithms = ZramController.availableAlgorithms()
            selectedAlgorithm = ZramController.persistedAlgorithm()
                ?: ZramController.currentAlgorithm()
                ?: ZramController.DEFAULT_ALGORITHM
            liveSizeBytes = ZramController.currentLiveSizeBytes()
            liveAlgorithm = ZramController.currentAlgorithm()
        }
    }

    ScreenScaffold(title = Screen.Zram.title, onBack = onBack) {
        Text(
            "Current live size: " +
                (liveSizeBytes?.let { "${it / 1024 / 1024} MB" } ?: "unknown / inactive")
        )
        Text("Current live algorithm: ${liveAlgorithm ?: "unknown"}")
        Text("Persisted setting (applies on next reboot): ${selectedSize.label}, ${selectedAlgorithm}")

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Compression algorithm", style = MaterialTheme.typography.titleMedium)

                availableAlgorithms.forEach { algo ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selectedAlgorithm == algo,
                            enabled = !busy,
                            onClick = {
                                selectedAlgorithm = algo
                                if (selectedSize != ZramController.Size.OFF) {
                                    busy = true
                                    scope.launch(Dispatchers.IO) {
                                        ZramController.setSize(context, selectedSize, algo, applyLive = false)
                                        busy = false
                                        statusMessage = "ZRAM algorithm set to $algo. Will apply on next reboot."
                                    }
                                }
                            }
                        )
                        Text(algo)
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Size", style = MaterialTheme.typography.titleMedium)

                ZramController.Size.entries.forEach { size ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selectedSize == size,
                            enabled = !busy,
                            onClick = {
                                selectedSize = size
                                busy = true
                                scope.launch(Dispatchers.IO) {
                                    ZramController.setSize(context, size, selectedAlgorithm, applyLive = false)
                                    busy = false
                                    statusMessage = "ZRAM set to ${size.label}. Will apply on next reboot."
                                }
                            }
                        )
                        Text(size.label)
                    }
                }
            }
        }

        Button(
            enabled = !busy && selectedSize != ZramController.Size.OFF,
            onClick = { showApplyWarning = true }
        ) {
            Text("Apply now")
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        if (showApplyWarning) {
            AlertDialog(
                onDismissRequest = { showApplyWarning = false },
                title = { Text("Apply ZRAM settings now?") },
                text = {
                    Text(
                        "Resizing/recompressing ZRAM live briefly disables swap and can " +
                            "cause background apps to be killed under memory pressure. " +
                            "It's usually safer to set this and reboot instead."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showApplyWarning = false
                        busy = true
                        scope.launch(Dispatchers.IO) {
                            ZramController.setSize(context, selectedSize, selectedAlgorithm, applyLive = true)
                            liveSizeBytes = ZramController.currentLiveSizeBytes()
                            liveAlgorithm = ZramController.currentAlgorithm()
                            busy = false
                            statusMessage = "ZRAM applied live: ${selectedSize.label}, $selectedAlgorithm."
                        }
                    }) { Text("Apply") }
                },
                dismissButton = {
                    TextButton(onClick = { showApplyWarning = false }) { Text("Cancel") }
                }
            )
        }
    }
}
