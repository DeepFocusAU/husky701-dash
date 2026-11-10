// config.h - dash settings. Tune these for your bike.
#pragma once

// Serial logging that never holds up the dash. With USB connected but nothing reading
// the port, a plain print stalls until the buffer has room, freezing the screen and taps.
#define LOG_MIN_FREE 160
#define LOG_PRINTF(...)  do { if (Serial.availableForWrite() >= LOG_MIN_FREE) Serial.printf(__VA_ARGS__); } while (0)
#define LOG_PRINTLN(...) do { if (Serial.availableForWrite() >= LOG_MIN_FREE) Serial.println(__VA_ARGS__); } while (0)

// ---------------- T-Display-S3 board pins ----------------
#define PIN_LCD_POWER     15   // must be HIGH to power the LCD
#define PIN_LCD_BL        38   // backlight (PWM)
// Piezo switch: pulses the pin to GND. Same pin as the on-board KEY button, which
// works as a stand-in on the bench. BOOT (GPIO0) is left for flashing only.
#define PIN_TAP_SWITCH    14

// ---------------- Piezo switch taps -------------------------
#define TAP_QUIET_MS      40    // line must be still this long before a new pulse counts
#define TAP_GAP_MS        450   // this long after the last pulse ends the gesture
#define TAP_MAX           4     // more taps than this are ignored

// ---------------- RS-485 link (MAX3485 module) -------------
// The dash only listens. MAX3485 RO -> GPIO18, DE and /RE (EN) -> GND.
// On the module fitted here, RO is the pin labelled TXD.
// GPIO17 isn't wired to the module: it's only used by the SIM-LOOP jumper.
#define PIN_LINK_RX       18
#define PIN_LINK_TX       17
#define LINK_BAUD         115200
#define LINK_TIMEOUT_MS   500   // no valid packet for this long -> "NO LINK"

// ---------------- Battery voltage --------------------------
// Measured by the CAN node's ADC and sent in the telemetry frame (vbat_mV),
// so the divider and its calibration live in the CAN node project.
// Colours: green = good, amber = warning, red = bad.
#define VBAT_LOW_WARN      12.0f   // red: engine running and below this = charging fault
#define VBAT_HIGH_WARN     15.0f   // red: regulator fault
#define VBAT_FLAT_WARN     11.8f   // amber: below this = battery getting flat
// Low-voltage warnings are suppressed for this long once the engine starts
// turning over (starter sag, regulator coming up). Over-voltage still shows.
#define VBAT_START_GRACE_MS 5000

// ---------------- Display behaviour ------------------------
#define RPM_MAX           9000     // full scale of the RPM bar
#define RPM_CRUISE_LOW    3500     // dark green below, bright green cruising band from here...
#define RPM_AMBER         6500     // ...up to here, where the bar turns amber
#define SHIFT_RPM         7500     // bar + screen flash red (shift light)
// Lugging (shift-down) alert: high throttle at low rpm in gear 2 or higher,
// held for LUG_HOLD_MS. Flashes the tacho blue like the red shift-up light.
#define LUG_RPM           3000     // below this...
#define LUG_THROTTLE_PCT  60       // ...with at least this much throttle
#define LUG_MIN_GEAR      2        // can't shift down from 1st
#define LUG_HOLD_MS       400      // condition must last this long (ignores blips)
#define COOLANT_WARN_C    105.0f   // coolant red (bad) at or above this
#define COOLANT_HOT_C     100.0f   // coolant amber (warning) at or above this
#define COOLANT_COLD_C    60.0f    // coolant blue below this, green up to COOLANT_HOT_C

// Road speed from the wheel-speed words (0x12D on the 2023 701, unconfirmed). KTMComputer measured a 2020
// 690 Enduro R (same platform) at ~27.4 front counts per mph = ~17.0 counts
// per km/h, +/-5 %. Check against GPS on your 701 and adjust.
#define WHEEL_KMH_PER_COUNT 0.0587f
#define SPEED_FROM_FRONT    1      // 1 = front wheel (rear spins/locks), 0 = rear
// Road speed comes from GPS whenever there's a fix. Wheel speed is the backup.
#define GPS_SPEED_MIN_KMH   2.0f   // below this GPS speed is just noise: treated as stopped

// ---------------- Fuel estimate (no fuel sensor) -----------
// Fuel flow modelled as  k x rpm/1000 x (idle load + throttle/255), with k
// learned at FULL fill-ups where the pumped litres are entered.
#define FUEL_TANK_L          13.0f    // 701 Enduro tank - CHECK your owner's manual
#define FUEL_DEFAULT_L100    5.0f     // range estimate until your own average is known
#define FUEL_HIST_MIN_KM     30.0f    // a tank must cover this far to update your average
#define FUEL_HIST_RATE       0.3f     // how far each tank moves your average (0..1)
#define FUEL_RANGE_BLEND_KM  50.0f    // range moves from your average to this tank's over this distance
// Closed throttle above idle while moving: assumed fuel cut on overrun, so no fuel is
// counted. Cold idle was seen up to about 1800 rpm, so this sits above that.
#define FUEL_CUT_RPM         2200
#define FUEL_CUT_THROTTLE    3        // raw 0..255: below this counts as closed
#define FUEL_CUT_MIN_KMH     5.0f
#define FUEL_IDLE_LOAD       0.10f    // engine load at closed throttle (idle fuel)
// Default k: ~5 L/100 km cruising at 100 km/h (5000 rpm, ~32 % throttle)
// -> 5 L/h / (5 x 0.42 x 3600) units/h = 6.6e-4 L per unit
#define FUEL_K_DEFAULT       0.00066f
#define FUEL_LEARN_RATE      0.5f     // how far each full fill moves k (0..1)
#define FUEL_LEARN_MIN_L     3.0f     // ignore learning when less than this was used
#define FUEL_AVG_MIN_KM      5.0f     // show average L/100 after this many km
#define FUEL_LOW_KM          30.0f    // range warning (red) below this
#define FUEL_LOW_AMBER_KM    60.0f    // range shown amber below this
#define FUEL_SAVE_EVERY_MS   30000    // NVS write interval while riding
#define FUEL_EDIT_STEP_L     0.5f     // litres per tap in the fill-up editor
#define FUEL_EDIT_MAX_KMH    3.0f     // fill-up editor only opens when stopped
#define FUEL_EDIT_TIMEOUT_MS 30000    // editor closes itself after 30 s idle

// ---------------- Trip page --------------------------------
#define TRIP_MOVING_KMH       3.0f     // counts as riding time above this
#define TRIP_SAVE_EVERY_MS    30000

// ---------------- Tyre pressures (BLE valve caps) ---------
// Recommended: FOBO Bike 2. Also decodes TPMSII, WODHMIEY/ITPMS and SYTPMS caps.
#define TPMS_ENABLE            1
#define TPMS_PSI               1        // 1 = show psi, 0 = bar
// Low-pressure warning thresholds (gauge, bar). Set ~10-15 % below your usual
// road pressures from the owner's manual; lower them when airing down off-road.
#define TPMS_FRONT_LOW_BAR     1.6f
#define TPMS_REAR_LOW_BAR      1.6f
#define TPMS_STALE_S           900      // reading older than this is shown grey, no warning
#define TPMS_SENSOR_BATT_LOW_V 2.7f     // cap battery low (CR1632/CR2032 ~3.0 V new)
#define TPMS_PAIR_TIMEOUT_MS   60000

// ---------------- GPS page -----------------------------------
#define NAV_WAYPOINT_MAX       40       // when full, the oldest is forgotten
#define NAV_TRAIL_MAX          256      // trail points kept. 8 bytes each in RAM and flash
#define NAV_TRAIL_SPACING_M    20.0f    // a new point this far from the last. Doubles when full
#define NAV_TRAIL_SAVE_MS      120000   // trail flash write interval while it's changing
#define NAV_HEADING_MIN_KMH    5.0f     // GPS course is only meaningful above this
#define NAV_ARRIVE_M           30.0f    // this close counts as reaching a waypoint
#define NAV_MENU_TIMEOUT_MS    30000
#define NAV_ETE_MIN_KMH        3.0f     // below this, time to go is not shown
#define NAV_TOAST_MS           2000
#define NAV_MAP_RECENT_POINTS  12       // map zoom fits this much of the latest trail

// ---------------- Battery health ----------------------------
#define BATT_CRANK_WEAK_V      9.6f     // lowest voltage while cranking: below = weak
#define BATT_CRANK_BAD_V       9.0f     // below = replace soon
#define BATT_REST_WEAK_V       12.2f    // key on, engine off, before starting

// ---------------- Key left on --------------------------------
#define KEYON_WARN_S           180      // key on, engine off, stopped this long -> warning
#define ALERT_SNOOZE_MS        300000   // a dismissed warning comes back after 5 min

// ---------------- Night dimming (needs the clock set) -------
#define NIGHT_DIM              1
#define NIGHT_START_MIN        (19 * 60)       // 19:00
#define NIGHT_END_MIN          (6 * 60 + 30)   // 06:30
#define NIGHT_LEVEL            40              // backlight 0..255 at night

// ---------------- Clock page --------------------------------
// Always 24-hour HH:MM, set from GPS. The time zone is set in the CAN node.

#define BACKLIGHT_LEVELS  { 40, 120, 255 }   // cycled with 3 taps
#define BACKLIGHT_DEFAULT 2                   // index into BACKLIGHT_LEVELS

#define UI_FRAME_MS       33       // ~30 fps redraw

// Middle panel: clock below, coolant / battery alternating above every
// VALUE_CYCLE_MS. If either is critical, both show and the clock is hidden.
#define VALUE_CYCLE_MS    15000

// Tacho bar response: time constants in ms for the bar to follow RPM.
// Rising is fast so it feels instant, falling slightly softer to look smooth.
#define TACHO_RISE_MS     40
#define TACHO_FALL_MS     90
#define TACHO_PEAK_HOLD_MS 1500    // blue peak marker hold time (0 = off)

// ---------------- Simulator (debug mode) -------------------
// SIM_MODE_DEFAULT is set per build environment in platformio.ini:
//   0 = live (real RS-485 link), 1 = internal simulator, 2 = UART loopback
#ifndef SIM_MODE_DEFAULT
#define SIM_MODE_DEFAULT  1
#endif
#define SIM_FRAME_MS          20      // 50 frames/s, same rate the CAN node will use
#define SIM_LOOP_MS           70000   // length of the scripted ride before it repeats
#define SIM_FAULT_CRC_EVERY   250     // corrupt 1 frame in N (0 = off)
#define SIM_FAULT_DROP_EVERY  400     // drop 1 frame in N (0 = off)
