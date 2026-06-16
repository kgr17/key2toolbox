package com.kgr.key2toolbox.modules

import android.content.Context
import com.kgr.key2toolbox.core.AssetInstaller
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.core.ShellResult

/**
 * Adaptive keyboard backlight daemon (kbd_light.sh).
 *
 * This script is a long-running loop, not a one-shot config - so "enabled"
 * means: install it under /data/adb/service.d/ for next boot, and optionally
 * launch/kill the loop right now so the user doesn't have to reboot to test it.
 */
object KbdLightController {

    private const val SCRIPT_NAME = "kbd_light.sh"
    private const val TARGET = "/data/adb/service.d/$SCRIPT_NAME"

    fun isPersisted(): Boolean = AssetInstaller.fileExists(TARGET)

    /** True if the daemon loop currently appears to be running. */
    fun isRunningLive(): Boolean {
        val out = RootShell.run("pgrep -f $SCRIPT_NAME").outString.trim()
        return out.isNotEmpty()
    }

    fun enable(context: Context, startLiveNow: Boolean): ShellResult {
        val result = AssetInstaller.installFromAsset(context, SCRIPT_NAME, TARGET)
        if (startLiveNow) {
            // Launch the installed script as a detached background process.
            RootShell.run("nohup sh $TARGET > /dev/null 2>&1 &")
        }
        return result
    }

    fun disable(stopLiveNow: Boolean): ShellResult {
        val result = AssetInstaller.removeFile(TARGET)
        if (stopLiveNow) {
            RootShell.run("pkill -f $SCRIPT_NAME")
            // The daemon owns these nodes while running; reset to off when disabling.
            RootShell.run(
                "echo 0 > /sys/class/leds/keyboard-backlight_1/brightness; " +
                    "echo 0 > /sys/class/leds/keyboard-backlight_2/brightness; " +
                    "echo 0 > /sys/class/leds/keyboard-backlight_3/brightness"
            )
        }
        return result
    }
}
