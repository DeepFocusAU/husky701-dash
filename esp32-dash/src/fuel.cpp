#include "fuel.h"
#include "config.h"
#include <Preferences.h>
#include <math.h>

static Preferences prefs;
static const char *NS = "fuel";

static float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

void FuelModel::begin() {
    prefs.begin(NS, false);
    k_               = prefs.getFloat("k", FUEL_K_DEFAULT);
    litres_          = prefs.getFloat("litres", FUEL_TANK_L);
    unitsSinceFull_  = prefs.getFloat("uFull", 0);
    addedSinceFull_  = prefs.getFloat("addFull", 0);
    usedSinceFill_   = prefs.getFloat("used", 0);
    kmSinceFill_     = prefs.getFloat("km", 0);
    learnedFills_    = prefs.getUChar("nLearn", 0);
    haveFullRef_     = prefs.getBool("fullRef", false);
    histL100_        = prefs.getFloat("hist", 0);

    // sanity: guard against a corrupt or very old record
    if (!(k_ > FUEL_K_DEFAULT * 0.3f && k_ < FUEL_K_DEFAULT * 3.0f)) k_ = FUEL_K_DEFAULT;
    litres_ = clampf(litres_, 0, FUEL_TANK_L);
    lastMs_ = lastSaveMs_ = millis();
}

void FuelModel::update(bool engineData, uint16_t rpm, uint8_t throttle, float speedKmh) {
    const uint32_t now = millis();
    const float dt = (now - lastMs_) / 1000.0f;
    lastMs_ = now;
    if (dt <= 0 || dt > 2.0f) return;          // first call or a long gap: skip

    float lph = 0;
    if (engineData && rpm > 300) {
        const bool overrun = throttle < FUEL_CUT_THROTTLE && rpm > FUEL_CUT_RPM
                          && speedKmh > FUEL_CUT_MIN_KMH;
        const float load = overrun ? 0.0f : FUEL_IDLE_LOAD + throttle / 255.0f;
        const float units = rpm / 1000.0f * load * dt;
        const float litres = units * k_;

        unitsSinceFull_ += units;
        usedSinceFill_  += litres;
        totalUsed_      += litres;
        litres_ = fmaxf(0, litres_ - litres);
        lph = litres / dt * 3600.0f;
        dirty_ = true;
    }
    if (engineData && speedKmh > 1.0f) {
        kmSinceFill_ += speedKmh * dt / 3600.0f;
        dirty_ = true;
    }

    // smoothing for the live readouts (~2 s)
    const float a = fminf(1.0f, dt / 2.0f);
    rateLph_ += (lph - rateLph_) * a;
    speedSm_ += ((engineData ? speedKmh : 0) - speedSm_) * a;

    // editor auto-cancel and the "stop first" message timeout
    if (editing_ && now - editMs_ > FUEL_EDIT_TIMEOUT_MS) editing_ = false;
    if (editBlocked_ && now - blockedMs_ > 2500) editBlocked_ = false;

    save(false);
}

void FuelModel::save(bool force) {
    if (!persist_) return;
    const uint32_t now = millis();
    if (!force && (!dirty_ || now - lastSaveMs_ < FUEL_SAVE_EVERY_MS)) return;
    prefs.putFloat("k", k_);
    prefs.putFloat("litres", litres_);
    prefs.putFloat("uFull", unitsSinceFull_);
    prefs.putFloat("addFull", addedSinceFull_);
    prefs.putFloat("used", usedSinceFill_);
    prefs.putFloat("km", kmSinceFill_);
    prefs.putUChar("nLearn", learnedFills_);
    prefs.putBool("fullRef", haveFullRef_);
    prefs.putFloat("hist", histL100_);
    lastSaveMs_ = now;
    dirty_ = false;
}

void FuelModel::fillUp(bool full, float litresAdded) {
    litresAdded = fmaxf(0, litresAdded);

    // This tank's consumption updates your long-run average, used for range next time.
    if (kmSinceFill_ >= FUEL_HIST_MIN_KM) {
        const float tankL100 = usedSinceFill_ / kmSinceFill_ * 100.0f;
        histL100_ = (histL100_ > 0) ? histL100_ + (tankL100 - histL100_) * FUEL_HIST_RATE : tankL100;
    }

    if (full) {
        // Learn k from a full-to-full interval with known litres.
        if (haveFullRef_ && litresAdded > 0 && unitsSinceFull_ > 1.0f) {
            const float actual = addedSinceFull_ + litresAdded;
            const float estimated = unitsSinceFull_ * k_;
            if (actual >= FUEL_LEARN_MIN_L && estimated > 0) {
                const float ratio = clampf(actual / estimated, 0.6f, 1.6f);   // reject wild entries
                const float kNew = k_ * ratio;
                k_ = k_ + (kNew - k_) * FUEL_LEARN_RATE;
                k_ = clampf(k_, FUEL_K_DEFAULT * 0.3f, FUEL_K_DEFAULT * 3.0f);
                if (learnedFills_ < 255) learnedFills_++;
                LOG_PRINTF("[fuel] learn: actual %.2f L, est %.2f L, ratio %.3f -> k %.6f\n",
                              actual, estimated, ratio, k_);
            }
        }
        litres_ = FUEL_TANK_L;
        unitsSinceFull_ = 0;
        addedSinceFull_ = 0;
        haveFullRef_ = true;
    } else {
        litres_ = fminf(FUEL_TANK_L, litres_ + litresAdded);
        addedSinceFull_ += litresAdded;
    }

    usedSinceFill_ = 0;
    kmSinceFill_ = 0;
    dirty_ = true;
    save(true);
}

void FuelModel::resetLearning() {
    k_ = FUEL_K_DEFAULT;
    learnedFills_ = 0;
    dirty_ = true;
    save(true);
}

// ------------------------------------------------------------- fill editor

void FuelModel::editOpen(float speedKmh) {
    if (speedKmh > FUEL_EDIT_MAX_KMH) {          // only when stopped
        editBlocked_ = true;
        blockedMs_ = millis();
        return;
    }
    editing_ = true;
    editStep_ = 0;
    editFull_ = true;
    // suggest the estimated litres used, rounded to the step size
    const float used = FUEL_TANK_L - litres_;
    editLitres_ = roundf(used / FUEL_EDIT_STEP_L) * FUEL_EDIT_STEP_L;
    editMs_ = millis();
}

void FuelModel::editCancel() { editing_ = false; }

void FuelModel::editToggleType() {
    editFull_ = !editFull_;
    editMs_ = millis();
}

void FuelModel::editStepLitres(float delta) {
    editLitres_ = clampf(editLitres_ + delta, 0, FUEL_TANK_L);
    editMs_ = millis();
}

bool FuelModel::editNext() {
    editMs_ = millis();
    if (editStep_ == 0) { editStep_ = 1; return false; }
    fillUp(editFull_, editLitres_);
    editing_ = false;
    return true;
}

// ------------------------------------------------------------- view

FuelView FuelModel::view() const {
    FuelView f;
    f.litres        = litres_;
    f.capacity      = FUEL_TANK_L;
    f.usedSinceFill = usedSinceFill_;
    f.kmSinceFill   = kmSinceFill_;
    f.rateLph       = rateLph_;
    f.avgL100       = (kmSinceFill_ >= FUEL_AVG_MIN_KM) ? usedSinceFill_ / kmSinceFill_ * 100.0f : 0;
    f.instL100      = (speedSm_ > 5.0f) ? rateLph_ / speedSm_ * 100.0f : 0;
    // Range: starts from your past average, moving to this tank's as the km build up.
    const float past = (histL100_ > 0) ? histL100_ : FUEL_DEFAULT_L100;
    const float blend = clampf(kmSinceFill_ / FUEL_RANGE_BLEND_KM, 0, 1);
    const float l100 = (f.avgL100 > 0) ? past + (f.avgL100 - past) * blend : past;
    f.rangeKm       = litres_ / l100 * 100.0f;
    f.kRatio        = k_ / FUEL_K_DEFAULT;
    f.learnedFills  = learnedFills_;

    f.editing     = editing_;
    f.editStep    = editStep_;
    f.editFull    = editFull_;
    f.editLitres  = editLitres_;
    f.editBlocked = editBlocked_;
    return f;
}
