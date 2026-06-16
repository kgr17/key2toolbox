package com.kgr.key2toolbox.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Intent
import android.content.SharedPreferences
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.kgr.key2toolbox.core.RootShell
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Combined accessibility service for the ported nozerorma Key2 Tweaks features.
 *
 * - Nav Lock: while the on-screen keyboard (IME) is visible, stops accidental
 *   Back / Home / Recents presses. Two modes:
 *     * Disable (root): cut the capacitive keys via the sysfs node
 *       /sys/class/input/eventN/device/0dbutton (1 = on, 0 = off), resolved by
 *       device name (synaptics_dsx_2) so it survives reboots.
 *     * Gesture (no root): keep the keys live but gate BACK in onKeyEvent - a
 *       single tap is swallowed; only a double-tap fires it. Only Back is
 *       gateable; Home/Recents are acted on by the window policy regardless of
 *       accessibility consumption.
 *
 * - PIN Input: on the lockscreen, maps physical-keyboard presses to taps on the
 *   SystemUI PIN pad so the PIN can be typed on the hardware keyboard.
 *
 * Each feature has an independent toggle stored in SharedPreferences ("key2tweaks").
 * Root writes go through RootShell (libsu) rather than a raw su process, to share
 * one root-execution path with the rest of the app.
 */
class Key2AccessibilityService : AccessibilityService() {

    companion object {
        const val PREFS = "key2tweaks"
        const val KEY_NAV_LOCK = "nav_lock_enabled"
        const val KEY_NAV_GESTURE = "nav_gesture_mode" // false=disable buttons, true=double-tap gate (Back)
        const val KEY_NAV_ALWAYS_OFF = "nav_always_off" // disable nav buttons permanently
        const val KEY_PIN_INPUT = "pin_input_enabled"

        private const val LONG_PRESS_MS = 350L
        private const val DOUBLE_TAP_MS = 300L
    }

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var navDisabled = false // last state pushed to kernel
    @Volatile private var imeActive = false   // keyboard currently showing
    private val lastNavTap = HashMap<Int, Long>() // keycode -> last short-tap time
    private var prefs: SharedPreferences? = null
    private var audioFx: AudioFx? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null) return@OnSharedPreferenceChangeListener
        if (key == KEY_NAV_LOCK || key == KEY_NAV_GESTURE || key == KEY_NAV_ALWAYS_OFF) {
            reconcileNav()
        }
        val fx = audioFx
        if (fx != null && (key == AudioFx.KEY_ENABLED || key.startsWith("eq_") ||
                    key.startsWith("bass_") || key.startsWith("loud_"))) {
            fx.refresh()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs = p
        p.registerOnSharedPreferenceChangeListener(prefListener)

        serviceInfo?.let { info ->
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            serviceInfo = info
        }

        // Seed navDisabled from the real kernel state rather than assuming
        // false - if the node is already disabled (e.g. left that way from
        // a previous session) and the in-memory flag defaults to "enabled",
        // the very first reconcile could conclude no change is needed and
        // silently skip a write that's actually required.
        worker.execute {
            navDisabled = readNavDisabledFromKernel() ?: false
            reconcileNav()
        }

        val fx = AudioFx(this, p)
        audioFx = fx
        fx.refresh()
    }

    /** Reads the current 0dbutton value for the synaptics_dsx_2 device, if found. */
    private fun readNavDisabledFromKernel(): Boolean? {
        val script =
            "for d in /sys/class/input/event*; do " +
                "if [ \"\$(cat \"\$d/device/name\" 2>/dev/null)\" = synaptics_dsx_2 ]; then " +
                "cat \"\$d/device/0dbutton\"; " +
                "fi; " +
                "done"
        return try {
            val result = RootShell.run(script)
            when (result.outString.trim()) {
                "0" -> true  // 0 = buttons disabled
                "1" -> false // 1 = buttons enabled
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun navLockEnabled() = prefs?.getBoolean(KEY_NAV_LOCK, true) ?: true
    private fun gestureMode() = prefs?.getBoolean(KEY_NAV_GESTURE, false) ?: false
    private fun alwaysOff() = prefs?.getBoolean(KEY_NAV_ALWAYS_OFF, false) ?: false
    private fun pinInputEnabled() = prefs?.getBoolean(KEY_PIN_INPUT, true) ?: true

    // ---------------------------------------------------------------- Nav Lock

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        imeActive = isImeVisible()
        reconcileNav()
    }

    /** Compute and apply the desired capacitive-button state from current settings. */
    private fun reconcileNav() {
        val desired = when {
            alwaysOff() -> true                          // permanently disabled
            !navLockEnabled() || gestureMode() -> false   // buttons stay live (gesture mode gates in onKeyEvent)
            else -> imeActive                             // disable-while-typing mode
        }
        if (desired != navDisabled) applyNavDisabled(desired)
    }

    private fun isImeVisible(): Boolean {
        val windowList: List<AccessibilityWindowInfo> = try {
            windows ?: return false
        } catch (_: Exception) {
            return false
        }
        return windowList.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }

    private fun applyNavDisabled(disabled: Boolean) {
        navDisabled = disabled
        worker.execute { runRoot(if (disabled) "0" else "1") }
    }

    private fun writeNodeBlocking(enabled: Boolean) {
        navDisabled = !enabled
        runRoot(if (enabled) "1" else "0")
    }

    private fun runRoot(value: String) {
        val script =
            "for d in /sys/class/input/event*; do " +
                "if [ \"\$(cat \"\$d/device/name\" 2>/dev/null)\" = synaptics_dsx_2 ]; then " +
                "echo $value > \"\$d/device/0dbutton\"; " +
                "fi; " +
                "done"
        try {
            RootShell.run(script)
        } catch (_: Exception) {
            // Root denied or su missing: leave buttons as-is.
        }
    }

    // --------------------------------------------------------------- PIN Input

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        val kc = event.keyCode

        // Nav gesture-gate (Back only): while typing, swallow a quick tap on Back
        // and fire it only on a double-tap. Home/Recents can't be gated - Android's
        // window policy acts on them regardless of accessibility consumption.
        if (navLockEnabled() && gestureMode() && imeActive &&
            kc == KeyEvent.KEYCODE_BACK && !isDeviceLocked()
        ) {
            return handleNavGesture(event, kc)
        }

        // PIN Input: map physical keys to the lockscreen PIN pad.
        if (!pinInputEnabled()) return false
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (!isDeviceLocked()) return false

        if (kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER) {
            return clickPinEnter()
        }
        if (kc == KeyEvent.KEYCODE_DEL || kc == KeyEvent.KEYCODE_FORWARD_DEL) {
            return clickPinDelete()
        }
        val digit = keyCodeToDigit(kc)
        if (digit != null) return clickPinButton(digit)
        return false
    }

    /** Consume the nav key; perform its action only on a double-tap. */
    private fun handleNavGesture(event: KeyEvent, kc: Int): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            val duration = event.eventTime - event.downTime
            val now = event.eventTime
            if (duration < LONG_PRESS_MS) { // ignore holds; count quick taps
                val last = lastNavTap[kc]
                if (last != null && (now - last) <= DOUBLE_TAP_MS) {
                    performNav(kc)
                    lastNavTap.remove(kc)
                } else {
                    lastNavTap[kc] = now // first tap; wait for the second
                }
            }
        }
        return true // always swallow the raw key so a single tap does nothing
    }

    private fun performNav(kc: Int) {
        val action = when (kc) {
            KeyEvent.KEYCODE_BACK -> GLOBAL_ACTION_BACK
            KeyEvent.KEYCODE_HOME -> GLOBAL_ACTION_HOME
            KeyEvent.KEYCODE_APP_SWITCH -> GLOBAL_ACTION_RECENTS
            else -> return
        }
        performGlobalAction(action)
    }

    private fun isDeviceLocked(): Boolean {
        val km = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isKeyguardLocked ?: false
    }

    private fun keyCodeToDigit(kc: Int): String? {
        if (kc >= KeyEvent.KEYCODE_0 && kc <= KeyEvent.KEYCODE_9) {
            return (kc - KeyEvent.KEYCODE_0).toString()
        }
        if (kc >= KeyEvent.KEYCODE_NUMPAD_0 && kc <= KeyEvent.KEYCODE_NUMPAD_9) {
            return (kc - KeyEvent.KEYCODE_NUMPAD_0).toString()
        }
        // BlackBerry physical keyboard: phone-dialpad layout mapped onto QWERTY.
        // W(1) E(2) R(3) / S(4) D(5) F(6) / Z(7) X(8) C(9) / Q(0)
        return when (kc) {
            KeyEvent.KEYCODE_Q -> "0"
            KeyEvent.KEYCODE_W -> "1"
            KeyEvent.KEYCODE_E -> "2"
            KeyEvent.KEYCODE_R -> "3"
            KeyEvent.KEYCODE_S -> "4"
            KeyEvent.KEYCODE_D -> "5"
            KeyEvent.KEYCODE_F -> "6"
            KeyEvent.KEYCODE_Z -> "7"
            KeyEvent.KEYCODE_X -> "8"
            KeyEvent.KEYCODE_C -> "9"
            else -> null
        }
    }

    private fun clickPinButton(digit: String): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val ids = arrayOf(
                "com.android.systemui:id/key$digit",
                "com.android.systemui:id/pin_key_$digit",
                "com.android.systemui:id/digit_$digit"
            )
            for (id in ids) if (clickById(root, id)) return true
            return findAndClick(root, digit)
        } finally {
            root.recycle()
        }
    }

    private fun clickPinDelete(): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val ids = arrayOf(
                "com.android.systemui:id/delete_button",
                "com.android.systemui:id/key_backspace",
                "com.android.systemui:id/pin_key_delete"
            )
            for (id in ids) if (clickById(root, id)) return true
            return findAndClickByDesc(root, arrayOf("delete", "backspace"))
        } finally {
            root.recycle()
        }
    }

    private fun clickPinEnter(): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val ids = arrayOf(
                "com.android.systemui:id/key_enter",
                "com.android.systemui:id/pin_key_enter",
                "com.android.systemui:id/check_button"
            )
            for (id in ids) if (clickById(root, id)) return true
            return findAndClickByDesc(root, arrayOf("enter", "confirm", "ok"))
        } finally {
            root.recycle()
        }
    }

    private fun clickById(root: AccessibilityNodeInfo, viewId: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        if (nodes.isNullOrEmpty()) return false
        for (node in nodes) {
            try {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            } finally {
                node.recycle()
            }
        }
        return false
    }

    private fun findAndClick(node: AccessibilityNodeInfo, digit: String): Boolean {
        if (node.isClickable) {
            val txt = node.text
            if (txt != null && txt.toString().trim() == digit) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findAndClick(child, digit)
            child.recycle()
            if (found) return true
        }
        return false
    }

    private fun findAndClickByDesc(node: AccessibilityNodeInfo, keywords: Array<String>): Boolean {
        if (node.isClickable) {
            val desc = node.contentDescription
            if (desc != null) {
                val s = desc.toString().lowercase(Locale.ROOT)
                for (kw in keywords) {
                    if (s.contains(kw)) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    }
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findAndClickByDesc(child, keywords)
            child.recycle()
            if (found) return true
        }
        return false
    }

    // ------------------------------------------------------------- Lifecycle

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        writeNodeBlocking(true) // never leave nav buttons dead
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        writeNodeBlocking(true)
        audioFx?.shutdown()
        prefs?.unregisterOnSharedPreferenceChangeListener(prefListener)
        worker.shutdown()
        super.onDestroy()
    }
}
