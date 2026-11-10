// can_bus.h - receive-only access to the bike's CAN bus (ESP32 TWAI controller).
#pragma once
#include <Arduino.h>

struct CanFrame {
    uint32_t id;
    uint8_t  dlc;
    uint8_t  data[8];
};

struct CanStats {
    uint32_t frames   = 0;
    uint32_t missed   = 0;    // frames lost because the receive queue was full
    uint32_t overruns = 0;    // frames lost in the controller's hardware FIFO
    uint32_t rxErrors = 0;    // bus errors: a wrong bit rate or bad wiring shows here
    bool     running  = false;
};

class CanBus {
public:
    // Puts the transceiver in a safe state. Call first thing in setup(), in every build.
    static void holdTransceiverSilent();

    bool begin();

    // Returns true and fills 'out' if a frame is waiting. Never blocks.
    bool read(CanFrame &out);

    // Call about once a second: restarts the controller if needed, updates stats.
    void service();

    const CanStats &stats() const { return stats_; }

private:
    bool installed_ = false;
    CanStats stats_;
};
