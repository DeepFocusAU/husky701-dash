# Husky701 Dash (LilyGO T-Display-S3)

> **Discontinued.** This was the first display for the project. Development has moved to the Android app in [`../android`](../android), which runs a Samsung Note 10 as the dash. This code still builds and works with the CAN node over RS-485, and is kept as a reference and a fallback.

Custom dash for a 2023 Husqvarna 701. The main screen shows:

- **Tacho bar** across the top (green → amber → red), with a peak-hold marker and a shift-light flash. The RPM figure sits top-left.
- **Large gear number**: green for neutral, white for every other gear.
- **Coolant (°C) and battery (V)** with icons. The numbers are always white. Warnings change the icon colour:
  - Coolant icon: blue when cold, flashing red when overheating.
  - Battery icon: red when not charging or over-voltage, amber when low.
- Clutch, stand, map, kill switch and the data source are on the Diagnostics page.

## Builds

| Env | Data source | Hardware needed |
|-----|-------------|-----------------|
| `sim` (default) | Built-in simulator. Runs a ~70 s scripted ride on a loop. | Just the T-Display-S3 |
| `live` | Frames from the under-seat CAN node over RS-485 | MAX3485 module on the dash |

In VS Code, pick the env in the PlatformIO toolbar. Then **Upload** and open the **Monitor** at 115200.

Nothing is saved to flash while the data source is SIM or SIM-LOOP, so test rides can't affect your real trip, fuel or waypoint records.

The simulator builds real frames in the wire format (`include/telemetry_packet.h`) and pushes them through the real parser. That way the CRC check, drop counter and NO LINK timeout all get tested too. It deliberately corrupts 1 frame in 250 and drops 1 in 400, so the diagnostics page counters move. To turn that off, set `SIM_FAULT_*` to 0 in `include/config.h`.

The scripted ride runs through these phases: key on, cranking, warm-up idle, full-throttle run through gears 1–6 (hits the shift light), cruise in map 2, downshifts, hot idle (trips the coolant warning), engine off.

## Piezo switch

The dash is driven by one momentary piezo switch. It only sends a pulse and can't be held, so functions are picked by the number of quick taps. A gesture ends 450 ms after the last tap (`TAP_GAP_MS`), so every action happens just after you stop tapping.

| Where | 1 tap | 2 taps | 3 taps | 4 taps |
|---|---|---|---|---|
| Any page | Next page | Page action (below) | Cycle brightness | – |
| Fill-up: FULL / PARTIAL | Switch | Next step | Cancel | – |
| Fill-up: litres | +0.5 L | Save | Cancel | −0.5 L |
| Tyre pairing | Next sensor | Assign | Exit | Skip wheel |
| GPS pages | Next page | Drop waypoint | Cycle brightness | Waypoint menu |
| Waypoint menu | Next | Choose | Exit | Back |

Page actions: Fuel opens the fill-up editor, Tyres starts pairing, GPS drops a waypoint, and Diagnostics cycles the data source. The Main page has none.

More than 4 taps does nothing, so you can abandon a miscounted gesture by tapping on. Each gesture is logged to the serial monitor as `[taps] n`, which helps when checking the wiring.

**Wiring:** the switch output goes between GPIO14 and GND, so it pulls the pin low for each pulse. The on-board KEY button is on the same pin and works as a stand-in on the bench. BOOT (GPIO0) is not used by the dash.

- GPIO14 takes 3.3 V at most. If your switch outputs +12 V instead of a contact to ground, connect it through an optocoupler or transistor.
- A latching switch won't work: it has to return to idle after each pulse.
- If long wiring picks up false taps, add a 4.7 kΩ pull-up to 3V3 and 100 nF to GND at the dash.

**SIM-LOOP** (a data source on the Diagnostics page) sends the frames out of GPIO17 and reads them back on GPIO18, which tests the real UART path. It needs a jumper from GPIO17 to GPIO18, or later two MAX3485 modules back to back.

## Settings

All settings are in `include/config.h`:

- RPM full scale, amber point and shift-light point
- Coolant warning and cold thresholds
- Tacho response time and peak-hold time
- Battery-sense divider values and calibration
- RS-485 pins and baud rate

## Wiring for the live build (later)

- **MAX3485 to T-Display-S3:**
  - RO → GPIO18. **On this module the receiver output is the pin labelled TXD**, not RXD. Labels vary between modules: during the node's `h` line test, RO is the pin that goes low on red.
  - DE and /RE (EN) → GND (receive only)
  - DI (labelled RXD on this module): not connected
  - VCC → 3V3, GND → GND
- **RS-485 pair:** A to A and B to B at the CAN node, plus a GND wire.
- **Bus bias:** the node's MAX13487E requires a pull-up on A and a pull-down on B, even though it only transmits. Fit 270–390 Ω from A to 3V3 and the same from B to GND at the dash. Idle A to B should read above 0.2 V (0.3 V with 270 Ω and the two on-board terminators). Don't use kΩ values: with 3.9 kΩ the node's chip never releases the line.
- **Wiring check:** type `linktest` in the serial monitor. With the node sending, GPIO18 should show edges and be high most of the time.
- **Battery sense:** done by the CAN node. See its README.

## Toolchain notes

The platform is pinned to `espressif32@6.9.0` (Arduino-ESP32 2.0.17), because TFT_eSPI does not work on core 3.x. The TFT_eSPI setup for the T-Display-S3 is passed as build flags in `platformio.ini`, so you don't need to edit any library files.

`include/telemetry_packet.h` is the contract with the CAN node. Copy it unchanged into that project.

## Pages

1 tap cycles **Main → Fuel → Trip → Tyres → GPS → GPS data → Clock → Diagnostics**. Any tap gesture while a warning banner shows dismisses it for 5 minutes. 3 taps change brightness.

### Fuel

There is no fuel sensor. Fuel used is modelled from the CAN data as `k × rpm/1000 × (idle load + throttle)`, plus distance from wheel speed. The page shows:

- range (km)
- litres in the tank, with a gauge
- average L/100 km since the last fill-up
- current L/100 km (L/h when stopped)
- litres and km since the fill-up

The fuel icon and gauge go amber below 60 km of range and flash red below 30 km.

**How it estimates.** Road speed and distance come from GPS when there's a fix, and wheel speed otherwise. No fuel is counted on overrun (closed throttle above 2200 rpm while moving), as most fuel-injected engines cut fuel then. That's assumed rather than measured on this bike. Range starts from your average over past tanks and moves to the current tank's over the first 50 km. Saving FULL fill-ups with the litres pumped remains what calibrates it most.

**Fill-up** (only when stopped): 2 taps on the Fuel page.

1. 1 tap switches between **FULL TANK** and **PARTIAL**. 2 taps go to the next step. 3 taps cancel.
2. 1 tap adds 0.5 L and 4 taps take away 0.5 L. 2 taps save. 3 taps cancel.
   - **PARTIAL:** adds the litres to the tank.
   - **FULL** with the pumped litres entered: sets the tank to full and **teaches the model**. Each full-to-full interval corrects `k`, and the page shows the learned factor.
   - **FULL** with 0 L: sets the tank to full without learning.

The tank state and learned `k` are saved to flash every 30 s and at each fill-up, so they survive key-off. Set `FUEL_TANK_L` in `include/config.h` to match your owner's manual.

### Shift lights (Main page)

- **Red flash, with a ▲ in the gear panel:** shift **up** at `SHIFT_RPM`.
- **Blue flash, with a ▼ in the gear panel:** shift **down**. The engine is lugging: at least `LUG_THROTTLE_PCT` throttle below `LUG_RPM`, in gear 2 or higher with the clutch out, for `LUG_HOLD_MS`. The simulator's ride includes a lugging stretch to show it.

### Trip

Two sets of figures side by side, both saved through key-off:

- **Today:** since 00:00 local time. It clears itself at midnight, or at the first GPS fix on a new day. Riding before that first fix is lost when it clears.
- **Since refuel:** since the last fill-up saved on the Fuel page, full or partial. Nothing else clears it.

Each shows distance and fuel used, then L/100 km, riding time (above 3 km/h), and average and maximum speed.

### Tyres

Pressures from Bluetooth valve-cap sensors. The dash scans passively, so nothing is paired at the Bluetooth level and the sensors still work with their own app.

- **Recommended sensors:** **FOBO Bike 2** (motorcycle-specific, replaceable batteries, anti-theft caps).
- **Also decoded:** TPMSII, WODHMIEY / ITPMS and SYTPMS caps. The decoders follow bkbilly/tpms_ble.

**The page:** a FRONT and a REAR tile, each with pressure (psi, or bar with `TPMS_PSI 0`), tyre temperature, how long ago the reading arrived, and the sensor battery.
- The tile flashes red, and a red banner appears, when pressure drops below `TPMS_FRONT_LOW_BAR` / `TPMS_REAR_LOW_BAR`.
- A reading older than 15 min is greyed out and never triggers a warning.

**Pairing:** 2 taps on the Tyres page.
- Nearby sensors are listed strongest first. 1 tap moves through them, and 2 taps assign the highlighted one to FRONT, then REAR.
- 4 taps skip a wheel. 3 taps exit.
- Hold the dash near the wheel, or spin it, to make the right sensor the strongest.

The sim build adds two pretend sensors: the rear one leaks every lap.

### Warnings

| Banner | When |
|---|---|
| FRONT / REAR TYRE LOW (red) | below the threshold. Shows for 8 s and repeats every 5 min while low |
| BATTERY BAD (red) / WEAK (amber) | after a start, if the lowest cranking voltage was under 9.0 V / 9.6 V, or the resting voltage under 12.2 V. Cranking history is on the Diagnostics page |
| KEY ON, ENGINE OFF (amber) | engine off and stopped for 3 min with the key on. Says whether the kill switch is off too |

### GPS

Needs the GPS module on the CAN node (see its README). The sim build drives this page with a pretend route, so you can try it on the bench.

**Map.** The trail fills the screen in cyan, north up, with your position in the centre. Waypoints are orange numbered dots, and the zoom follows your recent trail and the waypoint you are heading to. The label at the bottom left is the distance from the centre to the top or bottom edge. There is no topo map background.

**Compass, top right.** The ring turns so your direction of travel is at the top. It uses GPS course, as there is no magnetic compass, so it only works above 5 km/h. When stopped it shows `---` and the map shows a dot instead of a pointer.

**Speed, top left,** with the waypoint and satellite counts below it, or the waypoint and distance to go when navigating.

**Latitude and longitude, bottom right,** on one large line, to 3 decimal places (about 100 m).

**GPS data page.** The next page along, in the style of a Garmin trip computer: speed, heading, elevation, distance to go, bearing, time to go, today's distance, moving time and estimated accuracy. Accuracy is a rough figure worked out from satellite geometry.

**Waypoints (breadcrumbs).** 2 taps on the GPS page drop one at your position, at any speed. Up to 40 are kept, then the oldest is forgotten. They survive key-off.

**Track back.** 4 taps on either GPS page open the menu, at any speed, listing waypoints newest first with their distance from you. 1 tap moves on, 4 taps move back, 2 taps choose, 3 taps exit.

- The yellow arrow in the compass points to the next waypoint, relative to your direction of travel. When stopped it shows the compass bearing as a number instead.
- If you choose an older waypoint, it leads back through the newer ones in order: `W5 > W3` means heading to W5, on the way to W3. Each one counts as reached within 30 m.
- It points in a straight line, not along a road or track, so choose your own route.
- To stop, open the menu and choose **Stop navigating**.

**TracBack.** The second menu item retraces the trail itself, joining at the nearest point and working back to the start of the track, rather than heading straight for a waypoint.

**Trail.** A point is added every 20 m. After 256 points, it keeps every other point and doubles the spacing, so it always covers the whole trip, with older parts in less detail. It saves every 2 minutes, so the last 2 minutes can be lost at key-off.

**Clear waypoints + trail**, the last menu item, needs 2 taps twice, and clears both.

### Night dimming

Once the clock is set, the backlight drops to `NIGHT_LEVEL` from 19:00 to 06:30 and comes back up in the morning. 3 taps still change brightness by hand until the next day/night change.

### Clock

Full-size 24-hour HH:MM with nothing else on screen. The time comes from the GPS on the CAN node, so there is nothing to set, and it updates daylight saving by itself. It shows `--:--` after key-on until the GPS has a fix, then keeps running if the fix is lost. The time zone is `GPS_TIME_ZONE` in the CAN node's `include/config.h`.

### Diagnostics

2 taps cycle the data source: SIM → SIM-LOOP → LIVE. This only works on the Diagnostics page now, so it can't be changed by accident while riding.
