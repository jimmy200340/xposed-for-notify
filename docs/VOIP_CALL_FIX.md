# Technical Report & Fix: VoIP Call (LINE / WhatsApp / etc.) Answering & HFP Audio Routing on Amazfit Balance 2 (Zepp OS 6)

**Target App:** Notify for Amazfit v24.0.3 (`com.mc.amazfit1`)  
**Tested Device:** Amazfit Balance 2 (`MILI_LYON` / `0x920100`, Zepp OS 6, Bluetooth Class `0x000704` = `WEARABLE_WRIST_WATCH`)  
**Tested Phone / OS:** Xiaomi 17 Ultra (`25128PNA1G`), Android 16 (HyperOS Global)  
**Tested VoIP App:** LINE (`jp.naver.line.android`, Jetpack CoreTelecom `SelfManaged` `PhoneAccount`)

## Summary of Issues
During an incoming VoIP call (such as LINE):
1. **Case 1 — Immediate Call Drop:** Tapping Green Answer on the watch sends HFP `ATA` (answering LINE via `BluetoothInCallService.answerCall()`), while simultaneously dismissing the BLE Endpoint `0x1E` alert via `05 01` -> `Lyf0.w()`, which calls `TelecomManager.endCall()` + Decline `PendingIntent` and immediately drops the call.
2. **Case 2 — Audio Stays on Phone:** Because the watch Bluetooth Class is `0x000704` (`WEARABLE_WRIST_WATCH`), `HeadsetService.mActiveDevice` defaults to `null`/earbuds. During `ATA`, `HeadsetSystemInterface.answerCall(watchDevice)` calls `setActiveDevice(watchDevice)` *before* `BluetoothInCallService.answerCall()` (while `mNumActive == 0` and in-band ringing is disabled), skipping `connectAudio()`. Furthermore, `HeadsetStateMachine$ConnectedBase` (`what = 0x20`, +600ms) calls `isWearableWatchDeviceSetActive(watchDevice)`, kicking the watch back off `mActiveDevice` if LINE has not yet entered `STATE_ACTIVE`.

## Verified Fix Architecture
1. **Clean Endpoint `0x1E` Alert & `PendingIntent` Extraction:**
   - Suppress `com.mc.miband1.model.d.t6()` (`isVoipCall`) in `Le64.run` / `Lqy.run` to avoid 20x generic notification spam.
   - Fix `Lba8.g` to extract Answer (`X`) / Decline (`W`) `PendingIntent`s from Android 12+ `CallStyle` extras, LINE `RemoteViews` (`fl_btn_left`/`fl_btn_right`) even when `android.title` is non-empty, and `Notification.actions`.
2. **Strict Watch-Answer Detection & Case 1 Prevention:**
   - Never treat BLE `05 01` / `05 02` alone as Watch Answer.
   - Mark `answeredFromWatch = true` strictly when `ACTIVE_DEVICE_CHANGED` or `AUDIO_STATE_CHANGED` points to the watch during a VoIP call window (emitted by HFP `ATA`), `BluetoothHeadset.getActiveDevice()` returns the watch, or legacy `Lyf0.a`/`b` triggers.
   - In `Lyf0.w`, block false rejection when already answered/in communication, and defer ringing rejections by 650ms to distinguish Watch Answer (`ATA` + BLE `05 01`) from genuine Watch Hangup (`AT+CHUP` + BLE `05 01`).
3. **Watch-Only HFP SCO Routing & Earbuds Priority Protection:**
   - If answered on the phone screen or via connected Bluetooth earbuds, `answeredFromWatch` stays `false` (and earbuds SCO sets `otherBtHeadsetClaimedAudio = true`), skipping all watch routing.
   - Only when `answeredFromWatch == true`: invoke `BluetoothHeadset.setForceScoAudio(true)` (requires only `BLUETOOTH_CONNECT`), disable speakerphone, defend against HyperOS's 600ms deactivation timer, and route audio via `AudioManager.setCommunicationDevice(watchScoDevice)` (with safe `startBluetoothSco()` fallback only when no other headset is active).
4. **Clean Teardown:**
   - On transition back to `AudioManager.MODE_NORMAL`, invoke `BluetoothHeadset.setForceScoAudio(false)`, `stopBluetoothSco()`, `setBluetoothScoOn(false)`, and `clearCommunicationDevice()`.

## Live Test Result
Verified on a real incoming LINE call on **Xiaomi 17 Ultra (`25128PNA1G`, Android 16 / HyperOS Global)** + **Amazfit Balance 2 (Zepp OS 6)**:
- Tapping Answer on the watch cleanly answered with zero call drop and sustained two-way voice audio (`HFP STATE_AUDIO_CONNECTED = 12`).
- Hangup automatically restored `setForceScoAudio(false)` and cleared communication device.
