#include "simulator.h"
#include "config.h"
#include "tpms.h"
#include <math.h>
#include <string.h>

// km/h per 1000 rpm in each gear (approximate for a 701, used only to make
// the simulated wheel speed look sensible).
static const float KMH_PER_KRPM[7] = {0.0f, 9.5f, 13.0f, 16.0f, 19.0f, 21.5f, 23.5f};

static float noise(float amplitude) {
    return amplitude * ((float)random(-1000, 1001) / 1000.0f);
}

static float approach(float current, float target, float dt, float tau) {
    return current + (target - current) * fminf(1.0f, dt / tau);
}

void Simulator::begin(SimMode mode) {
    randomSeed(esp_random());
    setMode(mode);
}

void Simulator::setMode(SimMode mode) {
    mode_ = mode;
    startMs_ = millis();
    lastFrameMs_ = startMs_;
    coolantC_ = 35.0f;
}

const char *Simulator::modeName() const {
    switch (mode_) {
    case SimMode::Internal: return "SIM";
    case SimMode::Loopback: return "SIM-LOOP";
    default:                return "LIVE";
    }
}

void Simulator::update(TelemetryLink &link) {
    if (mode_ == SimMode::Off) return;

    const uint32_t now = millis();
    if (now - lastFrameMs_ < SIM_FRAME_MS) return;
    const float dt = (now - lastFrameMs_) / 1000.0f;
    lastFrameMs_ = now;

    step(dt, (now - startMs_) % SIM_LOOP_MS);
    emit(link);

    // Two pretend tyre sensors so the Tyres page and pairing can be tried on
    // the bench: front steady, rear leaking from 2.4 to 1.4 bar every lap.
    static uint32_t lastTpmsMs = 0;
    if (now - lastTpmsMs > 2000) {
        lastTpmsMs = now;
        static const uint8_t macF[6] = {0xA1, 0x3F, 0x00, 0x00, 0x5E, 0xC0};
        static const uint8_t macR[6] = {0xB7, 0x52, 0x00, 0x00, 0x5E, 0xC0};
        const float lap = ((now - startMs_) % SIM_LOOP_MS) / (float)SIM_LOOP_MS;
        tpms.onSensor(macF, "SIM", 2.2f + noise(0.01f), 24, 3.0f, -58);
        tpms.onSensor(macR, "SIM", 2.4f - 1.0f * lap, 27, 2.9f, -66);
    }
}

// Scripted ~70 s ride that loops: key on, start, warm-up idle, full-throttle
// run through the gears (hits the shift light), cruise, downshifts, a hot
// idle that trips the coolant warning, then engine off.
//
// Coolant and battery are scripted through every display state:
//   coolant  blue (cold) -> green -> amber (hot idle >100) -> red (>105)
//   battery  green (cranking sag is ignored), red charging fault late in the cruise,
//            red over-charge in the downshifts, amber (flat) with engine off
void Simulator::step(float dt, uint32_t tMs) {
    const float t = tMs / 1000.0f;
    float rpmTarget = 0;
    float coolTarget = 88.0f;
    bool  running = true;
    bool  clutch = false;
    bool  standUp = true;
    bool  map2 = false;
    bool  killRun = true;
    throttle_ = 0;

    if (t < 5.0f) {                                   // key on, engine off
        phaseName_ = "key on";
        running = false; standUp = false; gear_ = 0;
        if (t < 0.5f) coolantC_ = 35.0f;               // reset each loop
    } else if (t < 6.0f) {                            // cranking
        phaseName_ = "cranking";
        standUp = false; gear_ = 0;
        rpmTarget = 1700; clutch = true;
    } else if (t < 14.0f) {                           // warm-up idle
        phaseName_ = "idle";
        gear_ = 0; standUp = (t > 12.0f);
        rpmTarget = 1500 + noise(40);
    } else if (t < 38.0f) {                           // full-throttle run, 1st..6th
        phaseName_ = "accelerate";
        const float st = t - 14.0f;
        gear_ = (uint8_t)min(6, 1 + (int)(st / 4.0f));
        const float f = fmodf(st, 4.0f) / 4.0f;       // progress through this gear
        if (f < 0.07f && gear_ > 1) {                 // shifting: clutch in, throttle off
            clutch = true;
            rpmTarget = 4200;
        } else {
            const float peak = (gear_ <= 3) ? 7900.0f : 6600.0f;
            rpmTarget = 3800 + f * (peak - 3800);
            throttle_ = 0.85f + noise(0.05f);
        }
        coolTarget = 92.0f;
    } else if (t < 41.0f) {                           // lugging: 6th, low rpm, big throttle
        phaseName_ = "lugging";
        gear_ = 6;
        rpmTarget = 2600;
        throttle_ = 0.70f + noise(0.03f);
    } else if (t < 48.0f) {                           // cruise in 6th, map 2
        phaseName_ = "cruise";
        gear_ = 6; map2 = true;
        rpmTarget = 4800 + 300 * sinf(t * 0.8f);
        throttle_ = 0.32f + noise(0.03f);
    } else if (t < 56.0f) {                           // braking, downshift 6 -> 2
        phaseName_ = "downshift";
        const float st = t - 48.0f;
        gear_ = (uint8_t)max(2, 6 - (int)(st / 1.6f));
        const float f = fmodf(st, 1.6f) / 1.6f;
        clutch = (f < 0.15f);
        rpmTarget = clutch ? 5600 : 5200 - f * 2200;
    } else if (t < 63.0f) {                           // stopped, hot idle
        phaseName_ = "hot idle";
        gear_ = 0;
        rpmTarget = 1550 + noise(50);
        coolTarget = 115.0f;                          // passes amber, then red
    } else {                                          // kill switch off
        phaseName_ = "engine off";
        running = false; killRun = false; standUp = false; gear_ = 0;
        coolTarget = 80.0f;
    }

    if (!running) rpmTarget = 0;

    // engine responds quickly, coolant slowly
    rpm_ = approach(rpm_, rpmTarget, dt, 0.18f);
    if (rpm_ < 30) rpm_ = 0;
    coolantC_ = approach(coolantC_, coolTarget, dt, (coolTarget > coolantC_) ? 6.0f : 25.0f);

    // battery: charging when running, sags while cranking, plus scripted faults
    float vTarget = 12.6f;
    if (strcmp(phaseName_, "cranking") == 0) vTarget = 10.9f;    // sag: suppressed by start grace
    else if (running && rpm_ > 1000)         vTarget = 14.2f;
    if (t >= 43.0f && t < 48.0f)             vTarget = 11.8f;    // red: charging fault
    else if (t >= 48.0f && t < 54.0f)        vTarget = 15.4f;    // red: regulator over-charge
    else if (t >= 63.0f)                     vTarget = 11.6f;    // amber: flat, engine off
    vbat_ = approach(vbat_, vTarget + noise(0.03f), dt, 0.4f);

    flags_ = TELEM_FLAG_CAN_OK;
    if (clutch)  flags_ |= TELEM_FLAG_CLUTCH_IN;
    if (killRun) flags_ |= TELEM_FLAG_KILL_RUN;
    if (standUp) flags_ |= TELEM_FLAG_STAND_UP;
    if (map2)    flags_ |= TELEM_FLAG_MAP2;
    if (throttle_ > 0.02f) flags_ |= TELEM_FLAG_THROTTLE_OPEN;
}

void Simulator::emit(TelemetryLink &link) {
    TelemetryPayload p{};
    p.version  = TELEM_VERSION;
    p.seq      = seq_++;
    p.rpm      = (uint16_t)rpm_;
    p.gear     = gear_;
    p.throttle = (uint8_t)constrain(throttle_ * 255.0f, 0.0f, 255.0f);
    p.coolant_dC = (int16_t)lroundf(coolantC_ * 10.0f);

    // Wheel speed in raw counts, using the same scale as WHEEL_KMH_PER_COUNT.
    const float kmh = (gear_ > 0 && !(flags_ & TELEM_FLAG_CLUTCH_IN))
                          ? KMH_PER_KRPM[gear_] * rpm_ / 1000.0f : 0.0f;
    p.wheel_rear  = (uint16_t)(kmh / WHEEL_KMH_PER_COUNT);
    p.wheel_front = (uint16_t)(kmh / WHEEL_KMH_PER_COUNT * 0.985f);
    p.vbat_mV = (uint16_t)lroundf(vbat_ * 1000.0f);
    p.flags = flags_;

    // Bike dash clock as the CAN node will forward it: starts at 10:30 when the
    // simulator starts and runs in real time.
    const uint32_t simMin = 10 * 60 + 30 + (millis() - startMs_) / 60000;
    p.clock_hour = (uint8_t)((simMin / 60) % 24);
    p.clock_min  = (uint8_t)(simMin % 60);

    // GPS: moves at the wheel speed, with a slowly turning course and a sharp
    // change of direction each lap. There's no fix for the first few seconds.
    const float dtS = SIM_FRAME_MS / 1000.0f;
    const float tS = (millis() - startMs_) / 1000.0f;
    simCourseDeg_ = fmodf(simCourseDeg_ + (6.0f + 18.0f * sinf(tS * 0.13f)) * dtS + 360.0f, 360.0f);
    const float meters = kmh / 3.6f * dtS;
    const double radC = simCourseDeg_ * M_PI / 180.0;
    simLatDeg_ += meters * cos(radC) / 111319.5;
    simLonDeg_ += meters * sin(radC) / (111319.5 * cos(simLatDeg_ * M_PI / 180.0));
    // back to the start if it wanders too far
    if (fabs(simLatDeg_ + 33.899) > 0.045 || fabs(simLonDeg_ - 151.234) > 0.05) {
        simLatDeg_ = -33.899;
        simLonDeg_ = 151.234;
    }
    p.gps_fix        = tS > 6.0f ? 1 : 0;
    p.gps_alt_m      = (int16_t)lroundf(120.0f + 60.0f * sinf(tS * 0.05f));
    p.gps_hdop_dx10  = 12;
    p.gps_day        = tS > 6.0f ? 9806 : 0;   // any fixed date
    p.gps_sats       = tS > 6.0f ? 9 : 2;
    p.gps_lat_e7     = (int32_t)llround(simLatDeg_ * 1e7);
    p.gps_lon_e7     = (int32_t)llround(simLonDeg_ * 1e7);
    p.gps_speed_dkmh = (uint16_t)lroundf(kmh * 10.0f);
    p.gps_course_dd  = (uint16_t)lroundf(simCourseDeg_ * 10.0f);

    uint8_t frame[TELEM_FRAME_LEN];
    frame[0] = TELEM_SYNC0;
    frame[1] = TELEM_SYNC1;
    memcpy(&frame[2], &p, TELEM_PAYLOAD_LEN);
    const uint16_t crc = telem_crc16(&frame[2], TELEM_PAYLOAD_LEN);
    frame[2 + TELEM_PAYLOAD_LEN] = (uint8_t)(crc & 0xFF);
    frame[3 + TELEM_PAYLOAD_LEN] = (uint8_t)(crc >> 8);

    frameCount_++;

    // Fault injection, so the CRC-error and drop counters can be seen working.
#if SIM_FAULT_CRC_EVERY > 0
    if (frameCount_ % SIM_FAULT_CRC_EVERY == 0) frame[2 + TELEM_PAYLOAD_LEN] ^= 0x5A;
#endif
#if SIM_FAULT_DROP_EVERY > 0
    if (frameCount_ % SIM_FAULT_DROP_EVERY == 0) return;   // frame "lost"
#endif

    if (mode_ == SimMode::Loopback) {
        link.writeRaw(frame, sizeof(frame));
    } else {
        for (size_t i = 0; i < sizeof(frame); i++) link.feed(frame[i]);
    }
}
