// simulator.h - debug-mode source of telemetry frames.
//
// Builds frames in the exact wire format the CAN node will send
// (telemetry_packet.h) and pushes them through the real frame parser,
// so the display, parser, CRC and drop detection are all exercised
// with no hardware attached.
//
// Modes:
//   Off       - real link only (live build)
//   Internal  - bytes are fed straight into the parser. No wiring needed.
//   Loopback  - bytes are sent out of GPIO17 (link TX) and read back on
//               GPIO18 (link RX). Put a jumper wire from GPIO17 to GPIO18,
//               or later, two MAX3485 modules back-to-back, to test the
//               physical UART / RS-485 path.
#pragma once
#include <Arduino.h>
#include "link.h"

enum class SimMode : uint8_t { Off = 0, Internal = 1, Loopback = 2 };

class Simulator {
public:
    void begin(SimMode mode);
    void setMode(SimMode mode);
    SimMode mode() const { return mode_; }
    const char *modeName() const;

    // Call every loop. Emits a frame every SIM_FRAME_MS.
    void update(TelemetryLink &link);

    // Name of the current scenario phase, shown on the diagnostics page.
    const char *phaseName() const { return phaseName_; }

private:
    SimMode  mode_ = SimMode::Off;
    uint32_t lastFrameMs_ = 0;
    uint32_t startMs_ = 0;
    uint8_t  seq_ = 0;
    uint32_t frameCount_ = 0;

    // simulated bike state
    float    rpm_ = 0;
    uint8_t  gear_ = 0;
    float    coolantC_ = 24.0f;
    float    throttle_ = 0;      // 0..1
    float    vbat_ = 12.6f;      // sent as vbat_mV, as the CAN node's ADC will
    uint8_t  flags_ = 0;
    const char *phaseName_ = "start";

    // simulated GPS: wanders around Centennial Park, Sydney at the simulated road speed
    double   simLatDeg_ = -33.899;
    double   simLonDeg_ = 151.234;
    float    simCourseDeg_ = 40.0f;

    void step(float dt, uint32_t tMs);
    void emit(TelemetryLink &link);
};
