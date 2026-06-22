#!/system/bin/sh
sleep 5
swapoff /dev/block/zram0 2>/dev/null
echo 1 > /sys/block/zram0/reset
echo __ALGO__ > /sys/block/zram0/comp_algorithm
echo __SIZE_MB__m > /sys/block/zram0/disksize
mkswap /dev/block/zram0
swapon /dev/block/zram0
echo __SWAPPINESS__ > /proc/sys/vm/swappiness
