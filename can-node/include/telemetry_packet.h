// telemetry_packet.h
//
// Wire format between the under-seat CAN node and the dash, sent over RS-485.
// KEEP THIS FILE IDENTICAL IN BOTH PROJECTS (copy it into the CAN node project).
//
// Framing: [0xA5][0x5A][payload: TelemetryPayload][crc16 lo][crc16 hi]
// CRC is CRC-16/CCITT-FALSE over the payload bytes only.
// All multi-byte fields are little-endian (native on ESP32).
// Sent ~25-50 times per second by the CAN node.

#pragma once
#include <stdint.h>
#include <stddef.h>

#define TELEM_SYNC0 0xA5
#define TELEM_SYNC1 0x5A
#define TELEM_VERSION 5   // v2: added vbat_mV, v3: added clock_hour/clock_min, v4: added GPS, v5: elevation, accuracy

// Gear value meaning "unknown / between gears"
#define TELEM_GEAR_UNKNOWN 0xFF
// Coolant value meaning "not received yet"
#define TELEM_COOLANT_UNKNOWN ((int16_t)-32768)
// Battery value meaning "not measured"
#define TELEM_VBAT_UNKNOWN 0
// Clock value meaning "bike's clock not seen on the CAN bus"
#define TELEM_CLOCK_UNKNOWN 0xFF

// flags bitfield
#define TELEM_FLAG_CLUTCH_IN    (1u << 0)  // 0x129 D0 bit3
#define TELEM_FLAG_KILL_RUN     (1u << 1)  // 0x120 D3 bit3 (1 = run)
#define TELEM_FLAG_STAND_UP     (1u << 2)  // 0x540 D3 bit0 (1 = raised), unconfirmed
#define TELEM_FLAG_MAP2         (1u << 3)  // 0x120 D4 bit0 (throttle map)
#define TELEM_FLAG_THROTTLE_OPEN (1u << 4) // 0x128 D2 bit0 (inverted)
#define TELEM_FLAG_CAN_OK       (1u << 7)  // CAN node is seeing bike traffic

// reserved bitfield
#define TELEM_RES_NODE_SIM      (1u << 0)  // CAN node is sending made-up test data, not the bike's

#pragma pack(push, 1)
struct TelemetryPayload {
    uint8_t  version;        // TELEM_VERSION
    uint8_t  seq;            // increments every packet (drop detection)
    uint16_t rpm;            // engine rpm (0x120 D0..D1, big-endian on CAN)
    uint8_t  gear;           // 0 = neutral, 1..6, TELEM_GEAR_UNKNOWN
    uint8_t  throttle;       // 0..255 raw (0x120 D2)
    int16_t  coolant_dC;     // coolant temp in 0.1 degC (0x540 D5..D6)
    uint16_t wheel_front;    // raw (0x12D D0..D1, unconfirmed) - scaling to km/h TBD
    uint16_t wheel_rear;     // raw (0x12D D2..D3, unconfirmed) - scaling to km/h TBD
    uint16_t vbat_mV;        // battery in mV, CAN node's ADC on ACC2 12V (TELEM_VBAT_UNKNOWN = none)
    uint8_t  flags;          // TELEM_FLAG_*
    uint8_t  reserved;       // TELEM_RES_*, other bits 0
    uint8_t  clock_hour;     // bike dash clock 0..23, TELEM_CLOCK_UNKNOWN = not found on CAN
    uint8_t  clock_min;      // 0..59, from GPS (the 2023 701 doesn't send it on CAN)
    int32_t  gps_lat_e7;     // latitude in degrees x 10^7, north positive
    int32_t  gps_lon_e7;     // longitude in degrees x 10^7, east positive
    uint16_t gps_speed_dkmh; // speed over ground in 0.1 km/h
    uint16_t gps_course_dd;  // course over ground in 0.1 deg, 0 = north. Unreliable when slow
    uint8_t  gps_sats;       // satellites in use
    uint8_t  gps_fix;        // 1 = position, speed and course are valid
    uint16_t gps_day;        // local date, days since 2000-01-01. 0 = not known
    int16_t  gps_alt_m;      // height above sea level in metres
    uint8_t  gps_hdop_dx10;  // horizontal dilution of precision x 10, lower is better
    uint8_t  gps_reserved;   // keep 0
};
#pragma pack(pop)

static constexpr size_t TELEM_PAYLOAD_LEN = sizeof(TelemetryPayload);
static constexpr size_t TELEM_FRAME_LEN   = 2 + TELEM_PAYLOAD_LEN + 2;

static_assert(sizeof(TelemetryPayload) == 38, "TelemetryPayload layout changed - update both projects");

// CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF)
static inline uint16_t telem_crc16(const uint8_t *data, size_t len) {
    uint16_t crc = 0xFFFF;
    for (size_t i = 0; i < len; i++) {
        crc ^= (uint16_t)data[i] << 8;
        for (int b = 0; b < 8; b++) {
            crc = (crc & 0x8000) ? (uint16_t)((crc << 1) ^ 0x1021) : (uint16_t)(crc << 1);
        }
    }
    return crc;
}
