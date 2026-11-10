// trip.h - two sets of trip figures, both surviving key-off:
//   Today         - since 00:00 local time, from the GPS date
//   Since refuel  - since the last fill-up was saved on the Fuel page
#pragma once
#include <Arduino.h>

struct TripSetView {
    float    km      = 0;
    float    fuelL   = 0;
    uint32_t movingS = 0;     // time above TRIP_MOVING_KMH
    float    avgKmh  = 0;     // km / moving time, 0 until there's enough to go on
    float    maxKmh  = 0;
    float    avgL100 = 0;     // 0 until there's enough distance
};

struct TripView {
    TripSetView today;
    TripSetView sinceRefuel;
};

class TripModel {
public:
    void begin();

    // Call at ~10 Hz. fuelUsedL is the fuel model's running total (it only goes up).
    // gpsDay is the local date as days since 2000-01-01, or 0 when it isn't known.
    void update(bool engineData, float speedKmh, float fuelUsedL, uint16_t gpsDay);

    void resetSinceRefuel();
    void setPersist(bool persist) { persist_ = persist; }   // false: sim data isn't saved
    TripView view() const;

private:
    struct Set {
        float km = 0, fuelL = 0, movingS = 0, maxKmh = 0;
    };

    Set      today_, refuel_;
    uint16_t day_ = 0;

    uint32_t lastMs_ = 0, lastSaveMs_ = 0;
    bool     dirty_ = false, persist_ = true;
    float    lastFuel_ = -1;

    void save(bool force);
    static void addTo(Set &s, float speedKmh, float dt);
    static TripSetView viewOf(const Set &s);
};
