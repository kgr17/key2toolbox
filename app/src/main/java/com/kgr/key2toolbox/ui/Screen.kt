package com.kgr.key2toolbox.ui

sealed class Screen(val title: String) {
    data object Home : Screen("Key2 Toolbox")
    data object CtrlKey : Screen("Convenience Key \u2192 Ctrl")
    data object Zram : Screen("ZRAM")
    data object KbdLight : Screen("Adaptive Keyboard Backlight")
    data object WirelessAdb : Screen("Persistent Wireless ADB")
    data object Dt2w : Screen("Double-Tap to Wake")
}
