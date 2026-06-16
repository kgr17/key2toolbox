#!/system/bin/sh
settings put global adb_wifi_enabled 1
setprop persist.adb.tcp.port __PORT__
setprop service.adb.tcp.port __PORT__
