# Husky701 Dash (Samsung Note 10)

An Android version of the Husky701 dash, for running a Samsung Note 10 as a single-purpose bike dash. It does the same job as the ESP32 dash (`Husky701.Dsisplay`) and gets the same telemetry from the under-seat CAN node, over Bluetooth LE instead of RS-485.

Both dashes can run at once: the CAN node sends RS-485 and Bluetooth side by side.

## Pages

All of the ESP32 dash's features are ported. **Swipe down** for map view (map app + tacho strip), **swipe up** on the strip to come back. **Swipe left or right** to move through **Main → Fuel → Trip → Tyres → Map → Diagnostics** (it wraps round). The dots at the bottom show where you are. There is no Clock page: the time is in the header of every page.

Everything else is a touch button on the page. There are no tap sequences on the screen.

| Page | What it shows | Buttons |
|---|---|---|
| Main | Vertical tacho with peak hold and shift-up/down lights, map, status chips, gear, coolant, **fuel level and range**, battery, throttle, speed | – (swipe down for map view) |
| Fuel | Range (amber under 60 km, steady red under 10 % of a full tank's range, flashing red under 5 %), tank gauge, average and current L/100 (L/h when stopped), used and km since fill-up, learned factor | **FILL UP** (at any speed): FULL TANK / PARTIAL (PARTIAL can go below zero for fuel taken out, down to what's in the tank), litres with −1 −0.5 +0.5 +1, CANCEL / SAVE |
| Trip | Today and Since refuel: distance, fuel, L/100, riding time, average and maximum speed | – |
| Tyres | Front and rear pressure, temperature, reading age, sensor battery (FOBO Bike 2, TPMSII, ITPMS, SYTPMS caps) | **PAIR SENSORS**: tap a sensor in the list, then SET FRONT / SET REAR, SKIP or CANCEL |
| Map | Heading tape, latitude and longitude (hemisphere, degrees and decimal minutes, e.g. S 33° 53.946′ / E 151° 14.035′), what3words address, north-up trail, waypoints, heading pointer, scale | **− / +** or **pinch** to zoom, **AUTO** zoom, **DROP WPT**, **WAYPOINTS**; tap a waypoint (on the map or in the list) to select it, then **NAVIGATE** or **RENAME**; **hold** one to name it (e.g. Camp, Fuel) |
| Diagnostics | Link, Bluetooth, GPS sources, battery cranking history, tyre sensors heard | **LIVE \| SIM** data source (the lit half is in use, tap the other to switch; every page header shows an orange SIM tag in SIM). The CAN node follows over Bluetooth: in SIM it sends its made-up test data to the ESP32 dash too, and the "node sending" line shows what it's sending (amber if it doesn't match), **THROTTLE ON / OFF** (shows or hides the throttle bar on the main page, kept across restarts), **BRIGHT** level, **SETTINGS** (tap twice: maintenance) |

**Waypoints sheet:** each row shows the waypoint, its distance and compass direction, and ◀ NAV on the one being navigated to. Tap a row to navigate to it (or TracBack). Swipe up and down, or use ▲ ▼, to scroll. "Clear waypoints + trail" needs a second tap. Swipes don't change page while a sheet is open.

**Heading tape (Map page):** across the top, dive-computer style. The heading is in big digits, with its 16-point direction beside it, and a strip of 5° ticks with N, NE, E… labels (red N) slides under a fixed red centre line, over a blue line,, showing 60° either side. The heading comes from the phone's own compass, corrected to true north for where you are (about 12.8° around Sydney), so it works stopped as well as moving, with the phone mounted upright, flat or anywhere between. Without a compass sensor it falls back to the GPS course when moving. While navigating, a yellow triangle on the strip marks the bearing to the next waypoint (an arrow at the edge when it's more than 60° off), a yellow ◀ or ▶ left of the heading shows which way to turn and by how many degrees, and the distance shows on the right. Latitude and longitude sit side by side under the tape.

**Zoom:** pinch, or − and +, to set the map scale by hand. AUTO (lit green) goes back to fitting the trail and the target.

**what3words (Map page):** the 3-word address of where you are, under the latitude and longitude.
- It needs a free API key from developer.what3words.com. Add it to `local.properties` (never committed) as `w3w.apiKey=your-key`, then rebuild. Until then the line reads "add w3w.apiKey".
- It needs a data connection on the phone.
- Nothing is looked up by itself: **tap the red /// logo** to look up where you are. The logo dims and the line reads "looking up…" while it waits. Until the first tap the line reads "tap logo to look up".
- The key is on the free plan, so there's also a hard cap of 25 lookups a day, counted across restarts (about 750 a month).
- The last 20 answers are kept. Back on one of those squares, the address shows again by itself, and a tap there doesn't use a lookup. In SIM mode a tap looks up the simulated position, under the same cap.
- If the plan refuses, the line says "plan has no lookups left". If the key is wrong, it says "API key not accepted".
- It turns grey when you've moved off the square it describes.

**No link / no CAN data:**
- **NO LINK** shows on Main when no frames come from the node.
- **NO CAN DATA** shows when the node is connected but sees nothing on the bike's CAN bus.
- With no CAN data, everything that comes from CAN is greyed out and shows dashes: tacho, rpm, gear and shift lights, coolant, throttle, the CLUTCH and KILL chips (there is no STAND chip: the side stand switch is bypassed), and wheel speed. Speed stays live when it comes from GPS.
- The strip shows the same banners.

**Warnings:** tap a warning banner to dismiss it for 5 minutes. The warnings are the same as the ESP32 dash: tyre low, battery bad or weak after a start, and key on with the engine off.

**Key on, engine off:** after 3 minutes with CAN data, 0 rpm and the bike stopped, the warning shows. It plays **two short beeps** at once, then again every 30 s while it stays up (`KEYON_BEEP_REPEAT_MS`). The beeps use the alarm stream, so they play with the phone on silent. With no CAN data there's no warning, because 0 rpm then only means "no data".

The node's piezo taps (if fitted) still work: 1 tap = next page, 2 = page action, 3 = brightness, 4 = Main's split or the Map's waypoint menu.

## Split screen (map app + tacho strip)

**Swipe down** on any page to open **Google Maps** (default) or **Gaia GPS** sized to fit between the status bar and a minimalist tacho strip on the bottom 20% of the screen (`SPLIT_STRIP_FRACTION` in `DashConfig.kt`). The strip has the same content as the ESP32 dash's main screen, in this app's styling:
- horizontal tacho (blue to 6500, amber, red from 7500) with the rpm above it
- gear, with shift-up and shift-down arrows (shift lights flash 5 times a second, `SHIFT_FLASH_MS`)
- coolant and battery volts taking turns (15 s each) above the clock; both show if either is bad
- road speed

**In map view the strip is the only page.** On the strip, **swipe left or right** to switch between Google Maps and Gaia GPS, and **swipe up** to go back to the full dash.

**Touch on the strip:**
- the speed panel (**MAPS ⇄** or **GAIA ⇄**): switch between Google Maps and Gaia GPS (remembered), the same as a sideways swipe

Brightness is on the Diagnostics page. The node's piezo taps also work on the strip: 1 = swap coolant and battery, 2 = other map app, 3 = brightness, 4 = back to the dash.

Warnings show on the strip too. The rest of the screen is the map app, and it takes touches as normal.

**How it works:**
- Android's own split screen can't do this. On the Note 10 its divider only snaps to 50/50 or about 60/40, and an app can't close it.
- Instead, the map app is launched with window bounds for the space above the strip. Samsung opens it as a pop-up window of exactly that size, so nothing in the map is cut off.
- The strip is an overlay window. A pop-up stays on top of every app and an app can't close it, so swiping up grows the overlay to the whole display and shows the full dash over the map, and swiping down shrinks it back to the strip. If the dash restarts while a map pop-up is still open, it comes straight back up in the overlay.
- A thin dark bar covers the status bar and the pop-up's handle bar, so the map window can't be moved, resized, minimised or closed by accident. The strip covers its bottom edge. The status and navigation bars are hidden in split, like the full dash, and the strip sits at the very bottom of the screen. The map uses everything between the bar and the strip. Samsung keeps a pop-up below the status bar's height (about 4% of the screen) even when the bar is hidden, so that band at the top stays dark.
- Typing in the map app works: tap the map or its search box and the strip hands it the keyboard (the navigation bar shows while it has it, and the keyboard covers the strip). Touch the strip to take it back and hide the bars again.
- The map app keeps running behind the dash, so a Gaia track keeps recording. To close it, use Android's recent-apps button.
- A foreground service keeps Bluetooth and GPS running while the map is in front.
- The first time, Android asks for the **Appear on top** permission. `provision.ps1` grants it in appliance mode, where the map apps are also allowed in lock task.

**GPS and time come from the phone.**
- Position, speed and course come from the Note 10's own GNSS, which is much better than the node's NEO-6M. The node's GPS is only used if the phone has no fix.
- The clock, the trip's day change and night dimming (19:00 to 06:30) use the phone's clock and time zone.
- In SIM mode the simulated route is used instead, so the map can be tried on the bench.

**Saved state:** fuel, trips, waypoints, trail, paired tyre sensors and battery history survive restarts. Nothing is saved while the source is SIM.

## Appliance mode

The phone is set up as an Android **dedicated device**. This app is made device owner, so no custom ROM or root is needed. Once it's set up:

- The phone boots straight into the dash. It is the home screen, and there's no launcher to fall back to.
- The dash is pinned. There is no status bar, notification shade, home, back or recents button, and no lock screen.
- Bluetooth permissions are granted automatically, so there are no pop-ups.
- The screen stays on while powered. Two minutes after bike power goes off, the screen turns off. It comes back on when power returns.
- System updates can only install between 02:00 and 04:00.
- Samsung extras (Bixby, Game Launcher, Facebook and so on) are disabled.

**Setting it up:** factory reset the phone and skip all accounts. Turn on USB debugging, then run:

```powershell
.\gradlew.bat assembleDebug
powershell -ExecutionPolicy Bypass -File tools\provision.ps1
```

**Maintenance:** on the Diagnostics page, tap **SETTINGS**, then tap it again within 5 s. The dash unpins and opens Settings, and it locks again at the next restart.

**Undo everything:** `tools\provision.ps1 -Release`.

## Building

You need JDK 17 and the Android SDK, both in `C:\Users\asus\Android`. `local.properties` points at the SDK.

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\Android\jdk-17"
.\gradlew.bat testDebugUnitTest assembleDebug
```

The APK is written to `%USERPROFILE%\Android\build\Husky701.Android\app\outputs\apk\debug\app-debug.apk`. Build output is kept outside OneDrive, because syncing locks files in `build\` and breaks the build. The unit tests check the wire format against `telemetry_packet.h`, along with the CRC, resync, drop counting, the simulator, tap timing, swipes, the key-on beeps and the no-CAN-data rules.

## Link to the CAN node

| | |
|---|---|
| Device name | `Husky701` |
| Service | `7a1e0001-4b8f-4c1e-9a70-701d45a00001` |
| Frame (notify) | `…0002…`: one whole wire frame (42 bytes), exactly as sent on RS-485, at 25 per second |
| Taps (notify) | `…0003…`: `[gesture seq][tap count]` |

`Telemetry.kt` mirrors `telemetry_packet.h`. If the header changes, update `Telemetry.kt` and `TelemetryTest` to match. The UUIDs are in `DashConfig.kt` and in the CAN node's `include/config.h`.

## Mounting the phone

- **Vibration:** a big single-cylinder engine can wreck the camera's stabiliser. Use a vibration-damped mount.
- **Heat:** a phone charging in direct sun will throttle and dim itself. Use a sun shade, and turn on Settings › Battery › Protect battery (stops charging at 85 %).
- **Power:** feed it from switched 12 V through a quality USB-C PD supply. The screen follows that power.
