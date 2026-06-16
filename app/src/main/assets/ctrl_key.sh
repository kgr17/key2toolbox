#!/system/bin/sh
setenforce 0
nsenter -t 1 -m -- mount -o rw,remount /vendor
nsenter -t 1 -m -- sed -i s/FUNCTION/CTRL_LEFT/ /vendor/usr/keylayout/stmpe.kl
setenforce 1
