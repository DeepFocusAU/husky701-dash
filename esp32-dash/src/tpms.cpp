#include "tpms.h"
#include "config.h"
#include <NimBLEDevice.h>
#include <Preferences.h>
#include <string.h>

TpmsModel tpms;

static Preferences tpPrefs;
static portMUX_TYPE tpMux = portMUX_INITIALIZER_UNLOCKED;   // BLE task vs loop()

static void macTag(const uint8_t mac[6], char out[6]) {
    // NimBLE native addresses are little-endian: [0] is the last byte shown
    snprintf(out, 6, "%02X:%02X", mac[1], mac[0]);
}

// ------------------------------------------------------------ decoders

static float batteryFromCode(uint8_t code) { return code * 0.1f; }

class TpmsScanCb : public NimBLEAdvertisedDeviceCallbacks {
    void onResult(NimBLEAdvertisedDevice *d) override {
        const uint8_t *mac = d->getAddress().getNative();
        const int rssi = d->getRSSI();

        // FOBO, US/iBeacon variant: service data under UUID 0x012A, temp offset 40
        static const NimBLEUUID foboSvc((uint16_t)0x012A);
        std::string sd = d->getServiceData(foboSvc);
        if (sd.size() >= 4) {
            const uint8_t *p = (const uint8_t *)sd.data() + sd.size() - 4;
            const uint16_t raw = (p[2] << 8) | p[3];
            tpms.onSensor(mac, "FOBO", (raw & 1023) * 0.01f, (p[1] & 0x7F) - 40,
                          batteryFromCode((raw >> 10) & 0x1F), rssi);
            return;
        }

        if (d->getManufacturerDataCount() == 0) return;
        const std::string md = d->getManufacturerData();
        if (md.size() < 3) return;
        const uint8_t *m = (const uint8_t *)md.data();
        const uint16_t company = m[0] | (m[1] << 8);
        const uint8_t *p = m + 2;
        const size_t n = md.size() - 2;

        // SYTPMS (Type B): service 0x27A5; the "company id" carries battery voltage
        static const NimBLEUUID syt((uint16_t)0x27A5);
        if (d->isAdvertisingService(syt) && n == 5) {
            const float psi = (((p[1] << 8) | p[2]) - 145) / 10.0f;
            tpms.onSensor(mac, "SYTPMS", psi * 0.0689476f, (int8_t)p[0],
                          ((company >> 8) & 0xFF) / 10.0f, rssi);
            return;
        }

        if (company == 295 && n >= 4) {                     // FOBO Bike 2 (Salutica)
            const uint8_t *q = p + n - 4;
            const uint16_t raw = (q[2] << 8) | q[3];
            tpms.onSensor(mac, "FOBO", (raw & 1023) * 0.01f, (q[1] & 0x7F) - 50,
                          batteryFromCode((raw >> 10) & 0x1F), rssi);
        } else if (company == 256 && n == 16) {             // TPMSII (Type A)
            int32_t pa, t100;
            memcpy(&pa, p + 6, 4);
            memcpy(&t100, p + 10, 4);
            tpms.onSensor(mac, "TPMSII", pa / 100000.0f, t100 / 100, 0, rssi);
        } else if (company == 172 && n >= 7) {              // WODHMIEY / ITPMS (Type D)
            const int raw = p[1] + (p[6] == 1 ? 256 : 0);
            tpms.onSensor(mac, "ITPMS", raw * 0.03144f, p[2] - 55, p[0] * 0.01f + 1.22f, rssi);
        }
    }
};

static TpmsScanCb scanCb;

// ------------------------------------------------------------ model

void TpmsModel::begin() {
    tpPrefs.begin("tpms", false);
    haveFront_ = tpPrefs.getBytes("front", frontMac_, 6) == 6;
    haveRear_  = tpPrefs.getBytes("rear", rearMac_, 6) == 6;

#if TPMS_ENABLE
    NimBLEDevice::init("");
    NimBLEScan *scan = NimBLEDevice::getScan();
    scan->setAdvertisedDeviceCallbacks(&scanCb, true);   // want every advert
    scan->setDuplicateFilter(false);
    scan->setActiveScan(false);                          // passive: listen only
    scan->setInterval(160);
    scan->setWindow(80);                                 // 50 % duty
    scan->setMaxResults(0);                              // don't keep a list
    scan->start(0, nullptr, false);                      // forever
#endif
}

void TpmsModel::onSensor(const uint8_t mac[6], const char *type, float bar, int tempC,
                         float battV, int rssi) {
    if (bar < 0 || bar > 6.0f) return;                    // implausible
    portENTER_CRITICAL(&tpMux);
    // existing entry for this sensor, else an empty slot, else the oldest
    int slot = -1;
    for (int i = 0; i < MAX_SLOTS && slot < 0; i++)
        if (slots_[i].used && memcmp(slots_[i].mac, mac, 6) == 0) slot = i;
    for (int i = 0; i < MAX_SLOTS && slot < 0; i++)
        if (!slots_[i].used) slot = i;
    if (slot < 0) {
        slot = 0;
        for (int i = 1; i < MAX_SLOTS; i++)
            if (slots_[i].lastMs < slots_[slot].lastMs) slot = i;
    }
    Slot &s = slots_[slot];
    memcpy(s.mac, mac, 6);
    strncpy(s.type, type, sizeof(s.type) - 1);
    s.type[sizeof(s.type) - 1] = 0;
    s.bar = bar;
    s.tempC = (int8_t)constrain(tempC, -40, 125);
    s.battV = battV;
    s.rssi = (int8_t)constrain(rssi, -127, 0);
    s.lastMs = millis();
    s.used = true;
    portEXIT_CRITICAL(&tpMux);
}

void TpmsModel::update() {
    if (pairing_ && millis() - pairMs_ > TPMS_PAIR_TIMEOUT_MS) pairing_ = false;
}

void TpmsModel::sortedSeen(int *idx, int &n) const {
    n = 0;
    const uint32_t now = millis();
    for (int i = 0; i < MAX_SLOTS; i++)
        if (slots_[i].used && now - slots_[i].lastMs < 120000) idx[n++] = i;
    // strongest signal first (simple insertion sort, n <= 8)
    for (int a = 1; a < n; a++)
        for (int b = a; b > 0 && slots_[idx[b]].rssi > slots_[idx[b - 1]].rssi; b--) {
            int t = idx[b]; idx[b] = idx[b - 1]; idx[b - 1] = t;
        }
}

void TpmsModel::fillReading(TyreReading &r, bool have, const uint8_t mac[6]) const {
    r.paired = have;
    if (!have) return;
    char tag[6];
    macTag(mac, tag);
    memcpy(r.tag, tag, 6);
    for (int i = 0; i < MAX_SLOTS; i++) {
        const Slot &s = slots_[i];
        if (s.used && memcmp(s.mac, mac, 6) == 0) {
            r.valid = true;
            r.bar = s.bar;
            r.tempC = s.tempC;
            r.battV = s.battV;
            r.rssi = s.rssi;
            r.ageS = (millis() - s.lastMs) / 1000;
            return;
        }
    }
}

TyreView TpmsModel::view() const {
    TyreView v;
    portENTER_CRITICAL(&tpMux);
    fillReading(v.front, haveFront_, frontMac_);
    fillReading(v.rear, haveRear_, rearMac_);
    int idx[MAX_SLOTS], n;
    sortedSeen(idx, n);
    v.seenCount = (uint8_t)min(n, 4);
    for (int k = 0; k < v.seenCount; k++) {
        const Slot &s = slots_[idx[k]];
        macTag(s.mac, v.seen[k].tag);
        strncpy(v.seen[k].type, s.type, sizeof(v.seen[k].type) - 1);
        v.seen[k].bar = s.bar;
        v.seen[k].rssi = s.rssi;
        v.seen[k].ageS = (millis() - s.lastMs) / 1000;
    }
    portEXIT_CRITICAL(&tpMux);
    v.pairing = pairing_;
    v.pairStep = pairStep_;
    v.pairSel = pairSel_;
    return v;
}

// ------------------------------------------------------------ pairing

void TpmsModel::pairOpen() {
    pairing_ = true;
    pairStep_ = 0;
    pairSel_ = 0;
    pairMs_ = millis();
}

void TpmsModel::pairNextCandidate() {
    const TyreView v = view();
    pairSel_ = v.seenCount ? (pairSel_ + 1) % v.seenCount : 0;
    pairMs_ = millis();
}

void TpmsModel::pairSkip() {
    pairMs_ = millis();
    if (pairStep_ == 0) { pairStep_ = 1; pairSel_ = 0; }
    else pairing_ = false;
}

void TpmsModel::pairAssign() {
    pairMs_ = millis();
    int idx[MAX_SLOTS], n;
    portENTER_CRITICAL(&tpMux);
    sortedSeen(idx, n);
    bool ok = n > 0 && pairSel_ < n;
    uint8_t mac[6] = {};
    if (ok) memcpy(mac, slots_[idx[pairSel_]].mac, 6);
    portEXIT_CRITICAL(&tpMux);
    if (!ok) return;

    if (pairStep_ == 0) {
        memcpy(frontMac_, mac, 6); haveFront_ = true;
        pairStep_ = 1; pairSel_ = 0;
    } else {
        memcpy(rearMac_, mac, 6); haveRear_ = true;
        pairing_ = false;
    }
    saveMacs();
}

void TpmsModel::saveMacs() {
    if (haveFront_) tpPrefs.putBytes("front", frontMac_, 6);
    if (haveRear_)  tpPrefs.putBytes("rear", rearMac_, 6);
}
