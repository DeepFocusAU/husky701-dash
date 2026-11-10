// battery.h - battery health from start-up behaviour (CAN node's vbat).
//
// Before each start it records the resting voltage (key on, engine off);
// during cranking it records the lowest voltage. A healthy battery stays
// above ~9.6 V while cranking; a weak one sags further, and the trend over
// the last few starts shows it ageing before it leaves you stranded.
#pragma once
#include <Arduino.h>

enum class BattHealth : uint8_t { Unknown, Good, Weak, Bad };

struct BatteryView {
    float      restV      = 0;      // last resting voltage before a start
    float      crankMinV  = 0;      // last cranking minimum
    float      history[5] = {0};    // previous cranking minima, newest first
    uint8_t    historyN   = 0;
    BattHealth health     = BattHealth::Unknown;
    bool       newResult  = false;  // true once, right after a start is measured
};

class BatteryMonitor {
public:
    void begin();
    // call ~10 Hz with the latest data
    void update(bool vbatOk, float vbat, uint16_t rpm);
    BatteryView view();             // clears newResult

private:
    enum class St : uint8_t { Off, Crank, Running };
    St       st_ = St::Off;
    float    restV_ = 0, minV_ = 99, lastRest_ = 0, lastMin_ = 0;
    float    hist_[5] = {0};
    uint8_t  histN_ = 0;
    uint32_t runSinceMs_ = 0;
    bool     newResult_ = false;
    void finishStart();
};
