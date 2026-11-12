#include "ble_link.h"
#include "config.h"
#include <NimBLEDevice.h>
#include <esp_coexist.h>

static NimBLEServer         *server  = nullptr;
static NimBLECharacteristic *frameCh = nullptr;
static NimBLECharacteristic *tapsCh  = nullptr;
static NimBLECharacteristic *modeCh  = nullptr;
static NimBLECharacteristic *otaCh   = nullptr;
static volatile bool simWanted = false;
static volatile uint16_t connHandle = 0xFFFF;
static volatile bool quiet = false;     // update mode: slow link, Wi-Fi gets the radio

// Written by the BLE task, taken by loop(). The lock keeps the strings whole.
static portMUX_TYPE otaLock = portMUX_INITIALIZER_UNLOCKED;
static bool        otaPending = false;
static uint8_t     otaCmd = 0;
static std::string otaSsid, otaPass;

namespace {
class ServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer *s, ble_gap_conn_desc *desc) override {
        connHandle = desc->conn_handle;
        // Short connection interval (7.5-15 ms units of 1.25 ms) so 25 frames/s fit easily.
        // In update mode, slow (100-200 ms): the radio is shared and Wi-Fi needs the airtime.
        if (quiet) s->updateConnParams(desc->conn_handle, 80, 160, 0, 600);
        else       s->updateConnParams(desc->conn_handle, 6, 12, 0, 400);
        Serial.println("# BLE: phone connected");
    }
    void onDisconnect(NimBLEServer *) override {
        // NimBLE restarts advertising by itself after a disconnect.
        Serial.println("# BLE: phone disconnected");
        connHandle = 0xFFFF;
        // Never left on made-up data without the phone that asked for it.
        simWanted = false;
        if (modeCh) { const uint8_t v = 0; modeCh->setValue(&v, 1); }
    }
};

class ModeCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic *ch) override {
        const std::string v = ch->getValue();
        simWanted = !v.empty() && v[0] == 1;
    }
};

// [1][ssid len][ssid][pass len][pass] = start, [0] = stop. Anything malformed is ignored.
class OtaCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic *ch) override {
        const std::string v = ch->getValue();
        // never leave the written bytes (the Wi-Fi password) readable, whatever they were
        ch->setValue("received");
        Serial.printf("# update: message from phone, %u bytes\n", (unsigned)v.size());
        bool ok = !v.empty();
        std::string ssid, pass;
        if (ok && v[0] == 1) {
            const size_t sl = v.size() >= 2 ? (uint8_t)v[1] : 0;
            ok = sl >= 1 && sl <= 32 && v.size() >= 3 + sl;
            const size_t pl = ok ? (uint8_t)v[2 + sl] : 0;
            ok = ok && pl <= 63 && v.size() == 3 + sl + pl;
            if (ok) {
                ssid = v.substr(2, sl);
                pass = v.substr(3 + sl, pl);
            }
        } else if (ok && v[0] != 0) {
            ok = false;
        }
        if (!ok) {
            ch->setValue("failed: bad update message");
            ch->notify();
            return;
        }
        portENTER_CRITICAL(&otaLock);
        otaCmd = (uint8_t)v[0];
        otaSsid.swap(ssid);
        otaPass.swap(pass);
        otaPending = true;
        portEXIT_CRITICAL(&otaLock);
    }
};
}

bool BleLink::takeOtaCommand(uint8_t &cmd, String &ssid, String &pass) {
    std::string s, p;
    portENTER_CRITICAL(&otaLock);
    const bool had = otaPending;
    otaPending = false;
    cmd = otaCmd;
    s.swap(otaSsid);
    p.swap(otaPass);
    portEXIT_CRITICAL(&otaLock);
    if (!had) return false;
    ssid = s.c_str();
    pass = p.c_str();
    return true;
}

void BleLink::setOtaStatus(const String &status) {
    if (!otaCh) return;
    otaCh->setValue(status.c_str());
    if (connected()) otaCh->notify();
}

void BleLink::begin() {
    NimBLEDevice::init(BLE_DEVICE_NAME);
    NimBLEDevice::setPower(ESP_PWR_LVL_P9);
    NimBLEDevice::setMTU(247);

    server = NimBLEDevice::createServer();
    server->setCallbacks(new ServerCallbacks());

    NimBLEService *svc = server->createService(BLE_SERVICE_UUID);
    frameCh = svc->createCharacteristic(BLE_FRAME_UUID, NIMBLE_PROPERTY::NOTIFY);
    tapsCh  = svc->createCharacteristic(BLE_TAPS_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    const uint8_t none[2] = {0, 0};
    tapsCh->setValue(none, sizeof(none));
    modeCh = svc->createCharacteristic(BLE_MODE_UUID,
        NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    const uint8_t live = 0;
    modeCh->setValue(&live, 1);
    modeCh->setCallbacks(new ModeCallbacks());
    // Plain write, no pairing: the dash runs in kiosk mode, where Android can't show the pairing
    // prompt. The Wi-Fi details cross BLE unencrypted for that one write; uploads still need OTA_PASSWORD.
    otaCh = svc->createCharacteristic(BLE_OTA_UUID,
        NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY | NIMBLE_PROPERTY::WRITE);
    otaCh->setValue("off, fw " FW_VERSION);
    otaCh->setCallbacks(new OtaCallbacks());
    svc->start();

    NimBLEAdvertising *adv = NimBLEDevice::getAdvertising();
    adv->addServiceUUID(BLE_SERVICE_UUID);
    adv->setScanResponse(true);
    adv->start();
    Serial.printf("# BLE: advertising as \"%s\"\n", BLE_DEVICE_NAME);
}

bool BleLink::connected() const {
    return server && server->getConnectedCount() > 0;
}

bool BleLink::simRequested() const {
    return simWanted && connected();
}

void BleLink::setQuiet(bool on) {
    if (on == quiet) return;
    quiet = on;
    // The ESP32 has one 2.4 GHz radio for Bluetooth and Wi-Fi. With the fast telemetry link
    // Bluetooth wins nearly every slot and Wi-Fi can't even finish joining a network.
    esp_coex_preference_set(on ? ESP_COEX_PREFER_WIFI : ESP_COEX_PREFER_BALANCE);
    if (server && connHandle != 0xFFFF) {
        if (on) server->updateConnParams(connHandle, 80, 160, 0, 600);
        else    server->updateConnParams(connHandle, 6, 12, 0, 400);
    }
    Serial.printf("# BLE: %s\n", on ? "slow link, frames paused (update mode)" : "fast link, frames on");
}

void BleLink::sendFrame(const uint8_t *frame, size_t len) {
    if (!connected() || quiet) return;
    frameCh->setValue(frame, len);
    frameCh->notify();
    framesSent_++;
}

void BleLink::sendTaps(uint8_t taps) {
    if (!tapsCh) return;
    const uint8_t v[2] = {++tapSeq_, taps};
    tapsCh->setValue(v, sizeof(v));
    if (connected()) tapsCh->notify();
}
