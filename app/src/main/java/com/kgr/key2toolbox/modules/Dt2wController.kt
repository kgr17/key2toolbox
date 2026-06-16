package com.kgr.key2toolbox.modules

import android.content.Context
import com.kgr.key2toolbox.core.AssetInstaller
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.core.ShellResult

/**
 * DT2W (Double Tap to Wake), via wake_gesture sysfs node on the main
 * touchscreen (synaptics_dsx_2.7, I2C 4-0070).
 *
 * Must be applied while the screen is ON, so the driver configures gesture
 * detection as part of its normal suspend sequence. The value does not
 * persist across reboot, so persistence writes
 * /data/adb/service.d/dt2w.sh, which sleeps briefly then re-applies the
 * write (assumes screen is on shortly after boot).
 */
object Dt2wController {

    private const val SCRIPT_NAME = "dt2w.sh"
    private const val TARGET = "/data/adb/service.d/$SCRIPT_NAME"
    private const val WAKE_GESTURE =
        "/sys/devices/platform/soc/c178000.i2c/i2c-4/4-0070/input/input3/wake_gesture"

    enum class State { ON, OFF, UNKNOWN }

    /** Reads the live wake_gesture sysfs value. */
    fun currentState(): State {
        val out = RootShell.run("cat $WAKE_GESTURE 2>/dev/null").outString
        return when (out.trim()) {
            "1" -> State.ON
            "0" -> State.OFF
            else -> State.UNKNOWN
        }
    }

    fun isPersisted(): Boolean = AssetInstaller.fileExists(TARGET)

    /** Enables DT2W immediately. Must be called with the screen ON. */
    fun applyLiveOn(): ShellResult = RootShell.run(
        "echo 1 > $WAKE_GESTURE"
    )

    /** Disables DT2W immediately. */
    fun applyLiveOff(): ShellResult = RootShell.run(
        "echo 0 > $WAKE_GESTURE"
    )

    /** Installs the boot-time script so DT2W survives reboots. */
    fun enablePersist(context: Context): ShellResult =
        AssetInstaller.installFromAsset(context, SCRIPT_NAME, TARGET)

    fun disablePersist(): ShellResult =
        AssetInstaller.removeFile(TARGET)
}
