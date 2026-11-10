#include "link.h"
#include "config.h"
#include <string.h>

void TelemetryLink::begin() {
    Serial1.setRxBufferSize(1024);
    Serial1.begin(LINK_BAUD, SERIAL_8N1, PIN_LINK_RX, PIN_LINK_TX);
    rateWindowStart_ = millis();
}

// Samples a pin for 200 ms. Prints the share of time high and the number of edges.
static void probeOne(int pin, uint8_t mode, const char *modeName) {
    pinMode(pin, mode);
    delay(2);
    uint32_t samples = 0, highs = 0, edges = 0;
    int last = digitalRead(pin);
    const uint32_t start = millis();
    while (millis() - start < 200) {
        const int level = digitalRead(pin);
        samples++;
        if (level) highs++;
        if (level != last) edges++;
        last = level;
    }
    LOG_PRINTF("  GPIO%d %-8s high=%3lu%% edges=%lu\n", pin, modeName,
                  (unsigned long)(highs * 100 / samples), (unsigned long)edges);
}

void TelemetryLink::probePins() {
    Serial1.end();      // the RX pin becomes a plain input

    LOG_PRINTLN("Link pin test:");
    probeOne(PIN_LINK_RX, INPUT_PULLUP, "pull-up");
    probeOne(PIN_LINK_RX, INPUT_PULLDOWN, "pull-dn");

    Serial1.begin(LINK_BAUD, SERIAL_8N1, PIN_LINK_RX, PIN_LINK_TX);
}

bool TelemetryLink::alive() const {
    return stats_.lastFrameMs != 0 && (millis() - stats_.lastFrameMs) < LINK_TIMEOUT_MS;
}

void TelemetryLink::resetStats() {
    stats_ = LinkStats{};
    haveSeq_ = false;
    rateWindowCount_ = 0;
    rateWindowStart_ = millis();
}

void TelemetryLink::writeRaw(const uint8_t *data, size_t len) {
    Serial1.write(data, len);
}

bool TelemetryLink::poll(bool useUart) {
    bool gotNew = false;
    while (Serial1.available() > 0) {
        const uint8_t b = (uint8_t)Serial1.read();
        if (useUart && feed(b)) gotNew = true;
    }
    updateRate();
    return gotNew;
}

bool TelemetryLink::feed(uint8_t b) {
    switch (state_) {
    case State::Sync0:
        if (b == TELEM_SYNC0) state_ = State::Sync1;
        return false;

    case State::Sync1:
        if (b == TELEM_SYNC1) {
            state_ = State::Body;
            idx_ = 0;
        } else if (b != TELEM_SYNC0) {
            state_ = State::Sync0;
        }
        return false;

    case State::Body:
        buf_[idx_++] = b;
        if (idx_ < sizeof(buf_)) return false;

        state_ = State::Sync0;
        {
            const uint16_t rxCrc = (uint16_t)buf_[TELEM_PAYLOAD_LEN] |
                                   ((uint16_t)buf_[TELEM_PAYLOAD_LEN + 1] << 8);
            if (rxCrc == telem_crc16(buf_, TELEM_PAYLOAD_LEN) &&
                buf_[0] == TELEM_VERSION) {
                handleFrame();
                return true;
            }
        }
        stats_.crcErrors++;
        return false;
    }
    return false;
}

void TelemetryLink::updateRate() {
    const uint32_t now = millis();
    if (now - rateWindowStart_ >= 1000) {
        stats_.framesPerSec = rateWindowCount_ * 1000.0f / (now - rateWindowStart_);
        rateWindowCount_ = 0;
        rateWindowStart_ = now;
    }
}

void TelemetryLink::handleFrame() {
    TelemetryPayload p;
    memcpy(&p, buf_, sizeof(p));

    if (haveSeq_) {
        const uint8_t expected = (uint8_t)(lastSeq_ + 1);
        if (p.seq != expected) stats_.seqDrops += (uint8_t)(p.seq - expected);
    }
    haveSeq_ = true;
    lastSeq_ = p.seq;

    latest_ = p;
    stats_.framesOk++;
    stats_.lastFrameMs = millis();
    rateWindowCount_++;
}
