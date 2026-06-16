# Key2 Toolbox

A root app for the BlackBerry Key2 (FolkPatch/APatch, LineageOS 22.2, 4.19
kernel) that bundles five previously-separate tweaks into one toggle UI:

- **Convenience key → Ctrl** remap (`stmpe.kl` key 110)
- **ZRAM** compression algorithm + size (Off / 2GB / 3GB / 4GB)
- **Adaptive keyboard backlight** daemon
- **Persistent wireless ADB** on a user-chosen static port
- **Double-Tap to Wake** (DT2W)

Each module lives on its own screen, navigated from a home menu. Persistence
is handled per-module by installing a script to `/data/adb/service.d/`; most
modules also support applying changes immediately without a reboot.

## Signing with your existing keystore

`app/build.gradle.kts` has a commented-out `signingConfigs { create("release") {...} }`
block referencing `kgr_signing.keystore` / alias `kgr`. Uncomment it, point
`storeFile` at your keystore path, and wire `signingConfig = signingConfigs.getByName("release")`
into the `release` build type. Consider passing the password via
`gradle.properties` (gitignored) or an env var rather than committing it in
the build script.

## How each module works

### Ctrl key (`CtrlKeyController`)
- **Persist**: installs `assets/ctrl_key.sh` (the exact known-working script)
  to `/data/adb/service.d/ctrl_key.sh` via `install -m 755`.
- **Live apply**: runs the same `setenforce 0` → `nsenter -t 1 -m -- mount -o rw,remount /vendor`
  → `sed -i s/FUNCTION/CTRL_LEFT/` → `setenforce 1` sequence directly (and the
  reverse to disable).
- Status display reads back `key 110` from `/vendor/usr/keylayout/stmpe.kl`
  live, separately from whether the boot script is installed.

### ZRAM (`ZramController`)
- **Compression algorithm**: read dynamically from
  `/sys/block/zram0/comp_algorithm`, so the screen only offers algorithms
  this kernel actually supports (confirmed: `lzo`, `lz4`, `zstd`, `deflate`).
- **Size**: Off / 2GB / 3GB / 4GB.
- **Persist**: installs `assets/zram_template.sh` (with `__ALGO__` and
  `__SIZE_MB__` substituted) to `/data/adb/service.d/zram_size.sh`. Selecting
  "Off" removes the script.
- **Live apply** (behind a confirmation dialog): `swapoff` → reset → set
  `comp_algorithm` → set `disksize` → `mkswap` → `swapon`. This briefly
  disables swap and can cause background apps to be killed - the dialog
  warns about this, default is reboot-to-apply.

### Keyboard backlight (`KbdLightController`)
- This script is a persistent loop, not a one-shot config, so "enabled" =
  install `assets/kbd_light.sh` to `service.d` for next boot, **and**
  optionally launch it right now (`nohup sh ... &`) / kill it
  (`pkill -f kbd_light.sh`) so you don't need to reboot to test.

### Wireless ADB (`WirelessAdbController`)
- User enters a port; **persist** installs `assets/adb_wireless_template.sh`
  (with `__PORT__` substituted) to `/data/adb/service.d/adb_wireless.sh`,
  which sets `adb_wifi_enabled` and pins `persist.adb.tcp.port` /
  `service.adb.tcp.port` at boot.
- **Live apply** sets the same properties immediately.
- The screen also shows the device's current WLAN IP (via `ip route get`,
  checked against a `wlan*`-named interface so it doesn't report a cellular
  IP when WiFi is down) and the live port, so you can confirm the
  `adb connect <ip>:<port>` target at a glance.
- If you previously had a separate static-port script, remove it once this
  module's persistence is confirmed working, so two boot scripts aren't
  racing to set the same property.

### Double-Tap to Wake (`Dt2wController`)
- Toggles the `wake_gesture` sysfs node on the main touchscreen
  (`synaptics_dsx_2.7`, I2C 4-0070).
- Must be applied while the screen is **on** - the driver only picks up the
  gesture-mode setting as part of its normal suspend sequence, so enabling
  it with the screen already off won't take effect until the next time the
  screen turns on and back off.
- **Persist**: installs `assets/dt2w.sh` to `/data/adb/service.d/`, which
  sleeps briefly after boot (screen is assumed on) then re-applies the write,
  since the value doesn't survive reboot on its own.
- If the live state doesn't actually change after toggling, this may be the
  unresolved driver/HAL-level issue from earlier debugging (sysfs write
  appears to succeed but the gesture doesn't engage) rather than a bug in
  the app itself.

## ⚠ Known risk: writing to `/data/adb/service.d/` from the app

In a previous session, **every attempt to write to `/data/adb/service.d/`
from a root shell post-boot failed with "Permission denied"** - including
`su -c` over ADB using `>`, `dd`, etc. The only thing that worked was an
`install -m 755` from an **interactive Termux `su` session**. The likely
cause is a filesystem-encryption-context mismatch between the shell session
that originally created files there (the initial `adb shell` session at
setup time) and any shell spawned afterwards.

This app's root shell (via `libsu`) is yet another shell context, spawned at
app-runtime, so it **may hit the same wall**. Each module's screen shows a
"Persisted: Yes/No" status that's read back from disk after every write, so
you'll see immediately if a persist operation silently failed.

**If persistence writes fail from the app:**
1. The app's live-apply / enable-now actions still work (they don't touch
   `service.d`), so the toggles remain useful for testing.
2. For persistence, fall back to the Termux method: copy the relevant script
   from `app/src/main/assets/` (or pull it from the app's
   `filesDir` - the app writes a staging copy there before attempting the
   `install`), then from Termux:
   ```
   su
   install -m 755 /path/to/script.sh /data/adb/service.d/script.sh
   ```
3. If you find a write path that *does* work from an app-spawned root shell,
   it's worth updating `AssetInstaller.installFromAsset` here so the app can
   self-persist reliably - that's the main open question for this build.

## Extending

- Add new modules as `core`-style controllers in `modules/`, following the
  pattern of `CtrlKeyController` / `ZramController` / `KbdLightController` /
  `WirelessAdbController` / `Dt2wController` (persist via `AssetInstaller`,
  live-apply via `RootShell.run`).
- Add a corresponding screen in `ui/` (following e.g. `CtrlKeyScreen.kt`,
  built on the shared `ScreenScaffold`), and wire it into the navigation
  host and home menu in `ui/HomeScreen.kt` and `ui/Screen.kt`.
- Drop any new boot scripts in `app/src/main/assets/`.
