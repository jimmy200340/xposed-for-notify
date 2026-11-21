# NotifyBalance2Fix

Dual-API LSPosed / Xposed companion patch module (**libxposed API 101/102** + **legacy Xposed API 82**) for **Notify for Amazfit** (`com.mc.amazfit1`, verified on **v24.1.1** with backward compatibility for v24.0.3) targeting **Amazfit Balance 2** (`MILI_LYON` / `0x920100`, Zepp OS 6) on **Xiaomi 17 Ultra (`25128PNA1G`, Android 16 / HyperOS Global)**.

## Dual Xposed API Architecture
- **Modern libxposed API (`minApiVersion=101`, `targetApiVersion=102`)**: Entry point [`ModernXposedEntry`](src/com/amazfit/balance2fix/ModernXposedEntry.java) registered via `META-INF/xposed/{module.prop,java_init.list,scope.list}`. Strictly isolated with zero references to `de.robv.android.xposed.*` to comply with API 102 runtime enforcement.
- **Legacy Xposed API (`82+`)**: Entry point [`LegacyXposedEntry`](src/com/amazfit/balance2fix/LegacyXposedEntry.java) registered via `assets/xposed_init` and manifest `<meta-data>`.
- **Shared Core**: [`HookBridge`](src/com/amazfit/balance2fix/HookBridge.java) + [`NotifyWeatherHook`](src/com/amazfit/balance2fix/NotifyWeatherHook.java) share 100% of hook logic across both APIs.

## Implemented Modules (4 Toggles in App UI)

1. **Weather Sync Fix** — Fixes Weather v6 sync and prevents stale GNSS cache overwrites.
2. **Zepp OS 6 AGPS Fix & Fast Transfer** — See [`docs/AGPS_FIX.md`](docs/AGPS_FIX.md)
   - Reads real watch AGPS capability bitmask (`0x000F1C00`) and hardware update/expiration timestamps on Endpoint `0x0A` (`Lbn6.f` / `04 01 00 ...`, tags `0x08`/`0x09`/`0x0A`).
   - Downloads & packs EPO (`0x8C`/`0x8D`/`0x8E`) or LTO (`0x8B`) into valid UIHH containers (`uih.bin`).
   - Accelerates BLE transfer from several minutes down to ~2.9 seconds (`BLEManager.x1` `mode=2` + `WRITE_TYPE_NO_RESPONSE`, `CONNECTION_PRIORITY_HIGH` via `BLEManager.S0`, `Lvn7.l` timeout ceiling).
   - Injects a one-click **Update AGPS** row with last-update & expiration timestamps into *Device > Band Settings > Settings*.
3. **Xiaomi HyperOS Bluetooth Screen Unlock** — Integrates Bluetooth unlock configuration inside Band Settings.
4. **VoIP Call (LINE / WhatsApp / etc.) Watch Answering & Audio Routing** — See [`docs/VOIP_CALL_FIX.md`](docs/VOIP_CALL_FIX.md)
   - Prevents immediate call drop when tapping Green Answer on the watch (blocks false BLE `05 01` -> `Lyf0.w` rejection while preserving genuine Red Hangup).
   - Routes two-way HFP SCO voice audio directly to the watch (`BluetoothHeadset.setForceScoAudio(true)` + `AudioManager.setCommunicationDevice(watchScoDevice)`) while defending against HyperOS's 600ms watch deactivation timer (`isWearableWatchDeviceSetActive`).
   - Strictly preserves Bluetooth earbuds / phone priority when answering on the phone screen or connected earbuds.

## Building on Another Computer

### Prerequisites
- **JDK 17+** (`JAVA_HOME` set, or `javac` on `PATH`)
- **Android SDK** (`ANDROID_HOME` or `ANDROID_SDK_ROOT` set, with `platforms/android-34` and `build-tools/34.0.0` or newer)
- No Gradle installation required — `xposed_stubs/` is bundled in this repository and compiled as `compileOnly`.

### Build Command (Windows PowerShell)
```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```
Output APK: `NotifyBalance2Fix.apk` (signed with local `debug.keystore`; set `$env:NOTIFY_MOD_KEYSTORE` to reuse your existing signing key so you can `adb install -r` without uninstalling).

### Deploy via ADB
```powershell
adb install -r .\NotifyBalance2Fix.apk
adb shell am force-stop com.mc.amazfit1
adb shell monkey -p com.mc.amazfit1 -c android.intent.category.LAUNCHER 1
```

## Pending Verification / Next Steps
- [x] Real incoming LINE call answered from Amazfit Balance 2 (two-way audio + clean teardown verified via `adb logcat`).
- [ ] Live test with Bluetooth earbuds (e.g., Sony WF-1000XM5) simultaneously connected:
  - Answer on phone screen -> verify audio stays on earbuds (not hijacked to watch).
  - Answer on watch -> verify audio routes to watch.
  - Hang up / reject on watch -> verify clean decline.
