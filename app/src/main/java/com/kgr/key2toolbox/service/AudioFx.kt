package com.kgr.key2toolbox.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Global audio DSP (EQ + BassBoost + LoudnessEnhancer), hosted in the long-lived
 * accessibility-service process.
 *
 * Like LineageOS AudioFX, effects are attached to each media app's own audio
 * session (announced by the OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION broadcasts)
 * rather than only the global mix (session 0) - that's what actually processes
 * playback on Qualcomm ROMs. We also keep a session-0 set as a fallback.
 *
 * Four tuning profiles, auto-selected by output: "spk" (loudspeaker),
 * "wired" (3.5mm), "bt" (Bluetooth A2DP), "usb" (USB-C).
 */
class AudioFx(context: Context, private val prefs: SharedPreferences) {

    companion object {
        const val KEY_ENABLED = "audio_fx_enabled"
        fun kBand(prof: String, band: Int) = "eq_${prof}_$band"
        fun kBass(prof: String) = "bass_$prof"
        fun kLoud(prof: String) = "loud_$prof"

        /** Active output profile: "usb", "wired", "bt", or "spk" (priority in that order). */
        fun profileFor(am: AudioManager): String {
            var wired = false
            var bt = false
            var usb = false
            try {
                val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                for (d in outs) {
                    when (d.type) {
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET -> wired = true
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> bt = true
                        AudioDeviceInfo.TYPE_USB_HEADSET,
                        AudioDeviceInfo.TYPE_USB_DEVICE -> usb = true
                        else -> Unit // other output types (e.g. built-in speaker) don't set a flag
                    }
                }
            } catch (_: Throwable) {
            }
            return when {
                usb -> "usb"
                wired -> "wired"
                bt -> "bt"
                else -> "spk"
            }
        }

        /** Human-readable name for a profile key. */
        fun profileLabel(prof: String): String = when (prof) {
            "usb" -> "USB-C HEADPHONES"
            "wired" -> "WIRED HEADPHONES"
            "bt" -> "BLUETOOTH"
            else -> "SPEAKER"
        }

        /** Recommended defaults (millibels): fuller, clearer, louder. */
        fun defaultBandMb(prof: String, freqHz: Int): Int = when (prof) {
            "spk" -> when { // small speaker: lots of help across the board
                freqHz < 120 -> 600
                freqHz < 400 -> 250
                freqHz < 1500 -> 150
                freqHz < 6000 -> 350
                else -> 250
            }
            "bt" -> when { // BT often dull/compressed: a touch more bass + air
                freqHz < 120 -> 500
                freqHz < 400 -> 150
                freqHz < 1500 -> 0
                freqHz < 6000 -> 250
                else -> 200
            }
            else -> when { // wired / usb: gentle, near-flat headphone curve
                freqHz < 120 -> 400
                freqHz < 400 -> 100
                freqHz < 1500 -> 0
                freqHz < 6000 -> 200
                else -> 100
            }
        }

        fun defaultBass(prof: String): Int = when (prof) {
            "spk" -> 600
            "bt" -> 400
            else -> 300
        }

        /** Millibels of makeup gain. */
        fun defaultLoud(prof: String): Int = when (prof) {
            "spk" -> 700
            "bt" -> 400
            else -> 300
        }

        fun clamp(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v
    }

    private val ctx: Context = context.applicationContext
    private val am: AudioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    // One effect bundle per audio session id (0 = global fallback).
    private val sessions = HashMap<Int, EffectSet>()

    private var numBands = 0
    private var minLevel: Short = -1500
    private var maxLevel: Short = 1500
    private var bandInfoKnown = false

    private var devCb: AudioDeviceCallback? = null
    private var sessionRx: BroadcastReceiver? = null
    private var receiverRegistered = false

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    /** Reconcile everything with the master toggle + current prefs. */
    fun refresh() {
        worker.execute {
            if (enabled()) {
                registerSessionReceiver()
                registerDeviceCallback()
                ensureSession(0) // global fallback
                applyAll()
            } else {
                unregisterSessionReceiver()
                unregisterDeviceCallback()
                releaseAll()
            }
        }
    }

    fun shutdown() {
        worker.execute {
            unregisterSessionReceiver()
            unregisterDeviceCallback()
            releaseAll()
        }
        worker.shutdown()
    }

    // ----------------------------------------------------------- effect bundle

    private inner class EffectSet(session: Int) {
        var eq: Equalizer? = try {
            Equalizer(0, session)
        } catch (_: Throwable) {
            null
        }
        var bass: BassBoost? = try {
            BassBoost(0, session)
        } catch (_: Throwable) {
            null
        }
        var loud: LoudnessEnhancer? = try {
            LoudnessEnhancer(session)
        } catch (_: Throwable) {
            null
        }

        init {
            val e = eq
            if (e != null && !bandInfoKnown) {
                try {
                    numBands = e.numberOfBands.toInt()
                    val r = e.bandLevelRange
                    minLevel = r[0]
                    maxLevel = r[1]
                    bandInfoKnown = true
                } catch (_: Throwable) {
                }
            }
        }

        fun apply(prof: String) {
            try {
                val e = eq
                if (e != null) {
                    e.enabled = true
                    val n = if (numBands > 0) numBands else e.numberOfBands.toInt()
                    for (b in 0 until n) {
                        val band = b.toShort()
                        val mb = prefs.getInt(
                            kBand(prof, b),
                            defaultBandMb(prof, e.getCenterFreq(band) / 1000)
                        )
                        e.setBandLevel(band, clamp(mb, minLevel.toInt(), maxLevel.toInt()).toShort())
                    }
                }
            } catch (_: Throwable) {
            }
            try {
                val bb = bass
                if (bb != null && bb.strengthSupported) {
                    bb.enabled = true
                    bb.setStrength(
                        clamp(prefs.getInt(kBass(prof), defaultBass(prof)), 0, 1000).toShort()
                    )
                }
            } catch (_: Throwable) {
            }
            try {
                val l = loud
                if (l != null) {
                    l.enabled = true
                    l.setTargetGain(clamp(prefs.getInt(kLoud(prof), defaultLoud(prof)), 0, 2000))
                }
            } catch (_: Throwable) {
            }
        }

        fun release() {
            try {
                eq?.release()
            } catch (_: Throwable) {
            }
            try {
                bass?.release()
            } catch (_: Throwable) {
            }
            try {
                loud?.release()
            } catch (_: Throwable) {
            }
            eq = null
            bass = null
            loud = null
        }
    }

    private fun ensureSession(session: Int) {
        if (sessions.containsKey(session)) return
        sessions[session] = EffectSet(session)
    }

    private fun releaseSession(session: Int) {
        sessions.remove(session)?.release()
    }

    private fun applyAll() {
        val prof = activeProfile()
        sessions.values.forEach { it.apply(prof) }
    }

    private fun releaseAll() {
        sessions.values.forEach { it.release() }
        sessions.clear()
    }

    // -------------------------------------------------- per-session broadcasts

    private fun registerSessionReceiver() {
        if (receiverRegistered) return
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent) {
                val session = intent.getIntExtra(
                    AudioEffect.EXTRA_AUDIO_SESSION,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )
                val open = AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION == intent.action
                if (session == AudioManager.AUDIO_SESSION_ID_GENERATE) return
                worker.execute {
                    if (!enabled()) return@execute
                    if (open) {
                        ensureSession(session)
                        sessions[session]?.apply(activeProfile())
                    } else {
                        releaseSession(session)
                    }
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        try {
            ContextCompat.registerReceiver(ctx, rx, f, ContextCompat.RECEIVER_EXPORTED)
            sessionRx = rx
            receiverRegistered = true
        } catch (_: Throwable) {
        }
    }

    private fun unregisterSessionReceiver() {
        if (receiverRegistered) {
            try {
                sessionRx?.let { ctx.unregisterReceiver(it) }
            } catch (_: Throwable) {
            }
        }
        receiverRegistered = false
        sessionRx = null
    }

    // ------------------------------------------------------- output detection

    /** Active output profile: "usb", "wired", "bt", or "spk" (priority in that order). */
    fun activeProfile(): String = profileFor(am)

    private fun registerDeviceCallback() {
        if (devCb != null) return
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                refresh()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                refresh()
            }
        }
        devCb = cb
        am.registerAudioDeviceCallback(cb, main)
    }

    private fun unregisterDeviceCallback() {
        devCb?.let {
            try {
                am.unregisterAudioDeviceCallback(it)
            } catch (_: Throwable) {
            }
        }
        devCb = null
    }

    // ---- band info for the UI (probe a transient global instance if needed)

    fun bandCount(): Int = numBands
    fun minLevel(): Short = minLevel
    fun maxLevel(): Short = maxLevel
}
