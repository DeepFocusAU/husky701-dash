#include "gps.h"
#include "config.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

// Splits an NMEA sentence on commas in place. Empty fields stay as empty strings.
// Returns the number of fields found.
static int splitFields(char *s, char *fields[], int maxFields) {
    int n = 0;
    fields[n++] = s;
    while (*s && n < maxFields) {
        if (*s == ',') {
            *s = 0;
            fields[n++] = s + 1;
        }
        s++;
    }
    return n;
}

static int hexValue(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

// True if the line is "$...*hh" with a correct checksum.
static bool checksumOk(const char *s) {
    if (*s != '$') return false;
    uint8_t sum = 0;
    const char *p = s + 1;
    while (*p && *p != '*') sum ^= (uint8_t)*p++;
    if (*p != '*') return false;
    const int hi = hexValue(p[1]), lo = hexValue(p[2]);
    return hi >= 0 && lo >= 0 && sum == (uint8_t)((hi << 4) | lo);
}

// NMEA position "ddmm.mmmm" or "dddmm.mmmm" to degrees x 10^7, negative for S or W.
static int32_t toE7(const char *value, const char *hemisphere) {
    const double raw = atof(value);
    const int degrees = (int)(raw / 100.0);
    const double decimal = degrees + (raw - degrees * 100.0) / 60.0;
    const int32_t e7 = (int32_t)llround(decimal * 1e7);
    return (*hemisphere == 'S' || *hemisphere == 'W') ? -e7 : e7;
}

// Seconds since 1970 for a UTC date and time. No library call does this without
// also changing the time zone.
static time_t utcToEpoch(int year, int month, int day, int hour, int minute, int second) {
    year -= month <= 2;
    const int era = year / 400;
    const int yoe = year - era * 400;
    const int doy = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1;
    const int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    const int32_t days = era * 146097 + doe - 719468;
    return (time_t)days * 86400 + hour * 3600 + minute * 60 + second;
}

void Gps::begin() {
    setenv("TZ", GPS_TIME_ZONE, 1);
    tzset();
    Serial2.begin(GPS_BAUD, SERIAL_8N1, PIN_GPS_RX, -1);    // nothing is sent to the GPS
}

bool Gps::hasTime() const {
    return everFixed_ && (millis() - lastFixMs_) < GPS_FRESH_MS;
}

const char *Gps::statusName() const {
    if (sentences_ == 0) return "no data";
    return hasTime() ? "fix" : "searching";
}

void Gps::poll(SdLog &log) {
    while (Serial2.available() > 0) {
        const char c = (char)Serial2.read();
        if (c == '\r') continue;
        if (c == '\n') {
            line_[len_] = 0;
            handleLine(log);
            len_ = 0;
        } else if (len_ < sizeof(line_) - 1) {
            line_[len_++] = c;
        } else {
            len_ = 0;   // too long to be a sentence we want: drop it and resync
        }
    }
}

void Gps::handleLine(SdLog &log) {
    if (len_ < 7 || !checksumOk(line_)) return;
    sentences_++;

    // Talker ID is 2 characters: $GPRMC, $GNRMC, and so on.
    const bool isRmc = strncmp(line_ + 3, "RMC", 3) == 0;
    const bool isGga = strncmp(line_ + 3, "GGA", 3) == 0;
    if (isRmc) log.logText("GPS", line_, millis());     // before parsing, which splits the line
    if (isRmc) parseRmc();
    if (isGga) parseGga();
}

void Gps::parseRmc() {
    // $GPRMC,hhmmss.ss,A,lat,N,lon,E,knots,course,ddmmyy,...
    char *f[12];
    const int n = splitFields(line_, f, 12);
    if (n < 10 || f[2][0] != 'A') return;       // 'A' = valid fix
    if (strlen(f[1]) < 6 || strlen(f[9]) != 6) return;
    if (strlen(f[3]) < 4 || strlen(f[5]) < 5) return;

    const int hh = (f[1][0] - '0') * 10 + (f[1][1] - '0');
    const int mi = (f[1][2] - '0') * 10 + (f[1][3] - '0');
    const int ss = (f[1][4] - '0') * 10 + (f[1][5] - '0');
    const int dd = (f[9][0] - '0') * 10 + (f[9][1] - '0');
    const int mo = (f[9][2] - '0') * 10 + (f[9][3] - '0');
    const int yy = (f[9][4] - '0') * 10 + (f[9][5] - '0');
    if (mo < 1 || mo > 12 || dd < 1 || dd > 31 || hh > 23 || mi > 59) return;

    const time_t utc = utcToEpoch(2000 + yy, mo, dd, hh, mi, ss);
    struct tm local;
    localtime_r(&utc, &local);      // applies GPS_TIME_ZONE, including daylight saving
    hour_ = (uint8_t)local.tm_hour;
    minute_ = (uint8_t)local.tm_min;
    day_ = (uint16_t)(utcToEpoch(local.tm_year + 1900, local.tm_mon + 1, local.tm_mday, 0, 0, 0) / 86400 - 10957);

    latE7_ = toE7(f[3], f[4]);
    lonE7_ = toE7(f[5], f[6]);
    kmh_ = (float)atof(f[7]) * 1.852f;
    if (f[8][0]) courseDeg_ = (float)atof(f[8]);    // blank when stopped: keep the last one
    lastFixMs_ = millis();
    everFixed_ = true;
}

void Gps::parseGga() {
    // $GPGGA,time,lat,N,lon,E,quality,satellites,hdop,altitude,...
    char *f[11];
    const int n = splitFields(line_, f, 11);
    if (n >= 8) sats_ = (uint8_t)atoi(f[7]);
    if (n >= 10 && f[8][0] && f[9][0]) {
        const float hdop = (float)atof(f[8]);
        hdopX10_ = hdop >= 25.0f ? 250 : (uint8_t)lroundf(hdop * 10.0f);
        altM_ = (int16_t)lroundf((float)atof(f[9]));
    }
}
