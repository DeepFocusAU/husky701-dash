// ble_link.h - sends telemetry to the Android dash (Samsung Note 10) over Bluetooth LE.
//
// Runs alongside the RS-485 link, so the ESP32 dash and the phone can both be used.
//
// GATT layout (the Android app uses the same UUIDs, see BleTelemetry.kt):
//   Service   BLE_SERVICE_UUID
//   Frame     BLE_FRAME_UUID  notify: one whole wire frame, exactly as sent on RS-485
//                             ([A5][5A][payload][crc lo][crc hi], 42 bytes). Needs MTU >= 45;
//                             the app asks for 247.
//   Taps      BLE_TAPS_UUID   notify + read: [gesture seq][tap count]. The seq goes up by one
//                             for every finished gesture, so a repeated count is still seen.
//   Mode      BLE_MODE_UUID   write + read: [1] = SIM (made-up test data), [0] = LIVE (the bike).
//                             Back to LIVE when the phone disconnects, and always LIVE at power-up.
#pragma once
#include <Arduino.h>

class BleLink {
public:
    void begin();

    // Sends one wire frame to the phone, if one is connected and subscribed.
    void sendFrame(const uint8_t *frame, size_t len);

    // Tells the phone a tap gesture finished with this many taps (1..TAP_MAX).
    void sendTaps(uint8_t taps);

    bool connected() const;

    // The phone asked for SIM (made-up data) and is still connected.
    bool simRequested() const;
    uint32_t framesSent() const { return framesSent_; }

private:
    uint32_t framesSent_ = 0;
    uint8_t  tapSeq_ = 0;
};
