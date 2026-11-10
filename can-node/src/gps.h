// gps.h - reads a NEO-6M GPS (GY-GPS6MV2) for the dash clock and the SD log.
#pragma once
#include <Arduino.h>
#include "sdlog.h"

class Gps {
public:
    void begin();

    // Call often. Never blocks. Logs each position sentence to the SD card.
    void poll(SdLog &log);

    // True when the time is valid, from a fix in the last few seconds.
    bool hasTime() const;
    uint8_t localHour() const { return hour_; }
    uint8_t localMinute() const { return minute_; }

    const char *statusName() const;
    uint8_t satellites() const { return sats_; }
    float kmh() const { return kmh_; }

    int32_t latE7() const { return latE7_; }
    int32_t lonE7() const { return lonE7_; }
    float courseDeg() const { return courseDeg_; }
    uint16_t localDay() const { return day_; }   // days since 2000-01-01
    int16_t altitudeM() const { return altM_; }
    uint8_t hdopX10() const { return hdopX10_; }

private:
    char     line_[100];
    size_t   len_ = 0;
    uint32_t sentences_ = 0;
    uint32_t lastFixMs_ = 0;
    bool     everFixed_ = false;
    uint8_t  hour_ = 0, minute_ = 0, sats_ = 0;
    float    kmh_ = 0;
    float    courseDeg_ = 0;
    uint16_t day_ = 0;
    int16_t  altM_ = 0;
    uint8_t  hdopX10_ = 0;
    int32_t  latE7_ = 0, lonE7_ = 0;

    void handleLine(SdLog &log);
    void parseRmc();
    void parseGga();
};
