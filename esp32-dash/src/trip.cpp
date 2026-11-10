#include "trip.h"
#include "config.h"
#include <Preferences.h>
#include <math.h>

static Preferences tprefs;

void TripModel::begin() {
    tprefs.begin("trip2", false);
    day_            = tprefs.getUShort("day", 0);
    today_.km       = tprefs.getFloat("dKm", 0);
    today_.fuelL    = tprefs.getFloat("dFuel", 0);
    today_.movingS  = tprefs.getFloat("dMov", 0);
    today_.maxKmh   = tprefs.getFloat("dMax", 0);
    refuel_.km      = tprefs.getFloat("rKm", 0);
    refuel_.fuelL   = tprefs.getFloat("rFuel", 0);
    refuel_.movingS = tprefs.getFloat("rMov", 0);
    refuel_.maxKmh  = tprefs.getFloat("rMax", 0);
    lastMs_ = lastSaveMs_ = millis();
}

void TripModel::addTo(Set &s, float speedKmh, float dt) {
    if (speedKmh > 1.0f) s.km += speedKmh * dt / 3600.0f;
    if (speedKmh > TRIP_MOVING_KMH) s.movingS += dt;
    if (speedKmh > s.maxKmh) s.maxKmh = speedKmh;
}

void TripModel::update(bool engineData, float speedKmh, float fuelUsedL, uint16_t gpsDay) {
    const uint32_t now = millis();
    const float dt = (now - lastMs_) / 1000.0f;
    lastMs_ = now;

    // New day: at midnight while riding, or at the first fix after a day off the
    // bike. Riding before that first fix counts as the previous day's, then clears.
    if (gpsDay != 0 && gpsDay != day_) {
        if (day_ != 0) today_ = Set{};
        day_ = gpsDay;
        dirty_ = true;
        save(true);
    }

    // fuel: take the increase in the fuel model's running total
    if (lastFuel_ >= 0 && fuelUsedL > lastFuel_) {
        const float used = fuelUsedL - lastFuel_;
        today_.fuelL += used;
        refuel_.fuelL += used;
        dirty_ = true;
    }
    lastFuel_ = fuelUsedL;

    if (engineData && dt > 0 && dt <= 2.0f) {
        addTo(today_, speedKmh, dt);
        addTo(refuel_, speedKmh, dt);
        if (speedKmh > 1.0f) dirty_ = true;
    }

    save(false);
}

void TripModel::resetSinceRefuel() {
    refuel_ = Set{};
    dirty_ = true;
    save(true);
}

void TripModel::save(bool force) {
    if (!persist_) return;
    const uint32_t now = millis();
    if (!dirty_ || (!force && now - lastSaveMs_ < TRIP_SAVE_EVERY_MS)) return;
    tprefs.putUShort("day", day_);
    tprefs.putFloat("dKm", today_.km);
    tprefs.putFloat("dFuel", today_.fuelL);
    tprefs.putFloat("dMov", today_.movingS);
    tprefs.putFloat("dMax", today_.maxKmh);
    tprefs.putFloat("rKm", refuel_.km);
    tprefs.putFloat("rFuel", refuel_.fuelL);
    tprefs.putFloat("rMov", refuel_.movingS);
    tprefs.putFloat("rMax", refuel_.maxKmh);
    lastSaveMs_ = now;
    dirty_ = false;
}

TripSetView TripModel::viewOf(const Set &s) {
    TripSetView v;
    v.km      = s.km;
    v.fuelL   = s.fuelL;
    v.movingS = (uint32_t)s.movingS;
    v.avgKmh  = s.movingS > 30 ? s.km / (s.movingS / 3600.0f) : 0;
    v.maxKmh  = s.maxKmh;
    v.avgL100 = s.km >= FUEL_AVG_MIN_KM ? s.fuelL / s.km * 100.0f : 0;
    return v;
}

TripView TripModel::view() const {
    TripView t;
    t.today = viewOf(today_);
    t.sinceRefuel = viewOf(refuel_);
    return t;
}
