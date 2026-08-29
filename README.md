# 121GW-Android-port made with Grok AI

Native Android port of the iOS 121GW companion app (v1.4.22).  
Functions, packet decoding, logging behaviour, and interface layout match the iOS application.

## Open in Android Studio

1. Open this folder as a Gradle project.
2. Let Gradle sync (AGP 8.5 / Kotlin 1.9 / Compose BOM 2024.06).
3. Build the `app` module and run on a device with Bluetooth LE (API 26+).

## What is preserved

- Packet V2 framing (`0xF2`, 19 bytes, XOR checksum) and official range/unit tables
- Characteristic `E7ADD780-B042-4876-AAE1-112855353CC1`
- Remote keys HOLD / MODE / REL / RANGE / MIN-MAX / MEM / PEAK / SETUP (short + long payloads)
- LCD replica (BT, AUTO, 1kHz, 1ms, LowZ, BAT, APO, DC/AC/DC+AC, DIODE, CONT, REL, HOLD, MIN/MAX, MEM, dBm, TEST)
- Live trend (last 25,000 samples), internal-temperature overlay toggle
- Session log / CSV export / notes / min-max-avg-pp statistics
- Pause log when measurement *function* changes; range changes stay in the same log
- Continuity/diode beep + vibration with ON/OFF ohm hysteresis (defaults 25 / 400 Ω)
- Auto-reconnect, RSSI in dBm, meter battery / clock from SETUP sub-modes
- Communication-lost watchdog (5 s) and low-battery banners (4.5 V / 4.2 V)
- Foreground service while connected so BLE notifications continue in the background

## Permissions

Android 12+: `BLUETOOTH_SCAN` (neverForLocation) and `BLUETOOTH_CONNECT`.  
Android 11 and below: legacy Bluetooth + fine location for scanning.

Hold **1ms PEAK** on the 121GW until the BT icon appears, then Scan / Auto-reconnect.
