#!/system/bin/sh
# DT2W enable - kgr
# Must run while screen is on for gesture mode to take effect on suspend.
sleep 5
echo 1 > /sys/devices/platform/soc/c178000.i2c/i2c-4/4-0070/input/input3/wake_gesture
