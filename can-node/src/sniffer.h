// sniffer.h - USB serial tools for working out what's on the bike's bus.
#pragma once
#include <Arduino.h>
#include "can_bus.h"

class Sniffer {
public:
    enum class Mode : uint8_t { Off, All, Changes };

    void setMode(Mode m);
    Mode mode() const { return mode_; }

    void feed(const CanFrame &f, uint32_t nowMs);

    // Prints every ID seen, with its rate and latest bytes.
    void printTable();

    uint32_t dropped() const { return dropped_; }

private:
    static constexpr int MAX_IDS = 64;
    struct Entry {
        uint32_t id;
        uint32_t count;
        uint8_t  dlc;
        uint8_t  data[8];
    };

    Entry    table_[MAX_IDS];
    int      used_ = 0;
    Mode     mode_ = Mode::Off;
    uint32_t dropped_ = 0;
    uint32_t tableResetMs_ = 0;

    void print(const CanFrame &f, uint32_t nowMs);
};
