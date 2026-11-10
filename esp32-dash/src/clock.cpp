#include "clock.h"
#include "config.h"
#include <time.h>
#include <sys/time.h>

void ClockModel::begin() {
    // Local time is kept directly in the system clock (no timezone maths here:
    // the CAN node has already applied the time zone).
    setenv("TZ", "UTC0", 1);
    tzset();
    valid_ = false;
}

void ClockModel::setTime(uint8_t hour, uint8_t minute) {
    // Any fixed date works; only the time of day is shown.
    struct tm t = {};
    t.tm_year = 2026 - 1900;
    t.tm_mon  = 0;
    t.tm_mday = 1;
    t.tm_hour = hour;
    t.tm_min  = minute;
    const time_t epoch = mktime(&t);
    struct timeval tv = {epoch, 0};
    settimeofday(&tv, nullptr);
    valid_ = true;
}

void ClockModel::syncFromGps(uint8_t hour, uint8_t minute) {
    if (hour > 23 || minute > 59) return;
    const int m = hour * 60 + minute;
    if (m == lastMinute_ && valid_) return;
    lastMinute_ = m;
    // First sight takes the minute, with seconds unknown. Each change of minute
    // is exactly :00, so that re-locks it.
    setTime(hour, minute);
}

ClockView ClockModel::view() const {
    ClockView c;
    c.valid = valid_;
    if (valid_) {
        const time_t t = time(nullptr);
        struct tm tmv;
        localtime_r(&t, &tmv);
        c.hour = tmv.tm_hour;
        c.minute = tmv.tm_min;
        c.second = tmv.tm_sec;
    }
    return c;
}
