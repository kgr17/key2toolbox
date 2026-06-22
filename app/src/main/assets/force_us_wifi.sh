#!/system/bin/sh
# Force WiFi regulatory domain to US - Key2 Toolbox
#
# This build exposes no 5GHz SoftAP channels for any EU regdomain; only US has
# them. The country override resets on reboot, so re-apply it once the WiFi
# service is up. Retry for ~2 minutes since the service isn't ready immediately
# at boot.
i=0
while [ "$i" -lt 60 ]; do
    if cmd wifi status >/dev/null 2>&1; then
        cmd wifi force-country-code enabled US && break
    fi
    sleep 2
    i=$((i + 1))
done
