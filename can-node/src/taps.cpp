#include "taps.h"
#include "config.h"

static portMUX_TYPE      tapMux = portMUX_INITIALIZER_UNLOCKED;
static volatile uint8_t  tapCount = 0;
static volatile uint32_t tapLastEdgeMs = 0;
static volatile bool     tapLineLow = false;

// Counts a falling edge once the line has been still for TAP_QUIET_MS. A rising
// edge with no falling edge seen before it (missed while interrupts were held off)
// counts too.
static void ARDUINO_ISR_ATTR onTapEdge() {
    portENTER_CRITICAL_ISR(&tapMux);
    const uint32_t now = millis();
    const bool low = digitalRead(PIN_TAP_SWITCH) == LOW;
    if ((low || !tapLineLow) && now - tapLastEdgeMs >= TAP_QUIET_MS && tapCount < 255) tapCount++;
    tapLineLow = low;
    tapLastEdgeMs = now;
    portEXIT_CRITICAL_ISR(&tapMux);
}

void tapsBegin() {
    pinMode(PIN_TAP_SWITCH, INPUT_PULLUP);
    tapLineLow = digitalRead(PIN_TAP_SWITCH) == LOW;
    attachInterrupt(digitalPinToInterrupt(PIN_TAP_SWITCH), onTapEdge, CHANGE);
}

int tapsPoll() {
    int taps = 0;
    portENTER_CRITICAL(&tapMux);
    if (tapCount > 0 && millis() - tapLastEdgeMs >= TAP_GAP_MS) {
        taps = tapCount;
        tapCount = 0;
    }
    portEXIT_CRITICAL(&tapMux);
    if (taps > 0) Serial.printf("# taps %d\n", taps);
    return taps <= TAP_MAX ? taps : 0;
}
