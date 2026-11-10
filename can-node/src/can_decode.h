// can_decode.h - turns bike CAN frames into a TelemetryPayload.
//
// No hardware or Arduino calls in here. Time is passed in, in milliseconds.
#pragma once
#include <stdint.h>
#include "telemetry_packet.h"

class CanDecoder {
public:
    // Feed one standard CAN frame. Unknown IDs still count as bus traffic.
    void feed(uint32_t id, uint8_t dlc, const uint8_t *data, uint32_t nowMs);

    // Fills in everything except seq and vbat_mV, which belong to the sender.
    void fill(TelemetryPayload &p, uint32_t nowMs) const;

    bool busAlive(uint32_t nowMs) const;

    // True when every ID the dash needs is arriving.
    bool allSignalsFresh(uint32_t nowMs) const;

private:
    struct Age {
        bool     seen = false;
        uint32_t ms   = 0;
        void touch(uint32_t now) { seen = true; ms = now; }
        // Signed, so a frame stamped just after 'now' still counts as fresh.
        bool fresh(uint32_t now, uint32_t limit) const {
            return seen && (int32_t)(now - ms) < (int32_t)limit;
        }
    };

    Age any_, id120_, id128_, id129_, id12D_, id540_;

    uint16_t rpm_ = 0;
    uint8_t  throttle_ = 0;
    bool     killRun_ = false, map2_ = false;
    uint8_t  gear_ = TELEM_GEAR_UNKNOWN;
    bool     clutchIn_ = false;
    bool     throttleOpen_ = false;
    uint16_t wheelFront_ = 0, wheelRear_ = 0;
    bool     standUp_ = false;
    int16_t  coolant_dC_ = TELEM_COOLANT_UNKNOWN;
};
