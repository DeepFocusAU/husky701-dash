// fuel.h - fuel used / remaining estimate from engine data (no fuel sensor).
//
// Model: fuel flow is proportional to  rpm x (idle load + throttle).
//   units/s  = rpm/1000 * (FUEL_IDLE_LOAD + throttle/255)
//   (none on overrun: closed throttle above idle while moving, see FUEL_CUT_*)
//   litres   = units * k
// k starts at FUEL_K_DEFAULT and is learned at each FULL fill-up where the
// litres pumped are entered: since the previous full tank, the litres added
// (partials + this fill) are the real consumption, so k is corrected towards
// real / estimated. A few full-to-full tanks gets it to within ~5-10 %.
//
// State is saved to NVS (flash) so it survives key-off.
#pragma once
#include <Arduino.h>

// What the fuel page needs to draw.
struct FuelView {
    float   litres        = 0;    // estimated fuel in tank
    float   capacity      = 0;
    float   usedSinceFill = 0;    // L since last fill-up (any)
    float   kmSinceFill   = 0;
    float   avgL100       = 0;    // since last fill-up (0 = not enough distance yet)
    float   instL100      = 0;    // smoothed, 0 when stopped
    float   rateLph       = 0;    // smoothed litres/hour
    float   rangeKm       = 0;
    float   kRatio        = 1;    // learned k / default k
    uint8_t learnedFills  = 0;    // full fills used for learning so far

    // fill-up editor
    bool    editing       = false;
    uint8_t editStep      = 0;    // 0 = choose FULL/PARTIAL, 1 = litres
    bool    editFull      = true;
    float   editLitres    = 0;
    bool    editBlocked   = false; // tried to open editor while moving
};

class FuelModel {
public:
    void begin();                         // load saved state
    // Call regularly (e.g. every 100 ms) with the latest data.
    void update(bool engineData, uint16_t rpm, uint8_t throttle, float speedKmh);
    void save(bool force = false);        // throttled NVS write

    // Fill-up: litresAdded = 0 with full=true means "filled to full, amount unknown".
    void fillUp(bool full, float litresAdded);
    void resetLearning();

    // Fill-up editor (driven by the piezo switch on the fuel page)
    void editOpen(float speedKmh);
    void editCancel();
    bool editing() const { return editing_; }
    void editToggleType();
    void editStepLitres(float delta);
    bool editNext();                      // returns true when the fill-up was saved

    FuelView view() const;
    float totalUsed() const { return totalUsed_; }   // runtime running total (L)
    void setPersist(bool persist) { persist_ = persist; }   // false: sim data isn't saved

private:
    // persisted
    float    litres_ = 0;
    float    k_ = 0;
    float    unitsSinceFull_ = 0;      // engine units since last FULL fill
    float    addedSinceFull_ = 0;      // litres added by partial fills since last FULL
    float    usedSinceFill_ = 0;
    float    kmSinceFill_ = 0;
    uint8_t  learnedFills_ = 0;
    bool     haveFullRef_ = false;     // a FULL fill has happened (learning reference)
    float    histL100_ = 0;            // your average over past tanks, 0 = not known yet

    // runtime
    uint32_t lastMs_ = 0;
    uint32_t lastSaveMs_ = 0;
    bool     dirty_ = false;
    bool     persist_ = true;
    float    rateLph_ = 0;             // smoothed
    float    speedSm_ = 0;
    float    totalUsed_ = 0;

    bool     editing_ = false;
    uint8_t  editStep_ = 0;
    bool     editFull_ = true;
    float    editLitres_ = 0;
    uint32_t editMs_ = 0;
    bool     editBlocked_ = false;
    uint32_t blockedMs_ = 0;
};
