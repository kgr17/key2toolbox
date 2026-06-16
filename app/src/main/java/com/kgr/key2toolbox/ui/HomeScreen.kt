package com.kgr.key2toolbox.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kgr.key2toolbox.core.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Navigation host: shows the home menu, or one of the per-module screens.
 */
@Composable
fun HomeScreen() {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

    BackHandler(enabled = currentScreen != Screen.Home) {
        currentScreen = Screen.Home
    }

    when (currentScreen) {
        Screen.Home -> HomeMenu(onNavigate = { currentScreen = it })
        Screen.CtrlKey -> CtrlKeyScreen(onBack = { currentScreen = Screen.Home })
        Screen.Zram -> ZramScreen(onBack = { currentScreen = Screen.Home })
        Screen.KbdLight -> KbdLightScreen(onBack = { currentScreen = Screen.Home })
        Screen.WirelessAdb -> WirelessAdbScreen(onBack = { currentScreen = Screen.Home })
        Screen.Dt2w -> Dt2wScreen(onBack = { currentScreen = Screen.Home })
    }
}

@Composable
private fun HomeMenu(onNavigate: (Screen) -> Unit) {
    val scope = rememberCoroutineScope()
    var rootAvailable by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            rootAvailable = RootShell.isRootAvailable()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Key2 Toolbox", style = MaterialTheme.typography.headlineMedium)

        Button(onClick = {
            scope.launch(Dispatchers.IO) {
                rootAvailable = RootShell.isRootAvailable()
            }
        }) {
            Text("Check root access")
        }

        when (rootAvailable) {
            null -> Text("Checking root access…")
            false -> Text("Root access not granted.", color = Color(0xFFE57373))
            true -> Text("Root access granted.", color = Color(0xFF81C784))
        }

        MenuEntry(Screen.CtrlKey.title) { onNavigate(Screen.CtrlKey) }
        MenuEntry(Screen.Zram.title) { onNavigate(Screen.Zram) }
        MenuEntry(Screen.KbdLight.title) { onNavigate(Screen.KbdLight) }
        MenuEntry(Screen.WirelessAdb.title) { onNavigate(Screen.WirelessAdb) }
        MenuEntry(Screen.Dt2w.title) { onNavigate(Screen.Dt2w) }
    }
}

@Composable
private fun MenuEntry(title: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
    }
}
