#include "battery.h"
#include "config.h"
#include <Preferences.h>

static Preferences bPrefs;

void BatteryMonitor::begin() {
    bPrefs.begin("batt", false);
    histN_ = bPrefs.getUChar("n", 0);
    if (histN_ > 5) histN_ = 0;
    bPrefs.getBytes("hist", hist_, sizeof(hist_));
    lastRest_ = bPrefs.getFloat("rest", 0);
    lastMin_ = histN_ ? hist_[0] : 0;
}

void BatteryMonitor::update(bool vbatOk, float vbat, uint16_t rpm) {
    if (!vbatOk) return;
    const uint32_t now = millis();
    switch (st_) {
    case St::Off:
        // resting voltage: smooth while key on / engine stopped
        restV_ = (restV_ == 0) ? vbat : restV_ + (vbat - restV_) * 0.2f;
        if (rpm > 0) { st_ = St::Crank; minV_ = vbat; runSinceMs_ = 0; }
        break;
    case St::Crank:
        if (vbat < minV_) minV_ = vbat;
        if (rpm == 0) {                       // gave up cranking: count it, back to Off
            finishStart();
            st_ = St::Off;
        } else if (rpm > 1000) {
            if (runSinceMs_ == 0) runSinceMs_ = now;
            if (now - runSinceMs_ > 1000) { finishStart(); st_ = St::Running; }
        } else {
            runSinceMs_ = 0;
        }
        break;
    case St::Running:
        if (rpm == 0) { st_ = St::Off; restV_ = 0; }
        break;
    }
}

void BatteryMonitor::finishStart() {
    if (minV_ > 20 || minV_ < 3) return;      // no real data
    lastRest_ = restV_;
    lastMin_ = minV_;
    for (int i = 4; i > 0; i--) hist_[i] = hist_[i - 1];
    hist_[0] = minV_;
    if (histN_ < 5) histN_++;
    bPrefs.putUChar("n", histN_);
    bPrefs.putBytes("hist", hist_, sizeof(hist_));
    bPrefs.putFloat("rest", lastRest_);
    newResult_ = true;
    LOG_PRINTF("[battery] start: rest %.2f V, crank min %.2f V\n", lastRest_, lastMin_);
}

BatteryView BatteryMonitor::view() {
    BatteryView v;
    v.restV = lastRest_;
    v.crankMinV = lastMin_;
    v.historyN = histN_;
    for (int i = 0; i < 5; i++) v.history[i] = hist_[i];
    if (histN_ == 0)                                         v.health = BattHealth::Unknown;
    else if (lastMin_ < BATT_CRANK_BAD_V)                    v.health = BattHealth::Bad;
    else if (lastMin_ < BATT_CRANK_WEAK_V || lastRest_ < BATT_REST_WEAK_V) v.health = BattHealth::Weak;
    else                                                     v.health = BattHealth::Good;
    v.newResult = newResult_;
    newResult_ = false;
    return v;
}
