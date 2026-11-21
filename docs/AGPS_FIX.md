# Technical Report & Fix: AGPS Update Failure and Slow BLE Transfer on Amazfit Balance 2 (Zepp OS 6)

**Target App:** Notify for Amazfit v24.0.3 (`com.mc.amazfit1`)  
**Tested Device:** Amazfit Balance 2 (`MILI_LYON` / `0x920100`, Zepp OS 6)  
**Tested Phone / OS:** Xiaomi 17 Ultra (`25128PNA1G`), Android 16 (HyperOS Global)

## Summary of Issues
On Amazfit Balance 2 (Zepp OS 6), AGPS updates exhibit two separate issues:
1. **AGPS update fails or hangs indefinitely on "Loading..."** due to wrong AGPS format selection (CEP/LTO instead of EPO), UIHH container packaging issues, and missing failure/progress UI callbacks.
2. **BLE transfer takes several minutes (often timing out)** instead of ~2–5 seconds like the official Zepp app, due to `WRITE_TYPE_DEFAULT` + a 600ms per-packet sleep in `BLEManager.C1`, lack of `CONNECTION_PRIORITY_HIGH`, and premature chunk timeout handling.

## 1. Detect Watch AGPS Capability Bitmask via Endpoint `0x0A` (EPO vs. LTO)
Querying AGPS config via `Lxm6.k(bleMgr, Lnv0.q)` returns a TLV response on Endpoint `0x0A` (`Lxm6.f`) starting with `04 01 00`:

```text
04 01 00 01 01 03
08 50 00 1C 0F 00                   <-- Tag 0x08 (type 0x50, uint32 LE): capability bitmask = 0x000F1C00
09 40 18 71 55 F1 A1 01 00 00       <-- Tag 0x09 (type 0x40, uint64 LE): last AGPS update timestamp (epoch ms)
0A 40 00 BC 40 F6 A1 01 00 00       <-- Tag 0x0A (type 0x40, uint64 LE): AGPS expiration timestamp (epoch ms)
```

- Bit 9 (`1 << 9`, `0x0200`): **LTO (`0x8B`) = `false`**
- Bits 10–12 (`0x7 << 10`, `0x1C00`): **EPO (`0x8C`, `0x8D`, `0x8E`) = `true`**
- Bits 16–19 (`0xF << 16`, `0xF0000`): **ELPO (`0x95`–`0x98`) = `true`**

When `(capMask & (1L << 9)) == 0 && (capMask & (0x7L << 10)) != 0`, `UpdateFirmwareActivity.p0()` must return **`2` (EPO)** instead of `1` (CEP) or LTO.

## 2. UIHH Container Packaging (`Luv.d(Context)`)
Download EPO from `https://unlock.freemyband.com/agps.html?v=2` (`epo_YY-MM-DD.zip`) and pack:
- `EPO_GR_3.DAT` -> tag `0x8C`
- `EPO_GAL_7.DAT` -> tag `0x8D`
- `EPO_BDS_3.DAT` -> tag `0x8E`

UIHH layout (Little-Endian, CRC32 via `Lqn0.c(byte[])`):
```text
[32-byte UIHH Header]
  0x00..0x03 : Magic "UIHH" (55 49 48 48)
  0x04..0x0B : 04 00 00 00 00 00 00 01
  0x0C..0x0F : uint32 LE  CRC32(bodyBytes)
  0x10..0x15 : 00 00 00 00 00 00
  0x16..0x19 : uint32 LE  bodyBytes.length
  0x1A..0x1F : 00 00 00 00 00 00

[Body: repeated per item]
  0x00       : 0x01
  0x01       : uint8      itemTag (0x8C / 0x8D / 0x8E for EPO; 0x8B for LTO)
  0x02..0x05 : uint32 LE  payload.length
  0x06..0x09 : uint32 LE  CRC32(payload)
  0x0A..     : byte[]     raw payload bytes
```

## 3. Fast BLE Transfer Fixes
1. **`Luv.i(BLEManager, File, Ls6)`**: Clear `BLEManager.D1 = 0L` and request `BluetoothGatt.CONNECTION_PRIORITY_HIGH` (`1`).
2. **`BLEManager.C1(ch, data, mode)`**: For `00000016-*`, `00000017-*`, `00000023-*`, `00000024-*`, set `WRITE_TYPE_NO_RESPONSE` when supported and force **`mode = 2`** (bypasses `J1` lock and 600ms per-packet sleep).
3. **`Lqn7.l(long)`**: Relax fast-write timeouts `<= 100ms` (40ms) to `3000ms` safety ceiling during active AGPS transfer.
4. **`Lou8` / `Lmu8`**: Refresh `ou8.j` on chunk activity and prevent Main Thread lock contention / premature 5s timeout while chunks are progressing.

## 4. Progress & Endpoint `0x42` Flash Verification (`Lku8`, `Ls6`, `UpdateFirmwareActivity`)
- Broadcast `com.mc.miband.firmware.progress` in `Lku8.q(int)` and update `UpdateFirmwareActivity.R` in `Llj4.d`.
- On `Lku8.l(false)` (`mode == 3`), do not send `[0x05]`; broadcast `com.mc.miband.firmwareKO` and invoke `s6.d.e(false)`.
- Observe Endpoint `0x42` (`Ls6.f`): `cmd == 0x06` reports UART flash progress (`totalBytes` at `1..4` LE, `installedBytes` at `5..8` LE); `cmd == 0x07` reports install status (`data[1] == 0x01` = success), then re-query Endpoint `0x0A` to refresh hardware timestamps.
