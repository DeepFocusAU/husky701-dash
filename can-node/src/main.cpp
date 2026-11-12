// Husky701 CAN node - LilyGO T-CAN485.
// Bike CAN bus (listen-only) -> decode -> telemetry frames to the dash over RS-485.
//
// USB serial commands at 921600: s = status + ID table, r = log every frame,
// c = log only frames whose bytes changed, o = logging off, w = Wi-Fi networks the node can see.
#include <Arduino.h>
#include "config.h"
#include "telemetry_packet.h"
#include "can_bus.h"
#include "can_decode.h"
#include "sniffer.h"
#include "sdlog.h"
#include "gps.h"
#include "ble_link.h"
#include "taps.h"
#include "wifi_ota.h"

#include "selftest.h"

static CanBus     can;
static CanDecoder decoder;
static Sniffer    sniffer;
static SdLog      sdLog;
static Gps        gps;
#if BLE_ENABLE
static BleLink    ble;
#endif

static uint8_t  seq = 0;
static uint32_t linkFrames = 0;
static uint16_t lastVbat = TELEM_VBAT_UNKNOWN;
static uint16_t lastVbatPinMv = 0;   // raw IO34 reading, for the status line
static char     ledStatus = 'R';    // R, A or G: shown on the LED and in the status line

// SIM: made-up CAN frames instead of the bike's. The selftest build is always SIM; the
// real build only while the phone asks for it (see BleLink), so it powers up LIVE.
static bool simMode() {
#ifdef SELFTEST
    return true;
#elif BLE_ENABLE
    return ble.simRequested();
#else
    return false;
#endif
}
static bool sim = false;            // simMode() as of this loop

static void handleFrame(const CanFrame &f) {
    const uint32_t t = millis();
    decoder.feed(f.id, f.dlc, f.data, t);
    sniffer.feed(f, t);
    sdLog.log(f, t);
}

static uint16_t readVbatMv() {
    if (sim) return 13800;
    uint32_t sum = 0;
    for (int i = 0; i < VBAT_SAMPLES; i++) sum += analogReadMilliVolts(PIN_VBAT);
    const float pinMv = (float)sum / VBAT_SAMPLES;
    lastVbatPinMv = (uint16_t)pinMv;
    const float mv = pinMv * (VBAT_R_TOP + VBAT_R_BOTTOM) / VBAT_R_BOTTOM * VBAT_CAL;
    if (mv < VBAT_MIN_VALID_MV) return TELEM_VBAT_UNKNOWN;
    return (uint16_t)min(mv, 65000.0f);
}

static void sendTelemetry(uint32_t now) {
    TelemetryPayload p;
    decoder.fill(p, now);
    p.seq = seq++;
    p.reserved = sim ? TELEM_RES_NODE_SIM : 0;
    lastVbat = readVbatMv();
    p.vbat_mV = lastVbat;
    const bool fix = gps.hasTime();
    if (fix) {
        p.clock_hour = gps.localHour();
        p.clock_min  = gps.localMinute();
    }
    p.gps_lat_e7     = fix ? gps.latE7() : 0;
    p.gps_lon_e7     = fix ? gps.lonE7() : 0;
    p.gps_speed_dkmh = fix ? (uint16_t)lroundf(gps.kmh() * 10.0f) : 0;
    p.gps_course_dd  = fix ? (uint16_t)lroundf(gps.courseDeg() * 10.0f) : 0;
    p.gps_sats       = gps.satellites();
    p.gps_fix        = fix ? 1 : 0;
    p.gps_day        = fix ? gps.localDay() : 0;
    p.gps_alt_m      = fix ? gps.altitudeM() : 0;
    p.gps_hdop_dx10  = fix ? gps.hdopX10() : 0;
    p.gps_reserved   = 0;

    const uint16_t crc = telem_crc16((const uint8_t *)&p, TELEM_PAYLOAD_LEN);
    uint8_t frame[TELEM_FRAME_LEN];
    frame[0] = TELEM_SYNC0;
    frame[1] = TELEM_SYNC1;
    memcpy(&frame[2], &p, TELEM_PAYLOAD_LEN);
    frame[2 + TELEM_PAYLOAD_LEN] = (uint8_t)(crc & 0xFF);
    frame[3 + TELEM_PAYLOAD_LEN] = (uint8_t)(crc >> 8);

    Serial1.write(frame, sizeof(frame));
    linkFrames++;

#if BLE_ENABLE
    static uint8_t bleDiv = 0;
    if (++bleDiv >= BLE_FRAME_DIV) {
        bleDiv = 0;
        ble.sendFrame(frame, sizeof(frame));
    }
#endif
}

static void printStatus(uint32_t now) {
    TelemetryPayload p;
    decoder.fill(p, now);
    const CanStats &c = can.stats();
    Serial.printf("# CAN %s  frames=%lu missed=%lu overrun=%lu buserr=%lu | "
                  "rpm=%u gear=%u thr=%u coolant=%.1f front=%u rear=%u flags=0x%02X vbat=%umV (IO34 %umV) | "
                  "link=%lu logdrop=%lu led=%c\n",
                  (p.flags & TELEM_FLAG_CAN_OK) ? "OK" : "NO DATA",
                  (unsigned long)c.frames, (unsigned long)c.missed, (unsigned long)c.overruns,
                  (unsigned long)c.rxErrors, p.rpm, p.gear, p.throttle, p.coolant_dC / 10.0f,
                  p.wheel_front, p.wheel_rear, p.flags, lastVbat, lastVbatPinMv, (unsigned long)linkFrames,
                  (unsigned long)sniffer.dropped(), ledStatus);
    // TEMP: which analog header pin has the battery divider on it?
    Serial.printf("# ADC  IO33=%umV IO34=%umV IO35=%umV IO36=%umV IO39=%umV\n",
                  analogReadMilliVolts(33), analogReadMilliVolts(34), analogReadMilliVolts(35),
                  analogReadMilliVolts(36), analogReadMilliVolts(39));
    Serial.printf("# SD %s  file=%s size=%lukB dropped=%lu\n", sdLog.stateName(), sdLog.fileName(),
                  (unsigned long)sdLog.kBytes(), (unsigned long)sdLog.dropped());
    Serial.printf("# GPS %s  sats=%u time=%02u:%02u speed=%.1fkm/h\n", gps.statusName(), gps.satellites(),
                  gps.localHour(), gps.localMinute(), gps.kmh());
#if BLE_ENABLE
    Serial.printf("# BLE %s  sent=%lu  mode=%s  fw=%s\n", ble.connected() ? "PHONE CONNECTED" : "advertising",
                  (unsigned long)ble.framesSent(), sim ? "SIM" : "LIVE", FW_VERSION);
    if (wifiOta.state() != WifiOta::State::Off) Serial.printf("# UPDATE %s\n", wifiOta.status().c_str());
#endif
}

#if BLE_ENABLE
// Update mode (wifi_ota.h): started and stopped from the phone, only with the engine off.
static bool engineRunning(uint32_t now) {
    if (sim) return false;              // made-up rpm doesn't count
    TelemetryPayload p;
    decoder.fill(p, now);
    return p.rpm > 0;
}

static void serviceUpdates(uint32_t now) {
    uint8_t cmd;
    String ssid, pass;
    if (ble.takeOtaCommand(cmd, ssid, pass)) {
        if (cmd == 0) wifiOta.stop("off");
        else if (engineRunning(now)) wifiOta.stop("refused: engine running");
        else {
            ble.setQuiet(true);             // radio to Wi-Fi first, or Wi-Fi start-up crawls
            delay(500);                     // engine is off: let the phone take the slower link
            wifiOta.start(ssid.c_str(), pass.c_str());
        }
    }
    if (wifiOta.active() && wifiOta.state() != WifiOta::State::Updating && engineRunning(now))
        wifiOta.stop("off: engine started");
    wifiOta.poll(now);
    ble.setQuiet(wifiOta.active());

    static uint32_t sentSeq = 0;
    if (wifiOta.statusSeq() != sentSeq) {
        sentSeq = wifiOta.statusSeq();
        ble.setOtaStatus(wifiOta.status());
    }
}
#endif

// Wiring check: for 60 s, drives the RS-485 pair low for 3 s (LED red) then releases
// it for 3 s (LED off), so it can be followed on a meter or by the dash's "linktest".
// Everything else pauses, so it only runs on request.
static void holdLinkLow() {
    Serial.println("# RS-485 line test: 3 s low (LED red), 3 s released (LED off), for 60 s");
    Serial1.end();
    pinMode(PIN_RS485_TX, OUTPUT);
    for (int i = 0; i < 10; i++) {
        digitalWrite(PIN_RS485_TX, LOW);
        neopixelWrite(PIN_LED, 20, 0, 0);
        delay(3000);
        digitalWrite(PIN_RS485_TX, HIGH);
        neopixelWrite(PIN_LED, 0, 0, 0);
        delay(3000);
    }
    Serial1.begin(LINK_BAUD, SERIAL_8N1, PIN_RS485_RX, PIN_RS485_TX);
    Serial.println("# RS-485 line test finished");
}

static void handleCommands(uint32_t now) {
    while (Serial.available() > 0) {
        switch (Serial.read()) {
        case 'h': holdLinkLow(); break;
        case 's': printStatus(now); sniffer.printTable(); break;
        case 'r': sniffer.setMode(Sniffer::Mode::All); break;
        case 'c': sniffer.setMode(Sniffer::Mode::Changes); break;
        case 'o': sniffer.setMode(Sniffer::Mode::Off); break;
#if BLE_ENABLE
        case 'w': wifiOta.scan(); break;
#endif
        default: break;
        }
    }
}

// Red = no bike data. Amber = running, but something is missing or going wrong.
// Green = everything the dash needs is good. Call once a second.
static char workOutStatus(uint32_t now) {
    const CanStats &c = can.stats();

    static uint32_t lastFaults = 0;
    const uint32_t faults = c.missed + c.overruns + c.rxErrors;
    const bool newFaults = faults != lastFaults;
    lastFaults = faults;

    if (!sim && !c.running) return 'R';
    if (!decoder.busAlive(now)) return 'R';
    if (newFaults || !decoder.allSignalsFresh(now) || lastVbat == TELEM_VBAT_UNKNOWN) return 'A';
    if (sdLog.state() == SdLog::State::Failed || sdLog.state() == SdLog::State::NoCard) return 'A';
    return 'G';
}

static void updateLed(uint32_t now) {
    ledStatus = workOutStatus(now);

    if (sim) {
        // Flashes, so a green from made-up data can't be mistaken for a real one.
        static bool on = false;
        on = !on;
        if (!on) {
            neopixelWrite(PIN_LED, 0, 0, 0);
            return;
        }
    }

    switch (ledStatus) {
    case 'G': neopixelWrite(PIN_LED, 0, 20, 0); break;
    case 'A': neopixelWrite(PIN_LED, 20, 7, 0); break;
    default:  neopixelWrite(PIN_LED, 20, 0, 0); break;
    }
}

void setup() {
    CanBus::holdTransceiverSilent();

    // The MAX13487E runs from the boost's 5 V.
    pinMode(PIN_BOOST_EN, OUTPUT);
    digitalWrite(PIN_BOOST_EN, HIGH);
    pinMode(PIN_RS485_RE, OUTPUT);
    digitalWrite(PIN_RS485_RE, HIGH);
    pinMode(PIN_RS485_SHDN, OUTPUT);
    digitalWrite(PIN_RS485_SHDN, HIGH);

    Serial.setTxBufferSize(8192);       // holds log lines so printing doesn't block
    Serial.begin(921600);

    Serial1.begin(LINK_BAUD, SERIAL_8N1, PIN_RS485_RX, PIN_RS485_TX);

    analogReadResolution(12);
    analogSetPinAttenuation(PIN_VBAT, ADC_11db);

    sdLog.begin();
    gps.begin();
    tapsBegin();
#if BLE_ENABLE
    ble.begin();
#endif
    Serial.printf("# firmware %s, built %s %s\n", FW_VERSION, __DATE__, __TIME__);

#ifdef SELFTEST
    Serial.println("# Husky701 CAN node: SELFTEST, CAN bus not used");
#else
    Serial.println(can.begin() ? "# Husky701 CAN node: listen-only, 500 kbit/s"
                               : "# Husky701 CAN node: CAN START FAILED, retrying");
#endif
}

void loop() {
    const bool wantSim = simMode();
    if (wantSim != sim) {
        // start clean, so nothing from the other source is shown as fresh
        sim = wantSim;
        decoder = CanDecoder();
        Serial.println(sim ? "# mode: SIM (made-up data, asked for by the phone)" : "# mode: LIVE");
    }

#ifndef SELFTEST
    CanFrame f;
    while (can.read(f)) if (!sim) handleFrame(f);      // still drained in SIM
#endif

    gps.poll(sdLog);

    const uint32_t now = millis();

    if (sim) selftestFeed(handleFrame, now);

    // The dash link runs on a fixed schedule, and also runs with no CAN traffic
    // so the dash still gets battery voltage and the CAN NO DATA flag.
    static uint32_t nextFrame = 0;
    if ((int32_t)(now - nextFrame) >= 0) {
        sendTelemetry(now);
        nextFrame += LINK_FRAME_MS;
        if ((int32_t)(now - nextFrame) >= 0) nextFrame = now + LINK_FRAME_MS;
    }

    // The MAX13487E can echo our own frames back on RX, and nothing here uses them.
    while (Serial1.available() > 0) Serial1.read();

    static uint32_t lastSlow = 0;
    if (now - lastSlow >= 1000) {
        lastSlow = now;
#ifndef SELFTEST
        can.service();
#endif
        updateLed(now);

        static uint8_t statusEvery = 0;
        if (sniffer.mode() == Sniffer::Mode::Off && ++statusEvery >= 5) {
            statusEvery = 0;
            printStatus(now);
        }
    }

    const int taps = tapsPoll();
#if BLE_ENABLE
    if (taps) ble.sendTaps((uint8_t)taps);
    serviceUpdates(now);
#else
    (void)taps;
#endif

    handleCommands(now);
}
