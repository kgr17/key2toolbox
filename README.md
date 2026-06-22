# Key2 Toolbox

A root app for the BlackBerry Key2 (FolkPatch/APatch, LineageOS 22.2, 4.19
kernel) that bundles a set of previously-separate tweaks into one UI,
organised into three bottom-bar sections:

**Info** - device status landing page: build (model, Android, LineageOS,
security patch, kernel), battery (level, health, temperature, voltage,
technology, capacity-health % and charge cycles from sysfs), and root +
accessibility-service status.

**Keyboard**
- **Convenience key → Ctrl** remap (`stmpe.kl` key 110)
- **Adaptive keyboard backlight** daemon
- **Keyboard Nav Lock** - stops accidental Back/Home/Recents while typing
- **Lockscreen PIN on Keyboard** - type your PIN on the physical keyboard
- **Per-App Keyboard Block** - in chosen apps, route physical keys straight
  to the app (for games) by switching to a passthrough IME

**System**
- **ZRAM** compression algorithm + size (Off / 2GB / 3GB / 4GB)
- **Persistent wireless ADB** on a user-chosen static port
- **Double-Tap to Wake** (DT2W)
- **5GHz Hotspot Workaround** - force the WiFi region to US so 5GHz SoftAP
  works (EU regdomains expose no 5GHz AP channels on this build)

The UI follows Material You (Monet), in light or dark to match the system.
Most modules are stateless: they fire root commands on demand and persist by
installing a script to `/data/adb/service.d/`. A few (Nav Lock, PIN, Per-App
Keyboard Block - the last two ported from
[nozerorma/key2-tweaks](https://github.com/nozerorma/key2-tweaks)) instead
depend on a long-lived `Key2AccessibilityService` that watches IME/window
state and intercepts physical key events, since none of that is observable
from a one-shot root command. Their settings live in a `key2tweaks`
SharedPreferences file rather than going through `AssetInstaller`.

The accessibility-service modules only work once **Key2 Toolbox** is enabled
under Settings → Accessibility - each of their screens shows a banner with a
direct link there if it isn't. Reinstalling the app resets this, so it needs
re-enabling after every fresh install.

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

### Keyboard Nav Lock (`Key2AccessibilityService`)
Ported from nozerorma/key2-tweaks. Stops accidental Back / Home / Recents
presses while the on-screen keyboard (IME) is visible. Three independent
toggles, all stored as booleans in the `key2tweaks` SharedPreferences file:
- **Keyboard Nav Lock** (`nav_lock_enabled`): master switch for the
  disable-while-typing behavior.
- **Double-tap Back** (`nav_gesture_mode`, no root): keeps the buttons live;
  the service gates `KEYCODE_BACK` in `onKeyEvent` so a single tap is
  swallowed and only a double-tap (within 300ms, and only if the tap was
  shorter than a 350ms long-press) fires it. Home/Recents can't be gated
  this way - Android's window policy acts on them regardless of what an
  accessibility service consumes.
- **Disable nav buttons ALWAYS** (`nav_always_off`, root): permanently cuts
  all three capacitive buttons via the sysfs node
  `/sys/class/input/eventN/device/0dbutton` (`1`=on, `0`=off), resolved by
  device name (`synaptics_dsx_2`) each time rather than a fixed event
  number, so it survives reboots even if the event index shifts. The root
  write goes through `RootShell` (a `for` loop over `/sys/class/input/event*`
  in one shell invocation, matching the FolkPatch one-command-per-`-c` quirk
  already documented for the other modules).

The service recomputes the desired button state (`reconcileNav()`) whenever
an accessibility event fires or any of the three prefs change, and always
re-enables the buttons in `onUnbind`/`onDestroy` so a crash or disable never
leaves them stuck off.

### Lockscreen PIN on Keyboard (`Key2AccessibilityService`)
Ported from nozerorma/key2-tweaks. No root needed. While the keyguard is
locked, maps physical key presses to taps on SystemUI's PIN pad via
`AccessibilityNodeInfo`, so the PIN can be entered on the hardware keyboard
instead of the touchscreen. Digits map phone-dialpad style onto QWERTY:
`W E R` = `1 2 3`, `S D F` = `4 5 6`, `Z X C` = `7 8 9`, `Q` = `0` (number
row and numpad keys also work directly). Enter/D-pad-center confirms,
Delete/Backspace deletes. Button lookup tries known SystemUI view IDs first
(`key0`-`key9`, `delete_button`, `key_enter`, etc.) and falls back to a
recursive node search by visible text or content description if those IDs
don't match on this build.

### Per-App Keyboard Block (`Key2AccessibilityService` + `Key2PassthroughIme`)
In a chosen set of apps, physical key presses are routed straight to the app
instead of going through the keyboard. On the Key2 the BlackBerry IME
intercepts and translates hardware keys (it even ignores the system keymap -
`stmpe.kcm` maps the currency key to `$`, but the IME still emits `4`), which
interferes with games. The service tracks the foreground app
(`foregroundAppPackage()` from the active application window) and, when a
selected package is in front, switches the default IME to a bundled
do-nothing input method (`Key2PassthroughIme`, which inflates no view and
consumes no keys) via root `ime enable`/`ime set`, saving the previous IME to
restore on the way out. The picked packages are a `StringSet` in the
`key2tweaks` prefs; the app list uses a `<queries>` launcher intent so it can
enumerate launchable apps on Android 11+.

### 5GHz Hotspot Workaround (`WifiRegdomainController`)
On this build every EU WiFi regdomain exposes **zero** 5GHz SoftAP channels
(`SupportedChannelListIn5g[]`), so 5GHz hotspot is greyed out / fails with
`NO_CHANNEL`; only the US regdomain has them. This forces the WiFi country
code to US via `cmd wifi force-country-code enabled US`, applied live and
persisted with `assets/force_us_wifi.sh` (which re-applies it after each boot
once the WiFi service is up, since the override resets on reboot). Trade-off,
surfaced on the screen: it also applies to WiFi as a client (you lose 2.4GHz
ch 12-13 and EU-only 5GHz channels) and enables the upper US channels
(149-165) that aren't EU-licensed. Also note SoftAP only starts with **WPA2**
on this build - WPA3/SAE fails with `UNSUPPORTED_CONFIGURATION`.

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

- For stateless root-command modules, add a `core`-style controller in
  `modules/`, following the pattern of `CtrlKeyController` /
  `ZramController` / `KbdLightController` / `WirelessAdbController` /
  `Dt2wController` (persist via `AssetInstaller`, live-apply via
  `RootShell.run`).
- For features that need to observe ongoing state (window/IME visibility,
  key events) rather than just fire a command, that observation has to
  happen inside `Key2AccessibilityService` - root has no API for "tell me
  when X happens," only for executing commands. Add the logic there, store
  settings as SharedPreferences booleans/ints in the `key2tweaks` prefs file,
  and write the corresponding screen to read/write those same keys directly
  rather than going through `AssetInstaller`.
- Either way, add a corresponding screen in `ui/` (following e.g.
  `CtrlKeyScreen.kt` for the simple case or `NavLockScreen.kt` /
  `ImeBlockScreen.kt` for the prefs-based case, all built on the shared
  `ScreenScaffold`), and wire it into `DetailHost` plus the section lists in
  `ui/HomeScreen.kt` and `ui/Screen.kt`.
- Drop any new boot scripts in `app/src/main/assets/`.
