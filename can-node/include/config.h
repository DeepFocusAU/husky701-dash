// config.h - CAN node settings.
#pragma once

// ---------------- T-CAN485 board pins -----------------------
// From LilyGO's pin map and lib/Mylibrary/pin_config.h.
#define PIN_BOOST_EN       16   // ME2107 boost: 5 V for the MAX13487E. HIGH = on
#define PIN_CAN_TX         27   // to the SN65HVD231 D pin. Held recessive, see can_bus.cpp
#define PIN_CAN_RX         26   // from the SN65HVD231 R pin
#define PIN_CAN_SE         23   // SN65HVD231 Rs pin. LOW = normal, HIGH = SLEEP (receiver off!)
#define PIN_RS485_TX       22
#define PIN_RS485_RX       21
#define PIN_RS485_RE       17   // MAX13487E /RE. HIGH = AutoDirection controls the receiver
#define PIN_RS485_SHDN     19   // MAX13487E /SHDN. HIGH = running
#define PIN_LED            4    // on-board WS2812

// The CAN controller's TX signal goes to this pin instead of the transceiver,
// so the firmware has no path to drive the bike's bus. Leave IO25 unconnected.
#define PIN_TWAI_TX_PARKED 25

// ---------------- SD card log --------------------------------
#define PIN_SD_MISO        2
#define PIN_SD_MOSI        15
#define PIN_SD_SCLK        14
#define PIN_SD_CS          13
#define SD_SPI_HZ          20000000
#define SD_BUFFER_BYTES    32768    // over a second of bus traffic, to ride out card stalls
#define SD_FLUSH_MS        2000     // at most this much log is lost when the key goes off
#define SD_MOUNT_TRIES     6        // a reset mid-write can leave the card busy for a while
#define SD_MOUNT_RETRY_MS  500

// ---------------- GPS (GY-GPS6MV2, NEO-6M) -------------------
#define PIN_GPS_RX         35       // from the module's TX. Input-only pin, on the header
#define GPS_BAUD           9600
#define GPS_FRESH_MS       5000     // time is sent to the dash until this long after the last fix
// Local time, with daylight saving: POSIX format. This is Australian Eastern (NSW, VIC,
// ACT, TAS). Queensland would be "AEST-10".
#define GPS_TIME_ZONE      "AEST-10AEDT,M10.1.0,M4.1.0/3"

// ---------------- RS-485 link to the dash --------------------
#define LINK_BAUD          115200   // must match LINK_BAUD in the dash
#define LINK_FRAME_MS      20       // 50 frames/s, the rate the dash simulator uses

// ---------------- CAN signal timeouts ------------------------
// A signal not refreshed for this long is reported as unknown or zero, so the
// dash can never freeze on an old value. Each is several send intervals.
#define CAN_FAST_TIMEOUT_MS 300     // 0x120, 0x128, 0x129 (20 ms), 0x12D (10 ms)
#define CAN_SLOW_TIMEOUT_MS 1000    // 0x540 (100 ms)
#define CAN_OK_TIMEOUT_MS   500     // no frame of any ID this long clears TELEM_FLAG_CAN_OK

// ---------------- Battery sense ------------------------------
// ACC2 12 V -> VBAT_R_TOP -> IO34 -> VBAT_R_BOTTOM -> GND, 100 nF across the bottom
// resistor. The ESP32 ADC is only reliable up to ~2.45 V, and 100k/18k puts 15 V
// at 2.29 V (16 V at 2.44 V). Compare with a multimeter and adjust VBAT_CAL.
#define PIN_VBAT           34
#define VBAT_R_TOP         100.0f   // kOhm
#define VBAT_R_BOTTOM      18.0f    // kOhm (as fitted; was 15k)
#define VBAT_CAL           1.000f
#define VBAT_MIN_VALID_MV  3000     // below this, assume the divider isn't connected
#define VBAT_SAMPLES       8        // averaged per frame, short enough to keep the cranking dip

// ---------------- Bluetooth LE link to the Android dash ------
// Runs alongside RS-485. The UUIDs must match BleTelemetry.kt in the Android app (android/).
#define BLE_ENABLE         1
#define BLE_DEVICE_NAME    "Husky701"
#define BLE_SERVICE_UUID   "7a1e0001-4b8f-4c1e-9a70-701d45a00001"
#define BLE_FRAME_UUID     "7a1e0002-4b8f-4c1e-9a70-701d45a00001"
#define BLE_TAPS_UUID      "7a1e0003-4b8f-4c1e-9a70-701d45a00001"
#define BLE_MODE_UUID      "7a1e0004-4b8f-4c1e-9a70-701d45a00001"   // phone writes 1 = SIM, 0 = LIVE
#define BLE_FRAME_DIV      2        // send every 2nd RS-485 frame: 25 frames/s to the phone

// ---------------- Piezo switch (for the Android dash) -------
// Pulses the pin to GND, same as on the ESP32 dash. IO32 is a free header pin
// with an internal pull-up. Max 3.3 V: a +12 V switch output needs an optocoupler.
#define PIN_TAP_SWITCH     32
#define TAP_QUIET_MS       40       // line must be still this long before a new pulse counts
#define TAP_GAP_MS         450      // this long after the last pulse ends the gesture
#define TAP_MAX            4        // more taps than this are ignored
