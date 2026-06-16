package com.kgr.key2toolbox.ui

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.Equalizer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.service.AudioFx
import com.kgr.key2toolbox.service.Key2AccessibilityService
import com.kgr.key2toolbox.service.isKey2AccessibilityServiceEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LINEAGE_AUDIOFX = "org.lineageos.audiofx"

private data class BandInfo(val band: Short, val freqHz: Int)

@Composable
fun AudioFxScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember {
        context.getSharedPreferences(Key2AccessibilityService.PREFS, Context.MODE_PRIVATE)
    }

    var serviceEnabled by remember { mutableStateOf(false) }
    var fxEnabled by remember { mutableStateOf(prefs.getBoolean(AudioFx.KEY_ENABLED, false)) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    var lineageInstalled by remember { mutableStateOf<Boolean?>(null) }
    var lineageDisabled by remember { mutableStateOf<Boolean?>(null) }

    var profile by remember { mutableStateOf("spk") }
    var bands by remember { mutableStateOf(listOf<BandInfo>()) }
    var minLevel by remember { mutableStateOf<Short>(-1500) }
    var maxLevel by remember { mutableStateOf<Short>(1500) }
    var probeFailed by remember { mutableStateOf(false) }

    // Slider values, keyed by pref key so they survive profile display without remapping.
    var bandValues by remember { mutableStateOf(mapOf<String, Int>()) }
    var bassValue by remember { mutableStateOf(0) }
    var loudValue by remember { mutableStateOf(0) }

    fun loadProfileSliders(prof: String, bandList: List<BandInfo>) {
        bandValues = bandList.associate { b ->
            val key = AudioFx.kBand(prof, b.band.toInt())
            key to prefs.getInt(key, AudioFx.defaultBandMb(prof, b.freqHz))
        }
        bassValue = prefs.getInt(AudioFx.kBass(prof), AudioFx.defaultBass(prof))
        loudValue = prefs.getInt(AudioFx.kLoud(prof), AudioFx.defaultLoud(prof))
    }

    fun refreshLineageStatus() {
        scope.launch(Dispatchers.IO) {
            val installedOut = RootShell.run("pm list packages").outString
            val disabledOut = RootShell.run("pm list packages -d").outString
            lineageInstalled = installedOut.contains(LINEAGE_AUDIOFX)
            lineageDisabled = disabledOut.contains(LINEAGE_AUDIOFX)
        }
    }

    LaunchedEffect(Unit) {
        serviceEnabled = isKey2AccessibilityServiceEnabled(context)

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        profile = AudioFx.profileFor(am)

        // Probe a transient Equalizer instance just to learn band count/range/centers.
        var probe: Equalizer? = null
        try {
            probe = Equalizer(0, 0)
            val n = probe.numberOfBands
            val range = probe.bandLevelRange
            minLevel = range[0]
            maxLevel = range[1]
            val list = (0 until n).map { b ->
                val band = b.toShort()
                BandInfo(band, probe.getCenterFreq(band) / 1000)
            }
            bands = list
            loadProfileSliders(profile, list)
        } catch (t: Throwable) {
            probeFailed = true
        } finally {
            try {
                probe?.release()
            } catch (ignored: Throwable) {
            }
        }

        refreshLineageStatus()
    }

    ScreenScaffold(title = Screen.AudioFx.title, onBack = onBack) {
        AccessibilityServiceBanner(serviceEnabled)

        Text(
            "System-wide equalizer, bass boost and loudness. Auto-switches tuning " +
                "by output (speaker / wired / Bluetooth / USB-C). The EQ itself needs " +
                "no root - only disabling the conflicting LineageOS AudioFX below does.",
            style = MaterialTheme.typography.bodySmall
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Audio FX enabled")
            Switch(
                checked = fxEnabled,
                onCheckedChange = { checked ->
                    fxEnabled = checked
                    prefs.edit().putBoolean(AudioFx.KEY_ENABLED, checked).apply()
                    // Our effects and LineageOS AudioFX fight over the same sessions;
                    // disable theirs automatically when ours turns on.
                    if (checked && lineageInstalled == true && lineageDisabled == false) {
                        busy = true
                        scope.launch(Dispatchers.IO) {
                            RootShell.run("pm disable-user --user 0 $LINEAGE_AUDIOFX")
                            withContext(Dispatchers.Main) { busy = false }
                            refreshLineageStatus()
                        }
                    }
                }
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("LineageOS AudioFX conflict", style = MaterialTheme.typography.titleMedium)

                val (label, color) = when {
                    lineageInstalled == null -> "Checking…" to Color(0xFFB0B0B0)
                    lineageInstalled == false -> "Not present \u2713 (no conflict)" to Color(0xFF81C784)
                    lineageDisabled == true -> "Disabled \u2713 (no conflict)" to Color(0xFF81C784)
                    else -> "Enabled \u26A0 - conflicts with Audio FX above" to Color(0xFFE57373)
                }
                Text(label, color = color)

                Button(
                    enabled = !busy && lineageInstalled == true,
                    onClick = {
                        val disable = lineageDisabled != true
                        busy = true
                        scope.launch(Dispatchers.IO) {
                            RootShell.run(
                                if (disable) "pm disable-user --user 0 $LINEAGE_AUDIOFX"
                                else "pm enable $LINEAGE_AUDIOFX"
                            )
                            withContext(Dispatchers.Main) { busy = false }
                            refreshLineageStatus()
                        }
                    }
                ) {
                    Text("Disable / enable LineageOS AudioFX")
                }
            }
        }

        if (probeFailed) {
            Text(
                "Audio effects are not available on this ROM.",
                color = Color(0xFFE57373)
            )
        } else if (fxEnabled) {
            Text(
                "Editing tuning for: ${AudioFx.profileLabel(profile)} (auto-selected by output)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            bands.forEach { b ->
                val key = AudioFx.kBand(profile, b.band.toInt())
                val freqLabel = if (b.freqHz >= 1000) "${b.freqHz / 1000} kHz" else "${b.freqHz} Hz"
                val value = bandValues[key] ?: AudioFx.defaultBandMb(profile, b.freqHz)
                LabeledSlider(
                    label = "EQ $freqLabel",
                    value = value,
                    min = minLevel.toInt(),
                    max = maxLevel.toInt(),
                    divLabel = 100,
                    suffix = " dB",
                    onChange = { newVal ->
                        bandValues = bandValues + (key to newVal)
                        prefs.edit().putInt(key, newVal).apply()
                    }
                )
            }

            LabeledSlider(
                label = "Bass Boost",
                value = bassValue,
                min = 0,
                max = 1000,
                divLabel = 1,
                suffix = "",
                onChange = { newVal ->
                    bassValue = newVal
                    prefs.edit().putInt(AudioFx.kBass(profile), newVal).apply()
                }
            )

            LabeledSlider(
                label = "Loudness (makeup gain)",
                value = loudValue,
                min = 0,
                max = 2000,
                divLabel = 100,
                suffix = " dB",
                onChange = { newVal ->
                    loudValue = newVal
                    prefs.edit().putInt(AudioFx.kLoud(profile), newVal).apply()
                }
            )

            Button(onClick = {
                val editor = prefs.edit()
                bands.forEach { b ->
                    editor.putInt(AudioFx.kBand(profile, b.band.toInt()), AudioFx.defaultBandMb(profile, b.freqHz))
                }
                editor.putInt(AudioFx.kBass(profile), AudioFx.defaultBass(profile))
                editor.putInt(AudioFx.kLoud(profile), AudioFx.defaultLoud(profile))
                editor.apply()
                loadProfileSliders(profile, bands)
                statusMessage = "Reset ${AudioFx.profileLabel(profile)} to recommended."
            }) {
                Text("Reset this profile to recommended")
            }
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    divLabel: Int,
    suffix: String,
    onChange: (Int) -> Unit
) {
    Column {
        Text(formatSliderLabel(label, value, divLabel, suffix), style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value.toFloat(),
            valueRange = min.toFloat()..max.toFloat(),
            onValueChange = { onChange(it.toInt()) }
        )
    }
}

private fun formatSliderLabel(label: String, value: Int, div: Int, suffix: String): String {
    if (div == 1) return "$label: $value$suffix"
    val d = value / div.toDouble()
    val num = if (d == Math.rint(d)) d.toInt().toString() else String.format("%.1f", d)
    val sign = if (value > 0) "+" else ""
    return "$label: $sign$num$suffix"
}
