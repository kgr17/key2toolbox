package com.kgr.key2toolbox.ui

import android.content.Context
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kgr.key2toolbox.service.Key2AccessibilityService
import com.kgr.key2toolbox.service.isKey2AccessibilityServiceEnabled

@Composable
fun NavLockScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(Key2AccessibilityService.PREFS, Context.MODE_PRIVATE)
    }

    var serviceEnabled by remember { mutableStateOf(false) }
    var navLock by remember { mutableStateOf(prefs.getBoolean(Key2AccessibilityService.KEY_NAV_LOCK, true)) }
    var gestureMode by remember { mutableStateOf(prefs.getBoolean(Key2AccessibilityService.KEY_NAV_GESTURE, false)) }
    var alwaysOff by remember { mutableStateOf(prefs.getBoolean(Key2AccessibilityService.KEY_NAV_ALWAYS_OFF, false)) }

    LaunchedEffect(Unit) {
        serviceEnabled = isKey2AccessibilityServiceEnabled(context)
    }

    ScreenScaffold(title = Screen.NavLock.title, onBack = onBack) {
        AccessibilityServiceBanner(serviceEnabled)

        Text(
            "Stops accidental Back / Home / Recents presses while the keyboard is " +
                "showing. Pick a mode below.",
            style = MaterialTheme.typography.bodySmall
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Keyboard Nav Lock")
            Switch(
                checked = navLock,
                onCheckedChange = { checked ->
                    navLock = checked
                    prefs.edit().putBoolean(Key2AccessibilityService.KEY_NAV_LOCK, checked).apply()
                }
            )
        }

        Text(
            "Double-tap Back (keep button): while typing, a single tap on Back is " +
                "ignored - only a double-tap fires it. Only Back works this way; " +
                "Android won't let an app gate Home/Recents. Off = all three buttons " +
                "are fully disabled while typing (needs root).",
            style = MaterialTheme.typography.bodySmall
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Double-tap Back")
            Switch(
                checked = gestureMode,
                onCheckedChange = { checked ->
                    gestureMode = checked
                    prefs.edit().putBoolean(Key2AccessibilityService.KEY_NAV_GESTURE, checked).apply()
                }
            )
        }

        Text(
            "Disable nav buttons ALWAYS: keeps Back / Home / Recents disabled " +
                "permanently, not just while typing. Navigate with gestures instead. " +
                "Overrides the modes above; turn off to restore the buttons. Needs root.",
            style = MaterialTheme.typography.bodySmall
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Disable ALWAYS")
            Switch(
                checked = alwaysOff,
                onCheckedChange = { checked ->
                    alwaysOff = checked
                    prefs.edit().putBoolean(Key2AccessibilityService.KEY_NAV_ALWAYS_OFF, checked).apply()
                }
            )
        }
    }
}
