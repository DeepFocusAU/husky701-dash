#include "selftest.h"

static void send(void (*sink)(const CanFrame &), uint32_t id, const uint8_t (&data)[8]) {
    CanFrame f;
    f.id = id;
    f.dlc = 8;
    memcpy(f.data, data, 8);
    sink(f);
}

// A 20 s repeating sweep in the bike's real byte layout, so the decoder and SD log
// get used too: rpm 0..8000, gear N,1..6, clutch in for the first second of each
// gear, coolant 60..110 degC, side stand up, kill switch on run.
void selftestFeed(void (*sink)(const CanFrame &), uint32_t now) {
    static uint32_t last = 0;
    if (now - last < 20) return;
    last = now;

    static constexpr uint32_t CYCLE_MS = 20000;
    static constexpr uint32_t GEAR_MS  = CYCLE_MS / 7 + 1;

    const uint32_t t = now % CYCLE_MS;
    const uint16_t rpm = (uint16_t)(t * 8000 / CYCLE_MS);
    const uint8_t  gear = (uint8_t)(t / GEAR_MS);
    const bool     clutch = (t % GEAR_MS) < 1000;
    const uint16_t coolant = (uint16_t)(600 + t * 500 / CYCLE_MS);
    const uint16_t wheel = (uint16_t)(rpm / 3);
    const uint8_t  throttle = (uint8_t)(t * 255 / CYCLE_MS);

    const uint8_t f120[8] = {(uint8_t)(rpm >> 8), (uint8_t)rpm, throttle, 0x08, 0, 0, 0, 0};
    send(sink, 0x120, f120);

    const uint8_t f128[8] = {0x13, 0x88, (uint8_t)(throttle < 3 ? 0x03 : 0x02), 0, 0, 0, 0, 0};
    send(sink, 0x128, f128);

    const uint8_t f129[8] = {(uint8_t)((gear << 4) | (clutch ? 0x08 : 0)), 0, 0, 0, 0, 0, 0, 0};
    send(sink, 0x129, f129);

    const uint8_t f12D[8] = {(uint8_t)(wheel >> 8), (uint8_t)wheel,
                             (uint8_t)(wheel >> 8), (uint8_t)wheel, 0, 0, 0, 0};
    send(sink, 0x12D, f12D);

    const uint8_t f540[8] = {0, 0, 0x10, 0x01, 0x82, (uint8_t)(coolant >> 8), (uint8_t)coolant, 0};
    send(sink, 0x540, f540);
}
