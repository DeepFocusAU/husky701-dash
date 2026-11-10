// Husky701 dash - LilyGO T-Display-S3
//
// Shows RPM (tacho bar), gear, coolant, battery and road speed for a 2023
// Husqvarna 701. Battery volts are measured by the CAN node and sent in the frame.
// Data arrives as telemetry frames (telemetry_packet.h). In the default
// "sim" build the frames come from the built-in simulator, so no other
// hardware is needed while working on the display.
//
// Pages (1 tap cycles):  Main -> Fuel -> Trip -> Tyres -> GPS -> GPS data -> Clock -> Diagnostics
// Any tap gesture while a warning banner shows dismisses it for 5 minutes.
//
// Input is one momentary piezo switch that only sends a pulse, so it can't be
// held. Functions are picked by the number of quick taps (see TAP_GAP_MS).
// More than 4 taps does nothing, so a mistake can be abandoned by tapping on.
//
// Normal use:
//   1 tap  : next page
//   2 taps : Fuel page        -> open fill-up editor
//            Tyres page       -> pair front / rear sensors
//            GPS pages        -> drop a waypoint
//            Diagnostics page -> cycle data source SIM -> SIM-LOOP -> LIVE
//                                (SIM-LOOP needs a jumper GPIO17 -> GPIO18)
//   3 taps : cycle backlight brightness
//   4 taps : GPS pages        -> open waypoint menu
// Waypoint menu:
//   1 tap: next   4 taps: back   2 taps: choose   3 taps: exit
// Fill-up editor (only opens when stopped):
//   step 1  1 tap: FULL / PARTIAL   2 taps: next   3 taps: cancel
//   step 2  1 tap: +0.5 L   4 taps: -0.5 L   2 taps: save   3 taps: cancel
// Tyre pairing:
//   1 tap: next sensor   2 taps: assign   3 taps: exit   4 taps: skip wheel
//
// Clock: the time of day comes from the GPS on the CAN node. Road speed also comes
// from GPS when there's a fix, with wheel speed as the backup.

#include <Arduino.h>
#include <string.h>
#include "config.h"
#include "link.h"
#include "simulator.h"
#include "ui.h"
#include "fuel.h"
#include "clock.h"
#include "trip.h"
#include "tpms.h"
#include "battery.h"
#include "nav.h"
#include <esp_task_wdt.h>

static TelemetryLink telemetry;   // (not "link": clashes with POSIX link())
static Simulator     sim;
static DashUI        ui;
static FuelModel     fuel;
static ClockModel    clockModel;
static TripModel     trip;
static bool          lugging = false;   // shift-down alert state
static uint32_t      lugSinceMs = 0;
static BatteryMonitor battMon;
static BatteryView   battView;
static NavModel      nav;

// ---- warning banners (one at a time, highest priority wins) ----
enum AlertId { AL_TYRE_F, AL_TYRE_R, AL_BATT, AL_KEYON, AL_COUNT };
static uint32_t alertSnoozeUntil[AL_COUNT] = {0};
static uint32_t alertShownSince[AL_COUNT] = {0};
static int      alertCurrent = -1;
static uint32_t battAlertUntil = 0;
static uint32_t keyOnSinceMs = 0;

// ---- backlight: night dimming with a manual override ----
static bool     manualBright = false;
static int      lastNight = -1;

static Page     page = Page::Main;
static uint8_t  brightnessIdx = BACKLIGHT_DEFAULT;
static const uint8_t BRIGHTNESS[] = BACKLIGHT_LEVELS;
static float    vbatFiltered = 0;
static uint32_t lastUiMs = 0;
static uint32_t lastLogMs = 0;
static uint32_t lastFuelMs = 0;
static float    speedNow = 0;   // km/h, for the 'only when stopped' editor locks

// ------------------------------------------------------------ piezo switch

static portMUX_TYPE      tapMux = portMUX_INITIALIZER_UNLOCKED;
static volatile uint8_t  tapCount = 0;
static volatile uint32_t tapLastEdgeMs = 0;
static volatile bool     tapLineLow = false;

// Edges are caught by interrupt because a pulse can be shorter than a redraw.
// Each pulse counts once, whatever its length, and bounce is ignored. A high
// reading with no low before it means the whole pulse passed while the ISR was
// held off (it can't run during a flash write), so that counts as a tap too.
static void ARDUINO_ISR_ATTR onTapEdge() {
    portENTER_CRITICAL_ISR(&tapMux);
    const uint32_t now = millis();
    const bool low = digitalRead(PIN_TAP_SWITCH) == LOW;
    if ((low || !tapLineLow) && now - tapLastEdgeMs >= TAP_QUIET_MS && tapCount < 255) tapCount++;
    tapLineLow = low;
    tapLastEdgeMs = now;
    portEXIT_CRITICAL_ISR(&tapMux);
}

// returns the number of taps once a gesture has finished, 0 = none (or too many)
static int pollTaps() {
    int taps = 0;
    portENTER_CRITICAL(&tapMux);
    if (tapCount > 0 && millis() - tapLastEdgeMs >= TAP_GAP_MS) {
        taps = tapCount;
        tapCount = 0;
    }
    portEXIT_CRITICAL(&tapMux);
    if (taps > 0) LOG_PRINTF("[taps] %d\n", taps);
    return taps <= TAP_MAX ? taps : 0;
}

// ------------------------------------------------------------ serial console

// "linktest" checks the link wiring.
static void pollSerialCommands() {
    static char line[32];
    static size_t len = 0;
    while (Serial.available() > 0) {
        const char c = (char)Serial.read();
        if (c != '\n' && c != '\r') {
            if (len < sizeof(line) - 1) line[len++] = c;
            continue;
        }
        if (len == 0) continue;
        line[len] = 0;
        len = 0;
        int pageNum;
        if (sscanf(line, "page %d", &pageNum) == 1 && pageNum >= 0 && pageNum <= (int)Page::Diagnostics) {
            page = (Page)pageNum;
            LOG_PRINTF("[page] %d\n", pageNum);
        } else if (strcmp(line, "linktest") == 0) {
            telemetry.probePins();
        } else {
            LOG_PRINTLN("Commands: linktest, page N");
        }
    }
}

// ------------------------------------------------------------ setup / loop

void setup() {
    Serial.setTxBufferSize(2048);    // room for many log lines, so the skip check rarely triggers
    Serial.begin(115200);
    Serial.setTxTimeoutMs(0);       // on buck power there is no USB host: never wait for one
    pinMode(PIN_TAP_SWITCH, INPUT_PULLUP);
    tapLineLow = digitalRead(PIN_TAP_SWITCH) == LOW;
    attachInterrupt(digitalPinToInterrupt(PIN_TAP_SWITCH), onTapEdge, CHANGE);

    ui.begin();
    ui.setBrightness(BRIGHTNESS[brightnessIdx]);
    telemetry.begin();
    sim.begin((SimMode)SIM_MODE_DEFAULT);
    fuel.begin();
    clockModel.begin();
    trip.begin();
    tpms.begin();
    battMon.begin();
    nav.begin();

    esp_task_wdt_init(4, true);     // restart rather than sit frozen if the loop ever locks up
    esp_task_wdt_add(NULL);
    LOG_PRINTF("Husky701 dash started, source: %s, last reset reason: %d\n", sim.modeName(), (int)esp_reset_reason());
}

void loop() {
    esp_task_wdt_reset();
    // 1. data in: simulator (if enabled) pushes frames, then the UART is read
    sim.update(telemetry);
    telemetry.poll(sim.mode() != SimMode::Internal);
    pollSerialCommands();

    // 2. piezo switch (behaviour depends on the page and on any open editor)
    int taps = pollTaps();

    // any gesture while a warning banner shows just dismisses it (snoozed 5 min)
    if (alertCurrent >= 0 && taps) {
        alertSnoozeUntil[alertCurrent] = millis() + ALERT_SNOOZE_MS;
        if (alertCurrent == AL_BATT) battAlertUntil = 0;
        alertCurrent = -1;
        taps = 0;
    }

    if (fuel.editing()) {
        const FuelView f = fuel.view();
        if (f.editStep == 0) {
            if (taps == 1) fuel.editToggleType();
            if (taps == 2) fuel.editNext();
            if (taps == 3) fuel.editCancel();
        } else {
            if (taps == 1) fuel.editStepLitres(+FUEL_EDIT_STEP_L);
            if (taps == 4) fuel.editStepLitres(-FUEL_EDIT_STEP_L);
            if (taps == 2 && fuel.editNext()) {
                trip.resetSinceRefuel();
                LOG_PRINTF("[fuel] fill-up saved: %s %.1f L\n", f.editFull ? "FULL" : "PARTIAL", f.editLitres);
            }
            if (taps == 3) fuel.editCancel();
        }
    } else if (tpms.pairing()) {
        if (taps == 1) tpms.pairNextCandidate();
        if (taps == 2) tpms.pairAssign();
        if (taps == 4) tpms.pairSkip();
        if (taps == 3) tpms.pairCancel();
    } else if (nav.menuIsOpen()) {
        if (taps == 1) nav.menuNext();
        if (taps == 4) nav.menuPrev();
        if (taps == 2) nav.menuChoose();
        if (taps == 3) nav.menuCancel();
    } else {
        if (taps == 1) {
            page = (page == Page::Main) ? Page::Fuel
                 : (page == Page::Fuel) ? Page::Trip
                 : (page == Page::Trip) ? Page::Tyres
                 : (page == Page::Tyres) ? Page::Gps
                 : (page == Page::Gps) ? Page::GpsData
                 : (page == Page::GpsData) ? Page::Clock
                 : (page == Page::Clock) ? Page::Diagnostics
                 : Page::Main;
        }
        if (taps == 3) {
            brightnessIdx = (brightnessIdx + 1) % sizeof(BRIGHTNESS);
            ui.setBrightness(BRIGHTNESS[brightnessIdx]);
            manualBright = true;           // until the next day/night change
        }
        if (taps == 2) {
            if (page == Page::Fuel)  fuel.editOpen(speedNow);
            if (page == Page::Tyres) tpms.pairOpen();
            if (page == Page::Gps || page == Page::GpsData) nav.dropWaypoint();
            if (page == Page::Diagnostics) {
                const SimMode next = (sim.mode() == SimMode::Internal) ? SimMode::Loopback
                                   : (sim.mode() == SimMode::Loopback) ? SimMode::Off
                                   : SimMode::Internal;
                sim.setMode(next);
                telemetry.resetStats();
                LOG_PRINTF("Data source: %s\n", sim.modeName());
            }
        }
        if (taps == 4 && (page == Page::Gps || page == Page::GpsData)) nav.menuOpen();
    }

    // 3. battery: from the CAN node's ADC, lightly smoothed for a steady readout
    const TelemetryPayload &d = telemetry.data();
    const bool vbatOk = telemetry.alive() && d.vbat_mV != TELEM_VBAT_UNKNOWN;
    if (vbatOk) {
        const float vNow = d.vbat_mV / 1000.0f;
        vbatFiltered = (vbatFiltered == 0) ? vNow : vbatFiltered + (vNow - vbatFiltered) * 0.1f;
    } else {
        vbatFiltered = 0;
    }

    // engine start: the grace window opens when rpm leaves zero (cranking)
    static bool     engineTurning = false;
    static uint32_t engineStartMs = 0;
    const bool turning = telemetry.alive() && d.rpm > 0;
    if (turning && !engineTurning) engineStartMs = millis();
    engineTurning = turning;
    const bool vbatStarting = turning && millis() - engineStartMs < VBAT_START_GRACE_MS;

    // 4. fuel model (10 Hz) and clock
    const uint32_t now = millis();
    const bool gpsFix = telemetry.alive() && d.gps_fix;
    const float gpsKmh = gpsFix ? d.gps_speed_dkmh / 10.0f : 0;
    if (gpsFix)                speedNow = gpsKmh >= GPS_SPEED_MIN_KMH ? gpsKmh : 0;
    else if (telemetry.alive()) speedNow = (SPEED_FROM_FRONT ? d.wheel_front : d.wheel_rear) * WHEEL_KMH_PER_COUNT;
    else                        speedNow = 0;

    // sim data must not end up in the real trip, fuel or waypoint records
    const bool persist = sim.mode() == SimMode::Off;
    fuel.setPersist(persist);
    trip.setPersist(persist);
    nav.setPersist(persist);

    // lugging: high throttle at low rpm in gear, held for LUG_HOLD_MS
    {
        const bool cond = telemetry.alive()
                       && d.gear >= LUG_MIN_GEAR && d.gear <= 6
                       && !(d.flags & TELEM_FLAG_CLUTCH_IN)
                       && d.rpm > 800 && d.rpm < LUG_RPM
                       && d.throttle * 100 >= LUG_THROTTLE_PCT * 255;
        if (!cond) lugSinceMs = 0;
        else if (lugSinceMs == 0) lugSinceMs = now ? now : 1;
        lugging = cond && (now - lugSinceMs >= LUG_HOLD_MS);
    }

    if (now - lastFuelMs >= 100) {
        lastFuelMs = now;
        fuel.update(telemetry.alive(), d.rpm, d.throttle, speedNow);
        trip.update(telemetry.alive(), speedNow, fuel.totalUsed(), gpsFix ? d.gps_day : 0);

        // battery health: watch each start (raw voltage, so the cranking dip isn't smoothed away)
        battMon.update(vbatOk, d.vbat_mV / 1000.0f, d.rpm);
        battView = battMon.view();
        if (battView.newResult && (battView.health == BattHealth::Weak || battView.health == BattHealth::Bad))
            battAlertUntil = now + 8000;
    }
    if (telemetry.alive() && d.clock_hour != TELEM_CLOCK_UNKNOWN)
        clockModel.syncFromGps(d.clock_hour, d.clock_min);
    tpms.update();
    nav.update(telemetry.alive() && d.gps_fix, d.gps_lat_e7, d.gps_lon_e7,
               d.gps_speed_dkmh / 10.0f, d.gps_course_dd / 10.0f, d.gps_sats, d.gps_alt_m, d.gps_hdop_dx10);

    // key left on: engine off and stopped for KEYON_WARN_S
    if (telemetry.alive() && d.rpm == 0 && speedNow < 1.0f) {
        if (keyOnSinceMs == 0) keyOnSinceMs = now ? now : 1;
    } else {
        keyOnSinceMs = 0;
    }

    // night dimming (only once the clock is set)
    const ClockView cv = clockModel.view();
    if (NIGHT_DIM && cv.valid) {
        const int mins = cv.hour * 60 + cv.minute;
        const int night = (NIGHT_START_MIN > NIGHT_END_MIN)
                        ? (mins >= NIGHT_START_MIN || mins < NIGHT_END_MIN)
                        : (mins >= NIGHT_START_MIN && mins < NIGHT_END_MIN);
        if (night != lastNight) { manualBright = false; lastNight = night; }
        if (!manualBright) ui.setBrightness(night ? NIGHT_LEVEL : BRIGHTNESS[brightnessIdx]);
    }

    // 5. pick the warning banner to show (tyres > battery bad > key on > battery weak)
    const TyreView tyres = tpms.view();
    char a1[32] = "", a2[64] = "";
    uint8_t aLevel = 1;
    int want = -1;
    auto offer = [&](int id, bool active, uint8_t level, const char *l1, const char *l2, bool timed) {
        if (want >= 0 || !active || now < alertSnoozeUntil[id]) {
            if (!active) alertShownSince[id] = 0;
            return;
        }
        if (alertShownSince[id] == 0) alertShownSince[id] = now ? now : 1;
        if (timed && now - alertShownSince[id] > 8000) {   // auto-hide, back in 5 min
            alertSnoozeUntil[id] = now + ALERT_SNOOZE_MS;
            alertShownSince[id] = 0;
            return;
        }
        want = id; aLevel = level;
        strncpy(a1, l1, sizeof(a1) - 1);
        strncpy(a2, l2, sizeof(a2) - 1);
    };
    {
        char t2[64];
        const bool fLow = tyres.front.valid && tyres.front.ageS <= TPMS_STALE_S && tyres.front.bar < TPMS_FRONT_LOW_BAR;
        const bool rLow = tyres.rear.valid && tyres.rear.ageS <= TPMS_STALE_S && tyres.rear.bar < TPMS_REAR_LOW_BAR;
        snprintf(t2, sizeof(t2), "%.0f psi (%.2f bar) - check for a puncture", tyres.front.bar * 14.5038f, tyres.front.bar);
        offer(AL_TYRE_F, fLow, 2, "FRONT TYRE LOW", t2, true);
        snprintf(t2, sizeof(t2), "%.0f psi (%.2f bar) - check for a puncture", tyres.rear.bar * 14.5038f, tyres.rear.bar);
        offer(AL_TYRE_R, rLow, 2, "REAR TYRE LOW", t2, true);

        snprintf(t2, sizeof(t2), "Cranked at %.1f V (rest %.1f V)", battView.crankMinV, battView.restV);
        offer(AL_BATT, now < battAlertUntil && battView.health == BattHealth::Bad, 2, "BATTERY BAD", t2, false);

        const bool keyOn = keyOnSinceMs && now - keyOnSinceMs >= (uint32_t)KEYON_WARN_S * 1000;
        offer(AL_KEYON, keyOn, 1, "KEY ON, ENGINE OFF",
              (d.flags & TELEM_FLAG_KILL_RUN) ? "Turn the key off to save the battery"
                                              : "Kill switch is off - turn the key off too", false);

        offer(AL_BATT, now < battAlertUntil && battView.health == BattHealth::Weak, 1, "BATTERY WEAK", t2, false);
    }
    alertCurrent = want;

    // 6. redraw at a fixed frame rate
    if (now - lastUiMs >= UI_FRAME_MS) {
        lastUiMs = now;

        DashView v;
        v.linkAlive  = telemetry.alive();
        v.demo       = sim.mode() != SimMode::Off;
        v.rpm        = d.rpm;
        v.gear       = d.gear;
        v.coolantOk  = d.coolant_dC != TELEM_COOLANT_UNKNOWN;
        v.coolantC   = d.coolant_dC / 10.0f;
        v.vbatOk     = vbatOk;
        v.vbat       = vbatFiltered;
        v.vbatRawMv  = d.vbat_mV;
        v.vbatStarting = vbatStarting;
        v.speedFront = d.wheel_front * WHEEL_KMH_PER_COUNT;
        v.speedRear  = d.wheel_rear * WHEEL_KMH_PER_COUNT;
        v.speedKmh   = speedNow;
        v.clockOk    = cv.valid;
        v.hour       = cv.hour;
        v.minute     = cv.minute;
        v.throttle   = d.throttle;
        v.flags      = d.flags;
        v.wheelFront = d.wheel_front;
        v.wheelRear  = d.wheel_rear;
        v.seq        = d.seq;
        v.stats      = telemetry.stats();
        v.fuel       = fuel.view();
        v.clock      = cv;
        v.trip       = trip.view();
        v.tyres      = tyres;
        v.batt       = battView;
        v.nav        = &nav;
        v.alertOn   = want >= 0;
        v.alertLevel = aLevel;
        strncpy(v.alert1, a1, sizeof(v.alert1) - 1);
        strncpy(v.alert2, a2, sizeof(v.alert2) - 1);
        v.lugging    = lugging;
        ui.draw(v, page);
    }

    // 7. once-a-second log to the serial monitor
    if (now - lastLogMs >= 1000) {
        lastLogMs = now;
        const LinkStats &s = telemetry.stats();
        LOG_PRINTF("[%s %s] rpm=%u gear=%u cool=%.1f vbat=%.2f kmh=%.0f flags=0x%02X | fps=%.0f ok=%lu crc=%lu drop=%lu\n",
                      sim.modeName(), sim.mode() != SimMode::Off ? sim.phaseName() : "",
                      d.rpm, d.gear, d.coolant_dC / 10.0f, vbatFiltered,
                      speedNow, d.flags,
                      s.framesPerSec, (unsigned long)s.framesOk,
                      (unsigned long)s.crcErrors, (unsigned long)s.seqDrops);
    }
}
