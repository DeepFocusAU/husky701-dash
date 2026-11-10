// sdlog.h - records every CAN frame to the SD card, in the background.
//
// Card writes can stall for a long time, so they run in their own task on the
// other core, behind a buffer. A slow or failed card only loses log lines: it
// never holds up CAN reception or the link to the dash.
#pragma once
#include <Arduino.h>
#include <freertos/FreeRTOS.h>
#include <freertos/stream_buffer.h>
#include "can_bus.h"

class SdLog {
public:
    enum class State : uint8_t {
        Starting,   // looking for the card
        NoCard,     // no usable card at startup: logging is off
        Logging,
        Failed      // was logging, then a write failed: card full or removed
    };

    void begin();

    // Queues one frame. Never blocks.
    void log(const CanFrame &f, uint32_t nowMs);

    // Queues a text line: "ms,tag,text". Never blocks. Used for GPS sentences.
    void logText(const char *tag, const char *text, uint32_t nowMs);

    State state() const { return state_; }
    const char *stateName() const;
    const char *fileName() const { return fileName_; }
    uint32_t kBytes() const { return bytes_ / 1024; }
    uint32_t dropped() const { return dropped_; }

private:
    StreamBufferHandle_t buf_ = nullptr;
    volatile State state_ = State::Starting;
    volatile uint32_t bytes_ = 0;
    volatile uint32_t dropped_ = 0;
    char fileName_[16] = "-";

    static void taskEntry(void *self);
    void run();
};
