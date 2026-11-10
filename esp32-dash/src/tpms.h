// tpms.h - Bluetooth valve-cap tyre pressure sensors (passive BLE scan).
//
// Decoders follow bkbilly/tpms_ble (Home Assistant integration):
//   FOBO Bike 2 / Salutica   company id 295 (0x0127), or service data 0x012A  <- recommended
//   TPMSII ("Type A")        company id 256, 16-byte payload
//   WODHMIEY / ITPMS ("D")   company id 172
//   SYTPMS ("Type B")        service UUID 0x27A5
// Pressures are gauge, in bar. Sensors send more often while the wheel turns;
// parked, an update can take minutes, so each reading carries its age.
//
// Pairing (Tyres page): sensors seen nearby are listed by signal strength;
// pick one for FRONT, then REAR. The choice is saved to flash.
#pragma once
#include <Arduino.h>

struct TyreReading {
    bool     paired   = false;
    bool     valid    = false;   // a reading has arrived since start-up
    float    bar      = 0;
    int8_t   tempC    = 0;
    float    battV    = 0;       // 0 = not reported
    uint32_t ageS     = 0;
    int8_t   rssi     = 0;
    char     tag[6]   = "";      // last 2 MAC bytes, e.g. "3F:A1"
};

struct SeenSensor {
    char     tag[6]   = "";
    char     type[8]  = "";
    float    bar      = 0;
    int8_t   rssi     = -127;
    uint32_t ageS     = 0;
};

struct TyreView {
    TyreReading front, rear;
    // pairing UI
    bool       pairing   = false;
    uint8_t    pairStep  = 0;      // 0 = choosing FRONT, 1 = choosing REAR
    uint8_t    pairSel   = 0;
    uint8_t    seenCount = 0;
    SeenSensor seen[4];            // strongest first
};

class TpmsModel {
public:
    void begin();
    void update();                 // ages, timeouts (call often)

    TyreView view() const;

    // pairing controls
    void pairOpen();
    void pairCancel()  { pairing_ = false; }
    bool pairing() const { return pairing_; }
    void pairNextCandidate();
    void pairSkip();               // keep the current sensor for this wheel
    void pairAssign();             // assign the highlighted sensor, move on / save

    // called from the BLE callback
    void onSensor(const uint8_t mac[6], const char *type, float bar, int tempC,
                  float battV, int rssi);

private:
    struct Slot {
        uint8_t  mac[6];
        char     type[8];
        float    bar;
        int8_t   tempC, rssi;
        float    battV;
        uint32_t lastMs;
        bool     used;
    };
    static const int MAX_SLOTS = 8;
    Slot     slots_[MAX_SLOTS] = {};
    uint8_t  frontMac_[6] = {}, rearMac_[6] = {};
    bool     haveFront_ = false, haveRear_ = false;

    bool     pairing_ = false;
    uint8_t  pairStep_ = 0, pairSel_ = 0;
    uint32_t pairMs_ = 0;

    void sortedSeen(int *idx, int &n) const;
    void fillReading(TyreReading &r, bool have, const uint8_t mac[6]) const;
    void saveMacs();
};

extern TpmsModel tpms;
