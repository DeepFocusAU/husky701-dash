#include "ble_link.h"
#include "config.h"
#include <NimBLEDevice.h>

static NimBLEServer         *server  = nullptr;
static NimBLECharacteristic *frameCh = nullptr;
static NimBLECharacteristic *tapsCh  = nullptr;
static NimBLECharacteristic *modeCh  = nullptr;
static volatile bool simWanted = false;

namespace {
class ServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer *s, ble_gap_conn_desc *desc) override {
        // Short connection interval (7.5-15 ms units of 1.25 ms) so 25 frames/s fit easily.
        s->updateConnParams(desc->conn_handle, 6, 12, 0, 400);
        Serial.println("# BLE: phone connected");
    }
    void onDisconnect(NimBLEServer *) override {
        // NimBLE restarts advertising by itself after a disconnect.
        Serial.println("# BLE: phone disconnected");
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

void BleLink::sendFrame(const uint8_t *frame, size_t len) {
    if (!connected()) return;
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
