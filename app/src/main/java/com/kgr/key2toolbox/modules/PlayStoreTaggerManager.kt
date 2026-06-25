package com.kgr.key2toolbox.modules

import com.topjohnwu.superuser.Shell

/**
 * Root operations for Play Store tagging.
 * Uses Shell.cmd() directly (rather than RootShell.run()) because the logging
 * callback pattern needs the raw libsu API. The Shell instance is shared with
 * RootShell since Shell.setDefaultBuilder() is called once at app startup.
 */
object PlayStoreTaggerManager {

    private const val PLAY_STORE = "com.android.vending"

    fun setPlayInstaller(packageName: String, log: (String) -> Unit = {}): String? {
        val pathResult = Shell.cmd("pm path $packageName").exec()
        if (!pathResult.isSuccess || pathResult.out.isEmpty()) {
            return "Could not find APK path for $packageName"
        }

        val apkPaths = pathResult.out
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }

        if (apkPaths.isEmpty()) return "No APK paths found"

        log("Found ${apkPaths.size} APK(s)")
        apkPaths.forEach { log("  $it") }

        return if (apkPaths.size == 1) {
            installSingle(apkPaths[0], log)
        } else {
            installSplit(packageName, apkPaths, log)
        }
    }

    private fun installSingle(apkPath: String, log: (String) -> Unit): String? {
        log("Installing single APK with -i $PLAY_STORE")
        val result = Shell.cmd(
            "pm install -i $PLAY_STORE --dont-kill -r \"$apkPath\""
        ).exec()
        result.out.forEach { log(it) }
        return if (result.isSuccess || result.out.any { it.contains("Success", ignoreCase = true) }) null
        else result.out.joinToString("\n").ifBlank { "Install failed" }
    }

    private fun installSplit(packageName: String, apkPaths: List<String>, log: (String) -> Unit): String? {
        val sizeResult = Shell.cmd(
            "stat -c %s ${apkPaths.joinToString(" ")} | awk '{s+=\$1} END{print s}'"
        ).exec()
        val totalSize = sizeResult.out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
        log("Split APK total size: $totalSize bytes")

        val createResult = Shell.cmd(
            "pm install-create -i $PLAY_STORE --dont-kill -r -S $totalSize"
        ).exec()
        val sessionLine = createResult.out.firstOrNull { it.contains("Success") }
            ?: return "Failed to create session: ${createResult.out.joinToString("\n")}"

        val sessionId = Regex("\\[(\\d+)\\]").find(sessionLine)?.groupValues?.get(1)
            ?: return "Could not parse session ID"

        log("Session created: $sessionId")

        apkPaths.forEachIndexed { index, path ->
            val apkSize = Shell.cmd("stat -c %s \"$path\"").exec()
                .out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
            log("Writing split_$index (${apkSize} bytes)")
            val writeResult = Shell.cmd(
                "pm install-write -S $apkSize $sessionId split_$index \"$path\""
            ).exec()
            writeResult.out.forEach { log(it) }
            if (!writeResult.isSuccess && !writeResult.out.any { it.contains("Success", ignoreCase = true) }) {
                Shell.cmd("pm install-abandon $sessionId").exec()
                return "Failed to write split_$index"
            }
        }

        log("Committing session…")
        val commitResult = Shell.cmd("pm install-commit $sessionId").exec()
        commitResult.out.forEach { log(it) }
        return if (commitResult.isSuccess || commitResult.out.any { it.contains("Success", ignoreCase = true) }) null
        else {
            Shell.cmd("pm install-abandon $sessionId").exec()
            "Commit failed: ${commitResult.out.joinToString("\n")}"
        }
    }

    fun getInstaller(packageName: String): String? {
        val result = Shell.cmd("cmd package get-install-source $packageName 2>/dev/null").exec()
        if (!result.isSuccess) return null
        val line = result.out.firstOrNull { it.contains("installingPackageName") } ?: return null
        return line.substringAfter(":").trim().takeIf { it.isNotBlank() && it != "null" }
    }
}
