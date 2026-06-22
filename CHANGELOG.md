# Changelog

All notable changes to Key2 Toolbox are documented here.

## [4.1-beta4] - 2026-06-22

### Added
- **Bottom navigation** with three sections: **Info / Keyboard / System**.
- **Info** landing screen: device (model, Android, LineageOS, security patch,
  kernel, build), battery (level, status, health, temperature, voltage,
  technology, capacity-health % and charge cycles from sysfs), and live root +
  accessibility-service status.
- **Per-App Keyboard Block**: in selected apps, physical key presses reach the
  app directly instead of the BlackBerry IME, by switching to a bundled
  do-nothing passthrough IME (`Key2PassthroughIme`) while the app is foreground
  and restoring the previous IME on exit.
- **5GHz Hotspot Workaround**: forces the WiFi region to US (live + persisted
  boot script) so 5GHz SoftAP works, since EU regdomains expose no 5GHz AP
  channels on this build.
- **Material You (Monet)** theming that follows the system light/dark setting.

### Changed
- Per-app keyboard block now switches IME instead of toggling
  `show_ime_with_hard_keyboard` (which didn't actually stop the BlackBerry IME
  from intercepting keys).
- Fixed scrolling glitches by letting the `Scaffold` own the system-bar insets
  instead of each screen re-applying them.

### Removed
- **Audio FX** (system-wide EQ / BassBoost / LoudnessEnhancer) and its
  `MODIFY_AUDIO_SETTINGS` permission. The in-app, userspace `AudioEffect`
  approach was always a compromise (the EQ ate headroom and the makeup-gain
  compressor pumped). **NLSound** does the job better by operating at the audio
  HAL level, so the in-app audio mods are dropped in its favour.

### Notes
- WiFi hotspot on this build only starts with **WPA2** (WPA3/SAE fails with
  `UNSUPPORTED_CONFIGURATION`); the empty EU 5GHz AP regdomain and the SAE
  failure are ROM/driver-level and tracked upstream.
