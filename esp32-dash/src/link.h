// link.h - receives telemetry frames from the CAN node over RS-485 (UART1)
#pragma once
#include <Arduino.h>
#include "telemetry_packet.h"

struct LinkStats {
    uint32_t framesOk     = 0;
    uint32_t crcErrors    = 0;
    uint32_t seqDrops     = 0;
    uint32_t lastFrameMs  = 0;
    float    framesPerSec = 0;
};

class TelemetryLink {
public:
    void begin();

    // Reads everything waiting on UART1 and parses it, or throws it away when
    // useUart is false (the internal simulator is the data source).
    // Returns true if at least one new valid payload was received.
    bool poll(bool useUart = true);

    // Feed one byte into the frame parser. poll() uses this for UART bytes;
    // the simulator uses it to inject frames without any wiring.
    bool feed(uint8_t b);

    // Write raw bytes out of the link UART TX pin (simulator loopback mode).
    void writeRaw(const uint8_t *data, size_t len);

    void resetStats();

    // Wiring check: samples the RX pin directly and prints what's on it.
    // Blocks for about half a second, so it only runs on request.
    void probePins();

    const TelemetryPayload &data() const { return latest_; }
    const LinkStats &stats() const { return stats_; }
    bool alive() const;

private:
    enum class State : uint8_t { Sync0, Sync1, Body };
    State    state_ = State::Sync0;
    uint8_t  buf_[TELEM_PAYLOAD_LEN + 2];
    size_t   idx_ = 0;
    bool     haveSeq_ = false;
    uint8_t  lastSeq_ = 0;

    TelemetryPayload latest_{};
    LinkStats stats_;

    uint32_t rateWindowStart_ = 0;
    uint32_t rateWindowCount_ = 0;

    void handleFrame();
    void updateRate();
};
