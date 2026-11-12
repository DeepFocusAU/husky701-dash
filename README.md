# Husky701 Dash

An open-source digital dash for a **2023 Husqvarna 701**. A small ESP32 node under the seat listens to the bike's CAN bus and sends live telemetry to two dashes:

- an **Android app** that runs a Samsung Note 10 as a full-screen dash, over Bluetooth LE (the current display)
- a **LilyGO T-Display-S3** ESP32 dash, over a wired RS-485 link (the first display, now discontinued in favour of the Android app)

**Docs site:** https://deepfocusau.github.io/husky701-dash/

```
 2023 Husqvarna 701                 Under the seat                         On the handlebars
┌──────────────────┐   CAN 500k   ┌──────────────────────────┐  RS-485    ┌──────────────────────┐
│ ECU, ABS         │──listen-only─▶│ CAN node                 │──115200──▶ │ ESP32 dash           │
│ (Euro 5 diag     │              │ LilyGO T-CAN485 (ESP32)  │  50 fps    │ T-Display-S3         │
│  connector)      │              │ decode · SD log · GPS    │            └──────────────────────┘
└──────────────────┘              │ battery sense · taps     │  BLE GATT  ┌──────────────────────┐
         ACC2 12 V ─▶ 5 V buck ──▶ │                          │──25 fps──▶ │ Android dash         │
                                  └──────────────────────────┘ ◀─mode──── │ Samsung Note 10      │
                                                                          └──────────────────────┘
```

## What's in the repo

| Folder | What it is | Toolchain |
|---|---|---|
| [`can-node/`](can-node/) | Firmware for the under-seat node: listen-only CAN, decoder, RS-485 and BLE telemetry, SD logging, GPS, battery sense, piezo tap input | PlatformIO, `espressif32@6.9.0` (Arduino-ESP32 2.0.17), NimBLE |
| [`can-node/logs/`](can-node/logs/) | Raw CAN captures from the bike (on the stand), used to build the signal table | CSV: `ms,id,len,data` |
| [`esp32-dash/`](esp32-dash/) | **Discontinued** in favour of the Android app. The original T-Display-S3 dash: tacho, gear, coolant, battery, fuel, trip, tyres, GPS, diagnostics, built-in simulator. Still builds, kept as a reference | PlatformIO |
| [`android/`](android/) | The Android dash for a Samsung Note 10: same pages plus heading tape, waypoints, what3words, split screen with a map app, TPMS | Android Studio / Gradle, Kotlin, JDK 17 |
| [`docs/`](docs/) | The GitHub Pages site: architecture, CAN signals, wire format, wiring | Static HTML |

Each folder has its own detailed README. Open `husky701-dash.code-workspace` in VS Code to get all three projects side by side (PlatformIO picks up `can-node` and `esp32-dash`).

## Quick start

**CAN node**
```bash
cd can-node
pio run -e selftest -t upload   # bench: made-up CAN data, no bike needed
pio run -e node -t upload       # the real firmware
pio run -e node_ota -t upload   # the same, over the phone hotspot (see can-node README)
pio device monitor -b 921600    # press s for status
```

**ESP32 dash**
```bash
cd esp32-dash
pio run -e sim -t upload        # built-in scripted ride, no node needed
pio run -e live -t upload       # frames from the CAN node over RS-485
```

**Android dash**
```bash
cd android
./gradlew testDebugUnitTest assembleDebug
adb install -r -g ~/Android/build/Husky701.Android/app/outputs/apk/debug/app-debug.apk
```
The build output goes to `~/Android/build/` rather than `android/build/`, so cloud-synced folders such as OneDrive don't lock files mid-build. Change `layout.buildDirectory` in `android/build.gradle.kts` if you'd rather keep it in the project.
Optional: add `w3w.apiKey=your-key` to `android/local.properties` (never committed) for what3words lookups.

## Key design points

- **Read-only on the bike's bus.** The CAN controller runs in listen-only mode, and its TX signal is routed to a spare pin while the transceiver input is held recessive. Two independent layers, so the node can never disturb the ECU or ABS.
- **One wire format everywhere.** `telemetry_packet.h` (38-byte payload, 42-byte frame, CRC-16/CCITT-FALSE) is identical in the node and the ESP32 dash, and the Android app parses the same bytes from BLE notifications.
- **Stale data can't freeze the dash.** Every CAN signal times out to unknown or zero (300 ms fast IDs, 1 s slow IDs). The dashes show NO LINK when frames stop.
- **SIM everywhere.** Each part has a simulator that drives the real parsers. The phone can switch the node into SIM over BLE; frames carry a flag so made-up data is never shown as live, and the node always powers up LIVE.

## Status

Signals confirmed on the bike: rpm, throttle, gear, clutch, kill switch, throttle map, coolant, front wheel speed. Still to do: wheel-speed scale and rear wheel (needs a ride with GPS), side stand (bypassed on this bike), traction control and IMU IDs. See [`can-node/README.md`](can-node/README.md#signals).

## Safety

This is a hobby project for off-road and test use. It is not a certified instrument. Check local road rules before relying on it. Wire everything through a fused, switched (ACC2) supply, and read the node's wiring notes before connecting anything to the bike.

## Licence

MIT, see [LICENSE](LICENSE). Not affiliated with Husqvarna Motorcycles or KTM.
