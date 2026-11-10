#include "can_decode.h"
#include "config.h"

// Signal map from the 2023 701 captures (logs/LOG00014.CSV and LOG00017.CSV). It differs from the
// 2020 KTM 690 in blalor/ktm-can. README lists what is confirmed and what isn't.

static inline uint16_t be16(const uint8_t *p) {
    return (uint16_t)((p[0] << 8) | p[1]);
}

void CanDecoder::feed(uint32_t id, uint8_t dlc, const uint8_t *d, uint32_t now) {
    any_.touch(now);

    switch (id) {
    case 0x120:
        if (dlc < 5) return;
        rpm_      = be16(&d[0]);
        throttle_ = d[2];
        killRun_  = d[3] & 0x08;            // bit 4 (0x10) is the starter button here
        map2_     = d[4] & 0x01;            // follows the map button
        id120_.touch(now);
        break;

    case 0x128:
        if (dlc < 3) return;
        throttleOpen_ = !(d[2] & 0x01);     // the bit is set when the throttle is closed
        id128_.touch(now);
        break;

    case 0x129:
        if (dlc < 1) return;
        {
            const uint8_t g = d[0] >> 4;
            gear_ = (g <= 6) ? g : TELEM_GEAR_UNKNOWN;
        }
        clutchIn_ = d[0] & 0x08;
        id129_.touch(now);
        break;

    case 0x12D:
        // Front confirmed by hand-spinning. Rear is unconfirmed: the rear wheel never turned.
        // The km/h scale lives in the dash and still needs a ride with GPS.
        if (dlc < 4) return;
        wheelFront_ = be16(&d[0]);
        wheelRear_  = be16(&d[2]);
        id12D_.touch(now);
        break;

    case 0x540:
        if (dlc < 7) return;
        standUp_    = d[3] & 0x01;          // can't be tested: this bike has a stand switch bypass
        coolant_dC_ = (int16_t)be16(&d[5]); // already 0.1 degC, no offset
        id540_.touch(now);
        break;

    default:
        break;
    }
}

bool CanDecoder::busAlive(uint32_t now) const {
    return any_.fresh(now, CAN_OK_TIMEOUT_MS);
}

bool CanDecoder::allSignalsFresh(uint32_t now) const {
    return id120_.fresh(now, CAN_FAST_TIMEOUT_MS)
        && id128_.fresh(now, CAN_FAST_TIMEOUT_MS)
        && id129_.fresh(now, CAN_FAST_TIMEOUT_MS)
        && id12D_.fresh(now, CAN_FAST_TIMEOUT_MS)
        && id540_.fresh(now, CAN_SLOW_TIMEOUT_MS);
}

void CanDecoder::fill(TelemetryPayload &p, uint32_t now) const {
    const bool f120 = id120_.fresh(now, CAN_FAST_TIMEOUT_MS);
    const bool f128 = id128_.fresh(now, CAN_FAST_TIMEOUT_MS);
    const bool f129 = id129_.fresh(now, CAN_FAST_TIMEOUT_MS);
    const bool f12D = id12D_.fresh(now, CAN_FAST_TIMEOUT_MS);
    const bool f540 = id540_.fresh(now, CAN_SLOW_TIMEOUT_MS);

    p.version     = TELEM_VERSION;
    p.rpm         = f120 ? rpm_ : 0;
    p.throttle    = f120 ? throttle_ : 0;
    p.gear        = f129 ? gear_ : TELEM_GEAR_UNKNOWN;
    p.coolant_dC  = f540 ? coolant_dC_ : TELEM_COOLANT_UNKNOWN;
    p.wheel_front = f12D ? wheelFront_ : 0;
    p.wheel_rear  = f12D ? wheelRear_ : 0;

    uint8_t flags = 0;
    if (f129 && clutchIn_)     flags |= TELEM_FLAG_CLUTCH_IN;
    if (f120 && killRun_)      flags |= TELEM_FLAG_KILL_RUN;
    if (f540 && standUp_)      flags |= TELEM_FLAG_STAND_UP;
    if (f120 && map2_)         flags |= TELEM_FLAG_MAP2;
    if (f128 && throttleOpen_) flags |= TELEM_FLAG_THROTTLE_OPEN;
    if (busAlive(now))         flags |= TELEM_FLAG_CAN_OK;
    p.flags = flags;

    p.reserved = 0;

    // The capture showed no bike clock on this bus: the dash's manual clock is used.
    p.clock_hour = TELEM_CLOCK_UNKNOWN;
    p.clock_min  = TELEM_CLOCK_UNKNOWN;
}
