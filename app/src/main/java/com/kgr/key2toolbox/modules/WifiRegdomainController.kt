package com.kgr.key2toolbox.modules

import android.content.Context
import com.kgr.key2toolbox.core.AssetInstaller
import com.kgr.key2toolbox.core.RootShell

/**
 * Force the WiFi regulatory domain to US so 5GHz SoftAP (hotspot) works.
 *
 * On this build every EU regdomain exposes zero 5GHz AP channels, while US
 * exposes the full UNII-1/2/3 set, so the only way to get a 5GHz hotspot is to
 * override the country code. Applied live via `cmd wifi force-country-code`, and
 * persisted with a /data/adb/service.d boot script since the override resets on
 * reboot (and is otherwise driven by the SIM's country).
 */
object WifiRegdomainController {

    private const val SCRIPT_NAME = "force_us_wifi.sh"
    private const val TARGET = "/data/adb/service.d/$SCRIPT_NAME"

    /** Whether the boot script is installed (our persistent on/off state). */
    fun isPersisted(): Boolean = AssetInstaller.fileExists(TARGET)

    /** The WiFi country code currently applied by the framework, e.g. "US" / "ES". */
    fun currentCountry(): String =
        RootShell.run("cmd wifi get-country-code 2>/dev/null").outString
            .substringAfter('=', "").trim()

    fun enable(context: Context) {
        AssetInstaller.installFromAsset(context, SCRIPT_NAME, TARGET)
        RootShell.run("cmd wifi force-country-code enabled US")
    }

    fun disable() {
        AssetInstaller.removeFile(TARGET)
        RootShell.run("cmd wifi force-country-code disabled")
    }
}
