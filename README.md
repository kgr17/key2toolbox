# Key2 Toolbox

A small root app for the BlackBerry Key2 (FolkPatch/APatch, LineageOS 22.2,
4.19 kernel) that bundles three previously-separate tweaks into one toggle UI:

- **Convenience key → Ctrl** remap (`stmpe.kl` key 110)
- **ZRAM size** (Off / 2GB / 3GB / 4GB)
- **Adaptive keyboard backlight** daemon

## Opening in Android Studio

1. Open this folder (`Key2Toolbox/`) as an existing project.
2. Let Gradle sync. It uses AGP 8.5.2 / Kotlin 1.9.24 / Compose BOM
   2024.09.00 / libsu 5.2.2 - bump via Studio's upgrade assistant if you're on
   a newer Studio that complains.
3. Build & run with `minSdk 28` / `targetSdk 35` (matches LOS 22.2 / SDK 35).

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
- **Persist**: installs `assets/zram_template.sh` (with `__SIZE_MB__`
  substituted) to `/data/adb/service.d/zram_size.sh`. Selecting "Off" removes
  the script.
- **Live apply** (behind a confirmation dialog): `swapoff` → reset → set
  `disksize` → `mkswap` → `swapon`. This briefly disables swap and can cause
  background apps to be killed - the dialog warns about this, default is
  reboot-to-apply.

### Keyboard backlight (`KbdLightController`)
- This script is a persistent loop, not a one-shot config, so "enabled" =
  install `assets/kbd_light.sh` to `service.d` for next boot, **and**
  optionally launch it right now (`nohup sh ... &`) / kill it
  (`pkill -f kbd_light.sh`) so you don't need to reboot to test.

## ⚠ Known risk: writing to `/data/adb/service.d/` from the app

In a previous session, **every attempt to write to `/data/adb/service.d/`
from a root shell post-boot failed with "Permission denied"** - including
`su -c` over ADB using `>`, `dd`, etc. The only thing that worked was an
`install -m 755` from an **interactive Termux `su` session**. The likely
cause is a filesystem-encryption-context mismatch between the shell session
that originally created files there (the initial `adb shell` session at
setup time) and any shell spawned afterwards.

This app's root shell (via `libsu`) is yet another shell context, spawned at
app-runtime, so it **may hit the same wall**. Each module's UI shows a
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
  pattern of `CtrlKeyController` / `ZramController` / `KbdLightController`
  (persist via `AssetInstaller`, live-apply via `RootShell.run`).
- Add a corresponding card composable in `ui/HomeScreen.kt`.
- Drop any new boot scripts in `app/src/main/assets/`.
