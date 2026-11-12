# Husky701 CAN node (LilyGO T-CAN485)

Sits under the seat of a 2023 Husqvarna 701. It listens to the bike's CAN bus, decodes it, and sends telemetry frames to the dash over RS-485 at 50 frames/s. It also measures battery voltage.

Board: **T-CAN485 with CH9102F USB** (LilyGO option Q331). Windows may need the CH9102 driver before the COM port appears.

`include/telemetry_packet.h` is the contract with the dash. It must stay identical in both projects.

## Builds

| Env | What it does | Needs |
|-----|--------------|-------|
| `node` (default) | The real firmware | The bike |
| `selftest` | Feeds made-up CAN frames through the real decoder and sends the result to the dash. CAN controller not started | Node and dash on the bench |
| `node_ota` | The `node` firmware, uploaded over Wi-Fi (see [Over-the-air updates](#over-the-air-updates)) | Node in update mode, computer on the phone hotspot |

## Before connecting to the bike

**1. Don't put battery voltage straight on the power terminal.** LilyGO rates it at 12 V maximum, and a running bike sits above 14 V with spikes on top. Use one automotive-rated 5 V DC-DC buck for both boards, at least 2 A, with an input rating of 40 V or more, fused and fed from switched power (ACC2). Feed each board through its USB-C port. LilyGO's specs disagree on whether 5 V is enough at the power terminal (5–12 V and 7–12 V), so leave that terminal empty. Battery sense still comes straight from ACC2, not from the buck. Unplug the buck from a board before connecting a laptop to its USB-C.

**2. Check for a 120 Ω CAN terminator on the board.** The schematic shows 120 Ω parts on the board, and the bike's bus should already be terminated at both ends. A third terminator drops the bus from about 60 Ω to 40 Ω, on the bus the ABS and ECU share.

- With the board unpowered and disconnected, measure resistance between its CAN A and B terminals.
- If it reads about 120 Ω, remove the resistor from the CAN section before connecting.
- With the bike off, CAN-H to CAN-L should read about 60 Ω before and after the node is connected.

**3. Use the right connector pins.** This bike has the Euro 5 (ISO 19689) 6-pin diagnostic connector under the seat. Its CAN pins were confirmed with a meter on this bike: CAN-H read 2.7 V and CAN-L 2.3 V with the key on. The older KTM 6-pin connector looks similar but is wired differently.

| Pin | Signal | Connect to |
|-----|--------|------------|
| 1 | Not listed | Nothing |
| 2 | CAN-H | Node CAN terminal A |
| 3 | Ground | Node CAN terminal GND, buck IN− |
| 4 | +12 V | Only if it reads 0 V with the key off. Otherwise use ACC2. Never the node's CAN terminals |
| 5 | CAN-L | Node CAN terminal B |
| 6 | K-line | Nothing |

## Wiring

| From | To | Notes |
|------|----|-------|
| Bike CAN-H / CAN-L | CAN terminal | Start with H to A and L to B. Swap if `buserr` climbs and no frames arrive. Connect GND too |
| RS-485 terminal A / B | Dash MAX3485 A / B | Twisted pair, plus GND |
| ACC2 → 100 kΩ → IO34 → 18 kΩ → GND | Header | 100 nF across the 18 kΩ. 15 V reads 2.29 V at the pin (`VBAT_R_BOTTOM` in `config.h` must match the fitted resistor). Add a 3.3 V clamp on IO34 if the wiring is exposed to spikes |
| IO25 | Nothing | The CAN controller's TX signal is parked here. Leave it unconnected |

The link is one-way: the node transmits and the dash listens. The MAX13487E switches direction by itself, so the node needs no enable line. Its datasheet requires a pull-up on A and a pull-down on B to hold the idle bus above 0.2 V, so fit 270–390 Ω from A to 3V3 and the same from B to GND at the dash. The bench link works with 270 Ω, which reads 0.3 V idle with the terminators on both boards. Don't use kΩ values: with 3.9 kΩ the chip never releases the line.

## Read-only on the bike's bus

The bus is shared with the ABS and ECU, so the firmware has two independent layers keeping it silent:

1. The CAN controller runs in listen-only mode, so it never transmits or sends an ACK.
2. Its TX signal goes to spare pin IO25, and the transceiver's D pin is held recessive as a plain GPIO.

The node doesn't ACK. If it's somehow the only thing awake on the bus, that's harmless, because the bike's own modules ACK each other.

The SE pin must stay LOW. On this transceiver (SN65HVD231) SE high means **sleep with the receiver off**, not listen-only.

## Bring-up order

1. **Bench, link only.** Upload `selftest` and connect RS-485 to the dash running its `live` build. The LED flashes green. The dash should sweep through rpm, gears and coolant over 20 s and show 13.8 V, with no CRC errors climbing on Diagnostics.
2. **Bike, key on, engine off.** Upload `node`, complete the checks above, and connect. The LED should go from red to green. Open the monitor and press `s`.
3. **Check the IDs.** 0x120, 0x128, 0x129, 0x12D and 0x540 should all appear at about the rates below. `buserr` should stay still, and `missed` and `overrun` should stay at 0.
4. **Check each signal** against the table below by working the controls with the monitor open.

## Status LED

The on-board LED updates once a second. The status line shows the same state as `led=R`, `A` or `G`.

| Colour | Meaning | Check |
|--------|---------|-------|
| Red | No bike data: no CAN traffic for 500 ms, or the CAN controller isn't running | Key on? CAN wiring, H and L swapped |
| Amber | Running, but with a problem: one of the five IDs is missing, the battery reading is missing, the SD card isn't logging, or CAN errors or lost frames in the last second | Press `s` to see which |
| Green | All five IDs arriving, battery reading present, SD card logging, no new errors | – |

Battery voltage only has to be present. Amber doesn't mean the battery is low, because the dash handles those warnings. Without the divider wired, the LED can't go past amber.

A brief amber is normal if a frame is damaged by noise. Steady amber needs looking at. If it stays amber with the engine off on your bike, the ECU may stop sending one of the IDs in that state: check with `s`.

In the `selftest` build, and in SIM mode from the phone, the LED flashes, so made-up data can't be mistaken for the real thing.

## Signals

From captures on this 2023 701 (`logs/LOG00014.CSV` and `LOG00017.CSV`, on the stand, with no ride yet). This bike differs from the 2020 KTM 690 Enduro R in [blalor/ktm-can](https://github.com/blalor/ktm-can) and [KTMComputer](https://github.com/ChickenPoptart/KTMComputer): there's no 0x12B, and the 0x540 bytes sit one position earlier. Bus: 500 kbit/s, 11-bit IDs.

| ID | Every | Bytes | Signal | Status |
|----|-------|-------|--------|--------|
| 0x120 | 20 ms | D0–D1 big-endian | rpm | Confirmed |
| | | D2 | throttle, 0–255 | Confirmed |
| | | D3 bit 3 | kill switch, 1 = run | Fits both captures: set just before each start, cleared as the engine stopped. Not flicked on its own |
| | | D3 bit 4 | starter button | Fits the start. Not used |
| | | D4 bit 0 | throttle map | Confirmed: follows the button held for about 3 s |
| 0x128 | 20 ms | D2 bit 0 | 0 = throttle open | Confirmed |
| 0x129 | 20 ms | D0 high nibble | gear, 0 = neutral, 1–6 | Confirmed, gears N and 1–4 seen |
| | | D0 bit 3 | clutch in | Confirmed |
| 0x12D | 10 ms | D0–D1 big-endian | front wheel speed (raw) | Confirmed by hand-spinning. **Scale not set**: needs a ride with GPS |
| | | D2–D3 big-endian | rear wheel speed (raw) | **Unconfirmed**: stayed 0 with the rear wheel stopped, as expected |
| 0x540 | 100 ms | D3 bit 0 | side stand, 1 = up | **Can't be tested**: this bike has a side stand switch bypass, so it always reads up |
| | | D3 bit 4 | engine not running | Fits the start. Not used |
| | | D5–D6 big-endian | coolant, 0.1 °C | Confirmed, 23.5 to 54.6 °C on warm-up |

Not decoded, but seen in the capture: 0x4C1 D0 is the handlebar buttons (bit 0 = map, bit 1 = traction control, set while held). 0x12A D1 changed from 2D to 1D while traction control was switched, which may be its on/off state: not yet confirmed.

The bike clock isn't on this bus: no byte stepped at each minute during 5 minutes of key-on. The dash's manual clock stays in use.

At key-off, the last module awake repeats its final 0x120 frame very quickly for about a second, because nothing is left to acknowledge it. This node never acknowledges, by design, so these repeats are normal and show in the log.

A signal that stops arriving goes to unknown or zero within 300 ms (1 s for 0x540), so the dash can't freeze on an old rpm.

Wheel speed scaling lives in the dash (`WHEEL_KMH_PER_COUNT`). Check it against GPS.

## USB serial tools

Monitor at **921600**. Type one letter:

| Key | Does |
|-----|------|
| `s` | Status line and a table of every ID seen, with rate and latest bytes |
| `r` | Log every frame |
| `c` | Log only frames whose bytes changed |
| `o` | Logging off |
| `h` | RS-485 line test: 3 s low (LED red), 3 s released (LED off), for 60 s. Pauses everything else, so bench use only |

Log format: `millis ID length bytes...`. The dash link keeps running while logging. If the USB port can't keep up, log lines are dropped and counted as `logdrop`, and nothing else slows down.

To find the bike clock, follow the steps in the dash README using `c`. Some IDs have a rolling counter in D7, so they change on every frame. Then decode that ID in `src/can_decode.cpp` and fill `clock_hour` / `clock_min`.

## SD card log

With a FAT32 card fitted, the node records every CAN frame, with no laptop needed. Each power-on starts a new file: `LOG00001.CSV`, `LOG00002.CSV` and so on.

```
ms,id,len,data
1234,12B,8,0102030405060708
```

`ms` counts from power-on, as the node has no clock. At about 1100 frames/s, expect roughly 120 MB per hour of key-on.

- **Key-off:** cuts power without warning, so the last 2 s of a log can be lost. Earlier data is safe.
- **Slow card:** log lines are dropped and counted, and the dash link isn't affected.
- **LED:** a missing or unreadable card, or a write failure such as a full or removed card, turns it amber, so you can tell with no laptop. The card check takes up to about 3 s after power-on. Press `s` to see the SD state.
- **Removing the card:** turn the key off first. Refit it before the next key-on, as the card is only checked at startup.
- **Flashing:** the SD slot shares GPIO2 with the boot pins. If an upload fails to connect, take the card out and try again.

## GPS (GY-GPS6MV2)

Sets the dash clock and records position and speed in the SD log, at 1 update per second. It's optional, and the LED doesn't depend on it.

| GPS module | Node header |
|------------|-------------|
| VCC | VDD (check it reads 3.3 V) |
| GND | GND |
| TX | IO35 |
| RX | Not connected |

- **Antenna:** the flat ceramic side faces up, with a view of the sky through plastic or foam, away from metal, carbon, the battery and the buck. Its tiny connector is fragile, so secure the lead.
- **First fix:** can take a few minutes outdoors. The module keeps satellite data on its own backup cell for a while, which shortens later starts.
- **Dash clock:** updated from GPS once there's a fix. The time zone is `GPS_TIME_ZONE` in `include/config.h`, including daylight saving.
- **SD log:** GPS lines look like `1234,GPS,$GPRMC,...`, in with the CAN frames, using the standard NMEA sentence.
- **Status:** press `s` for `# GPS no data`, `searching` or `fix`, with the satellite count.

## Over-the-air updates

After one USB upload, the node can be updated over Wi-Fi, through the Samsung's hotspot. Wi-Fi is off the rest of the time.

**One-time setup**
1. Copy `secrets.example.ini` to `secrets.ini` and set a long `ota_password`. `secrets.ini` is gitignored. A firmware built without it refuses update mode.
2. Upload `node` over USB once. This build changes the partition table to `min_spiffs.csv` (two 1.9 MB app slots), which OTA can't do by itself.
3. On the phone, hold **NODE UPDATE** on Diagnostics and enter the hotspot name and password (Settings > Connections > Mobile Hotspot). The app keeps them.
4. Windows: allow inbound TCP port 32323 for the PlatformIO Python (or for that port), because the node connects back to the computer during an upload:
   ```powershell
   New-NetFirewallRule -DisplayName "ESP32 OTA" -Direction Inbound -Protocol TCP -LocalPort 32323 -Action Allow -Profile Private,Public
   ```

**Each update**
1. Engine off, key on. Turn on the phone's hotspot.
2. Tap **NODE UPDATE**. The first time, Android pairs with the node (no PIN). The `update` line on Diagnostics goes `connecting to …`, then `ready 192.168.x.x`.
3. Join the laptop to the same hotspot and run `pio run -e node_ota -t upload` in `can-node`. If `husky701-node.local` doesn't resolve, add `--upload-port 192.168.x.x` with the IP from the app.
4. The status shows `updating NN%`, then `done, restarting`. The node reboots on the new firmware and the phone reconnects.

**Safety rules in the firmware**
- Refused while the engine runs (rpm > 0 in LIVE). If the engine starts during update mode, Wi-Fi switches off.
- Update mode ends after 10 minutes without an upload, on STOP UPDATE, or on any failure.
- The hotspot details are sent over an encrypted BLE link and are never saved on the node (`WiFi.persistent(false)`).
- An upload must know the OTA password, and is MD5-checked before the node switches to it. An interrupted upload leaves the old firmware running, and USB always works as a fallback.
- The status line shows `# UPDATE …` while it's on, and every change is printed as `# update: …`.

## Settings

All in `include/config.h`: pins, link rate, signal timeouts, and the battery divider with `VBAT_CAL`. To calibrate, compare `vbat=` in the status line with a multimeter at the battery and set `VBAT_CAL` to multimeter ÷ reading.

Below 3 V the node reports battery as unknown, so a disconnected divider doesn't show as a flat battery.

## Toolchain

`espressif32@6.9.0` (Arduino-ESP32 2.0.17), the same as the dash. Pin numbers are from LilyGO's pin map and current `pin_config.h`. An older config on their `main` branch has CAN TX and RX swapped: ignore it.

## Bluetooth link to the Android dash

The node also sends telemetry over Bluetooth LE to the Android dash (`android/`, a Samsung Note 10). This runs alongside RS-485, so the ESP32 dash keeps working.

- It advertises as `Husky701`. The phone finds it and reconnects by itself after key-off.
- It sends every 2nd frame (25 per second), byte for byte the same as on RS-485 (`BLE_FRAME_DIV`).
- The status line shows `# BLE PHONE CONNECTED sent=… mode=LIVE` or `# BLE advertising`.
- **LIVE / SIM from the phone:** the phone's Diagnostics LIVE | SIM switch writes the Mode characteristic (1 = SIM, 0 = LIVE). In SIM the `node` build ignores the bike and sends the same made-up sweep as `selftest` (13.8 V, LED flashing), on RS-485 too, so the ESP32 dash shows it as well. Bit 0 of the frame's `reserved` byte is set while it does. It always powers up LIVE, and goes back to LIVE as soon as the phone disconnects, so it can't be left on made-up data. `# mode: SIM` / `# mode: LIVE` is printed on each change.
- **Update mode:** the Ota characteristic starts the Wi-Fi update mode, see [Over-the-air updates](#over-the-air-updates).
- To turn it off, set `BLE_ENABLE 0` in `include/config.h`. The UUIDs are there too, and must match `DashConfig.kt` in the Android project.

**Piezo switch for the phone:** wire the switch between **IO32** and GND (`PIN_TAP_SWITCH`). The node counts the taps with the same timing as the ESP32 dash and sends each finished gesture to the phone. IO32 takes 3.3 V at most, so a switch that outputs +12 V needs an optocoupler. Check that IO32 is free on your header before wiring it.
