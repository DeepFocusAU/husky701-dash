#include "can_bus.h"
#include "config.h"
#include "driver/twai.h"

// The bike's ABS and ECU share this bus, so this node must never drive it. There
// are two independent layers, and either one alone keeps the node silent:
//
//  1. TWAI_MODE_LISTEN_ONLY: the controller never transmits or sends an ACK.
//  2. The controller's TX signal is routed to a spare pin, and the transceiver's
//     D pin is held HIGH (recessive) as a plain GPIO.
//
// The SN65HVD231 has no hardware listen mode. Driving Rs/SE high puts it to sleep
// with the receiver off, so SE stays LOW.

void CanBus::holdTransceiverSilent() {
    pinMode(PIN_CAN_TX, OUTPUT);
    digitalWrite(PIN_CAN_TX, HIGH);
    pinMode(PIN_CAN_SE, OUTPUT);
    digitalWrite(PIN_CAN_SE, LOW);
}

bool CanBus::begin() {
    twai_general_config_t g = TWAI_GENERAL_CONFIG_DEFAULT(
        (gpio_num_t)PIN_TWAI_TX_PARKED, (gpio_num_t)PIN_CAN_RX, TWAI_MODE_LISTEN_ONLY);
    g.rx_queue_len = 64;    // the bus runs at ~1100 frames/s; this covers a stall of ~50 ms
    g.tx_queue_len = 0;     // transmit is disabled
    const twai_timing_config_t t = TWAI_TIMING_CONFIG_500KBITS();
    const twai_filter_config_t f = TWAI_FILTER_CONFIG_ACCEPT_ALL();  // the raw log needs every ID

    if (twai_driver_install(&g, &t, &f) != ESP_OK) return false;
    installed_ = true;
    stats_.running = (twai_start() == ESP_OK);
    return stats_.running;
}

bool CanBus::read(CanFrame &out) {
    if (!stats_.running) return false;

    twai_message_t m;
    while (twai_receive(&m, 0) == ESP_OK) {
        if (m.extd || m.rtr) continue;      // the bike uses standard data frames
        out.id  = m.identifier;
        out.dlc = m.data_length_code > 8 ? 8 : m.data_length_code;
        memcpy(out.data, m.data, out.dlc);
        stats_.frames++;
        return true;
    }
    return false;
}

void CanBus::service() {
    if (!installed_) {
        begin();
        return;
    }

    twai_status_info_t s;
    if (twai_get_status_info(&s) != ESP_OK) return;

    stats_.missed   = s.rx_missed_count;
    stats_.overruns = s.rx_overrun_count;
    stats_.rxErrors = s.bus_error_count;

    if (s.state == TWAI_STATE_BUS_OFF) {
        twai_initiate_recovery();
        stats_.running = false;
    } else if (s.state == TWAI_STATE_STOPPED) {
        stats_.running = (twai_start() == ESP_OK);
    } else if (s.state == TWAI_STATE_RUNNING) {
        stats_.running = true;
    }
}
