// ui.h - dash screen drawing (T-Display-S3, 320x170 landscape)
#pragma once
#include <Arduino.h>
#include <TFT_eSPI.h>
#include "link.h"
#include "fuel.h"
#include "clock.h"
#include "trip.h"
#include "tpms.h"
#include "battery.h"
#include "nav.h"

// Everything the screen needs for one frame.
struct DashView {
    bool     linkAlive  = false;
    bool     demo       = false;
    uint16_t rpm        = 0;
    uint8_t  gear       = TELEM_GEAR_UNKNOWN;
    bool     coolantOk  = false;
    float    coolantC   = 0;
    bool     vbatOk     = false;
    float    vbat       = 0;     // smoothed
    uint16_t vbatRawMv  = 0;     // as received
    bool     vbatStarting = false; // engine just started: ignore low voltage
    float    speedKmh   = 0;     // road speed (wheel chosen by SPEED_FROM_FRONT)
    float    speedFront = 0;
    float    speedRear  = 0;
    bool     clockOk    = false; // time has been set since power on
    uint8_t  hour       = 0;
    uint8_t  minute     = 0;
    uint8_t  throttle   = 0;     // 0..255
    uint8_t  flags      = 0;     // TELEM_FLAG_*
    uint16_t wheelFront = 0;     // raw counts
    uint16_t wheelRear  = 0;
    uint8_t  seq        = 0;
    bool     lugging    = false; // shift-down alert (high throttle, low rpm)
    LinkStats stats;
    FuelView  fuel;
    ClockView clock;
    TripView  trip;
    TyreView  tyres;
    BatteryView batt;
    const NavModel *nav = nullptr;     // GPS page reads the model directly: the trail is large
    // warning banner shown over any page
    bool      alertOn    = false;
    uint8_t   alertLevel = 1;          // 1 = amber, 2 = red
    char      alert1[32] = "";
    char      alert2[64] = "";
};

enum class Page : uint8_t { Main, Fuel, Trip, Tyres, Gps, GpsData, Clock, Diagnostics };
enum class Icon : uint8_t { Coolant, Battery };
enum class TachoAlert : uint8_t { None, ShiftUp, ShiftDown };

class DashUI {
public:
    void begin();
    void setBrightness(uint8_t level);   // 0..255
    void draw(const DashView &v, Page page);

private:
    TFT_eSPI     tft_;
    TFT_eSprite  spr_{&tft_};
    bool         sprOk_ = false;

    // tacho smoothing + peak hold
    float        barRpm_ = 0;
    uint16_t     peakRpm_ = 0;
    uint32_t     peakMs_ = 0;
    uint32_t     lastDrawMs_ = 0;

    void drawMain(const DashView &v);
    void drawDiag(const DashView &v);
    void drawFuel(const DashView &v);
    void drawFuelEditor(const FuelView &f);
    void drawFuelIcon(int cx, int cy, uint16_t colour);
    void drawClock(const DashView &v);
    void drawTrip(const DashView &v);
    void drawTripSet(const TripSetView &t, int x, const char *title, const char *subtitle);
    void drawTyres(const DashView &v);
    void drawTyreTile(int x, int y, int w, int h, const char *label, const TyreReading &r, bool lowWarn);
    void drawTyrePairing(const TyreView &t);
    void drawGps(const DashView &v);
    void drawGpsMap(const NavModel &n);
    void drawGpsCompass(const NavModel &n, int cx, int cy, int r);
    void drawGpsInfo(const NavModel &n);
    void drawGpsMenu(const NavModel &n);
    void drawGpsData(const DashView &v);
    void drawAlert(const DashView &v);
    void updateTacho(uint16_t rpm);
    void drawTacho(TachoAlert alert);
    void drawGear(uint8_t gear, int cx, int cy, uint16_t colour);
    void drawBigN(int cx, int cy, uint16_t colour);
    void drawValue(int x, int y, int w, int h, Icon icon, const char *value,
                   bool degree, uint16_t colour, bool alarm);
    void drawDigitsRight(const GFXfont &font, const char *s, int right, int baseY, uint16_t colour);
    void drawSpeed(int x, int y, int w, int h, const DashView &v);
    void drawClock(int x, int y, int w, int h, const DashView &v);
    void drawCoolant(int x, int y, int w, int h, const DashView &v);
    void drawBattery(int x, int y, int w, int h, const DashView &v);
    void drawCoolantIcon(int cx, int cy, uint16_t colour, uint16_t bg);
    void drawBatteryIcon(int cx, int cy, uint16_t colour);
    void diagRow(int x, int y, const char *label, const char *value, uint16_t colour);
};
