// clock.h - time of day, from the GPS on the CAN node.
//
// The T-Display-S3 has no battery-backed clock and the dash is on switched power,
// so it shows "--:--" after key-on until the GPS has a fix.
#pragma once
#include <Arduino.h>

struct ClockView {
    bool    valid  = false;
    uint8_t hour   = 0;      // 0..23
    uint8_t minute = 0;
    uint8_t second = 0;
};

class ClockModel {
public:
    void begin();

    // Feed the local time from each telemetry frame. The system clock is re-set
    // whenever the minute changes, so it stays within a second of GPS after the
    // first minute change. It keeps running if the fix is lost.
    void syncFromGps(uint8_t hour, uint8_t minute);

    ClockView view() const;

private:
    bool valid_ = false;
    int  lastMinute_ = -1;

    void setTime(uint8_t hour, uint8_t minute);
};
