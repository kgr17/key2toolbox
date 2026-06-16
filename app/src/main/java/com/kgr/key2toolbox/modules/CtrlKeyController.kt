package com.kgr.key2toolbox.modules

import android.content.Context
import com.kgr.key2toolbox.core.AssetInstaller
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.core.ShellResult

/**
 * Convenience key -> Ctrl remap, via stmpe.kl key 110.
 *
 * Persistence writes /data/adb/service.d/ctrl_key.sh (the exact working
 * script: setenforce 0 -> nsenter remount /vendor rw -> sed FUNCTION->CTRL_LEFT
 * -> setenforce 1). Live apply runs the same sequence directly.
 */
object CtrlKeyController {

    private const val SCRIPT_NAME = "ctrl_key.sh"
    private const val TARGET = "/data/adb/service.d/$SCRIPT_NAME"
    private const val KEYLAYOUT = "/vendor/usr/keylayout/stmpe.kl"

    enum class State { CTRL, FUNCTION, UNKNOWN }

    /** Reads the live keymap to see whether key 110 is currently CTRL_LEFT or FUNCTION. */
    fun currentKeymapState(): State {
        val out = RootShell.run("grep '^key 110' $KEYLAYOUT 2>/dev/null").outString
        return when {
            out.contains("CTRL_LEFT") -> State.CTRL
            out.contains("FUNCTION") -> State.FUNCTION
            else -> State.UNKNOWN
        }
    }

    fun isPersisted(): Boolean = AssetInstaller.fileExists(TARGET)

    /** Remaps key 110 to CTRL_LEFT immediately (this boot only, until reverted or rebooted). */
    fun applyLiveOn(): ShellResult = RootShell.run(
        "setenforce 0 && " +
            "nsenter -t 1 -m -- mount -o rw,remount /vendor && " +
            "nsenter -t 1 -m -- sed -i s/FUNCTION/CTRL_LEFT/ $KEYLAYOUT ; " +
            "setenforce 1"
    )

    /** Reverts key 110 back to FUNCTION immediately (this boot only). */
    fun applyLiveOff(): ShellResult = RootShell.run(
        "setenforce 0 && " +
            "nsenter -t 1 -m -- mount -o rw,remount /vendor && " +
            "nsenter -t 1 -m -- sed -i s/CTRL_LEFT/FUNCTION/ $KEYLAYOUT ; " +
            "setenforce 1"
    )

    /** Installs the boot-time script so the remap survives reboots. */
    fun enablePersist(context: Context): ShellResult =
        AssetInstaller.installFromAsset(context, SCRIPT_NAME, TARGET)

    fun disablePersist(): ShellResult =
        AssetInstaller.removeFile(TARGET)
}
