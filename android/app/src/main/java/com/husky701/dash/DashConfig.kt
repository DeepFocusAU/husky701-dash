// DashConfig.kt - dash settings, the same values as the ESP32 dash's include/config.h.
package com.husky701.dash

import java.util.UUID

object DashConfig {
    // ---- BLE link: must match BLE_* in the CAN node's include/config.h ----
    val BLE_SERVICE: UUID = UUID.fromString("7a1e0001-4b8f-4c1e-9a70-701d45a00001")
    val BLE_FRAME: UUID = UUID.fromString("7a1e0002-4b8f-4c1e-9a70-701d45a00001")
    val BLE_TAPS: UUID = UUID.fromString("7a1e0003-4b8f-4c1e-9a70-701d45a00001")

    val BLE_MODE: UUID = UUID.fromString("7a1e0004-4b8f-4c1e-9a70-701d45a00001")      // write 1 = node SIM, 0 = LIVE
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val LINK_TIMEOUT_MS = 500L        // no valid frame this long -> NO LINK
    const val BLE_SEQ_STEP = 2              // = BLE_FRAME_DIV: the node sends every 2nd frame over BLE

    // ---- taps (on-screen taps use the same timing as the piezo switch) ----
    const val TAP_QUIET_MS = 40L
    const val TAP_GAP_MS = 450L
    const val TAP_MAX = 4

    // ---- tacho ----
    const val RPM_MAX = 9000
    const val RPM_CRUISE_LOW = 3500
    const val RPM_AMBER = 6500
    const val SHIFT_RPM = 7500
    const val LUG_RPM = 3000
    const val LUG_THROTTLE_PCT = 60
    const val LUG_MIN_GEAR = 2
    const val LUG_HOLD_MS = 400L
    const val TACHO_RISE_MS = 40f
    const val TACHO_FALL_MS = 90f
    const val TACHO_PEAK_HOLD_MS = 1500L
    /** Split screen: coolant and battery take turns above the clock, this long each. */
    const val VALUE_CYCLE_MS = 15000L
    /** Split screen: share of the screen height the tacho strip takes (map app above). */
    const val SPLIT_STRIP_FRACTION = 0.20f

    // ---- coolant ----
    const val COOLANT_WARN_C = 105f
    const val COOLANT_HOT_C = 100f
    const val COOLANT_COLD_C = 60f

    // ---- battery ----
    const val VBAT_LOW_WARN = 12.0f
    const val VBAT_HIGH_WARN = 15.0f
    const val VBAT_FLAT_WARN = 11.8f
    const val VBAT_START_GRACE_MS = 5000L

    // ---- road speed ----
    const val WHEEL_KMH_PER_COUNT = 0.0587f
    const val SPEED_FROM_FRONT = true
    const val GPS_SPEED_MIN_KMH = 2.0f

    // ---- screen ----
    val BRIGHTNESS_LEVELS = floatArrayOf(0.15f, 0.5f, 1.0f)   // cycled with 3 taps
    const val BRIGHTNESS_DEFAULT = 2
    const val ALARM_FLASH_MS = 250L
    /** Shift-up / shift-down light: on and off this long each (5 flashes a second). */
    const val SHIFT_FLASH_MS = 100L
    /** what3words (free plan): lookups are by hand only, and never more than this many a day (about 750 a month). */
    const val W3W_DAILY_CAP = 25

    /** Longest waypoint name. */
    const val NAV_LABEL_MAX = 16

    // ---- phone GPS ----
    const val GPS_FIX_MAX_AGE_MS = 3000L    // no new location this long -> no fix

    // ---- fuel estimate (no fuel sensor), same model as the ESP32 dash ----
    const val FUEL_TANK_L = 13.0f           // 701 Enduro tank - CHECK your owner's manual
    const val FUEL_DEFAULT_L100 = 5.0f
    const val FUEL_HIST_MIN_KM = 30f
    const val FUEL_HIST_RATE = 0.3f
    const val FUEL_RANGE_BLEND_KM = 50f
    const val FUEL_CUT_RPM = 2200
    const val FUEL_CUT_THROTTLE = 3
    const val FUEL_CUT_MIN_KMH = 5f
    const val FUEL_IDLE_LOAD = 0.10f
    const val FUEL_K_DEFAULT = 0.00066f
    const val FUEL_LEARN_RATE = 0.5f
    const val FUEL_LEARN_MIN_L = 3f
    const val FUEL_AVG_MIN_KM = 5f
    /** Range warnings, as a share of the full-tank range (the same as the share of the tank left):
     *  steady red below 10 %, flashing red below 5 %, amber below 60 km. */
    const val FUEL_RED_FRACTION = 0.10f
    const val FUEL_FLASH_FRACTION = 0.05f
    const val FUEL_LOW_AMBER_KM = 60f
    const val FUEL_SAVE_EVERY_MS = 30_000L
    const val FUEL_EDIT_STEP_L = 0.5f
    const val FUEL_EDIT_TIMEOUT_MS = 30_000L

    // ---- trip ----
    const val TRIP_MOVING_KMH = 3f
    const val TRIP_SAVE_EVERY_MS = 30_000L

    // ---- tyre pressures (BLE valve caps) ----
    const val TPMS_ENABLE = true
    const val TPMS_PSI = true               // false = bar
    const val TPMS_FRONT_LOW_BAR = 1.6f
    const val TPMS_REAR_LOW_BAR = 1.6f
    const val TPMS_STALE_S = 900L
    const val TPMS_SENSOR_BATT_LOW_V = 2.7f
    const val TPMS_PAIR_TIMEOUT_MS = 60_000L

    // ---- GPS pages ----
    const val NAV_WAYPOINT_MAX = 40
    const val NAV_TRAIL_MAX = 1024          // the phone has room for 4x the ESP32's trail
    const val NAV_TRAIL_SPACING_M = 20f
    const val NAV_TRAIL_SAVE_MS = 120_000L
    const val NAV_HEADING_MIN_KMH = 5f
    const val NAV_ARRIVE_M = 30f
    const val NAV_MENU_TIMEOUT_MS = 30_000L
    const val NAV_ETE_MIN_KMH = 3f
    const val NAV_TOAST_MS = 2000L
    const val NAV_MAP_RECENT_POINTS = 12

    // ---- battery health ----
    const val BATT_CRANK_WEAK_V = 9.6f
    const val BATT_CRANK_BAD_V = 9.0f
    const val BATT_REST_WEAK_V = 12.2f

    // ---- warnings ----
    const val KEYON_WARN_S = 180L
    /** Key-on warning: two short beeps when it shows, repeated this often while it stays up. */
    const val KEYON_BEEP_REPEAT_MS = 30_000L
    const val ALERT_SNOOZE_MS = 300_000L

    // ---- night dimming, from the phone clock ----
    const val NIGHT_DIM = true
    const val NIGHT_START_MIN = 19 * 60
    const val NIGHT_END_MIN = 6 * 60 + 30
    const val NIGHT_LEVEL = 0.16f

    // ---- appliance power ----
    // Bike power off (charger unplugged) this long -> screen off. Back on when power returns.
    const val POWER_OFF_SCREEN_DELAY_MS = 120_000L

    // ---- simulator ----
    const val SIM_FRAME_MS = 20L
    const val SIM_LOOP_MS = 70_000L
    const val SIM_FAULT_CRC_EVERY = 250
    const val SIM_FAULT_DROP_EVERY = 400
}
