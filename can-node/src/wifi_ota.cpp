#include "wifi_ota.h"
#include "config.h"
#include <WiFi.h>
#include <ArduinoOTA.h>

WifiOta wifiOta;

// Why the last join attempt was dropped (wifi_err_reason_t), from the Wi-Fi task.
static volatile uint8_t lastReason = 0;

static void onWifiEvent(WiFiEvent_t event, WiFiEventInfo_t info) {
    switch (event) {
    case ARDUINO_EVENT_WIFI_STA_START:
        Serial.println("# update: Wi-Fi started");
        break;
    case ARDUINO_EVENT_WIFI_STA_CONNECTED:
        lastReason = 255;                   // joined, waiting for an address
        Serial.printf("# update: Wi-Fi joined, channel %u, waiting for an IP address\n",
                      (unsigned)info.wifi_sta_connected.channel);
        break;
    case ARDUINO_EVENT_WIFI_STA_GOT_IP:
        Serial.println("# update: Wi-Fi got an IP address");
        break;
    case ARDUINO_EVENT_WIFI_STA_DISCONNECTED:
        lastReason = info.wifi_sta_disconnected.reason;
        Serial.printf("# update: Wi-Fi dropped, reason %u\n", (unsigned)lastReason);
        break;
    default:
        break;
    }
}

static String joinFailure() {
    switch (lastReason) {
    case WIFI_REASON_NO_AP_FOUND:          return "failed: network not found (2.4 GHz only)";
    case WIFI_REASON_AUTH_FAIL:
    case WIFI_REASON_4WAY_HANDSHAKE_TIMEOUT:
    case WIFI_REASON_HANDSHAKE_TIMEOUT:    return "failed: wrong Wi-Fi password?";
    case WIFI_REASON_ASSOC_FAIL:
    case WIFI_REASON_AUTH_EXPIRE:          return "failed: router refused the node";
    case 255:                              return "failed: joined Wi-Fi but got no IP address";
    case 0:                                return String("failed: couldn't join Wi-Fi (no answer, status ") + (int)WiFi.status() + ")";
    default:                               return String("failed: couldn't join Wi-Fi (reason ") + lastReason + ")";
    }
}

void WifiOta::setStatus(const String &s) {
    if (s == status_) return;
    status_ = s;
    statusSeq_++;
    Serial.printf("# update: %s\n", s.c_str());
}

bool WifiOta::start(const char *ssid, const char *password) {
    if (active()) stop("restarting");
    if (strlen(OTA_PASSWORD) == 0) {
        state_ = State::Failed;
        setStatus("failed: no OTA password in this firmware (secrets.ini)");
        return false;
    }
    if (!ssid || !*ssid) {
        state_ = State::Failed;
        setStatus("failed: no Wi-Fi name");
        return false;
    }

    const uint32_t t0 = millis();
    WiFi.persistent(false);                 // never write the credentials to flash
    WiFi.mode(WIFI_STA);
    WiFi.setHostname(OTA_HOSTNAME);
    WiFi.setSleep(true);                    // modem sleep: needed with Bluetooth running
    // Mesh routers have several access points with one name: pick the strongest, not the first.
    WiFi.setScanMethod(WIFI_ALL_CHANNEL_SCAN);
    WiFi.setSortMethod(WIFI_CONNECT_AP_BY_SIGNAL);
    static bool hooked = false;
    if (!hooked) { WiFi.onEvent(onWifiEvent); hooked = true; }
    lastReason = 0;
    const wl_status_t began = WiFi.begin(ssid, password);
    Serial.printf("# update: Wi-Fi begin returned %d after %lu ms\n", (int)began, (unsigned long)(millis() - t0));
    state_ = State::Connecting;
    sinceMs_ = millis();
    otaStarted_ = false;
    failWhy_ = nullptr;
    setStatus(String("connecting to ") + ssid);
    return true;
}

void WifiOta::scan() {
    if (active()) { Serial.println("# wifi scan: not while update mode is on"); return; }
    WiFi.persistent(false);
    WiFi.mode(WIFI_STA);
    const int n = WiFi.scanNetworks();
    Serial.printf("# wifi scan: %d networks the node can join (2.4 GHz)\n", n < 0 ? 0 : n);
    for (int i = 0; i < n; i++)
        Serial.printf("#   %-32s ch%-2d %4d dBm %s\n", WiFi.SSID(i).c_str(), WiFi.channel(i), WiFi.RSSI(i),
                      WiFi.encryptionType(i) == WIFI_AUTH_OPEN ? "open" : "");
    WiFi.scanDelete();
    WiFi.mode(WIFI_OFF);
}

void WifiOta::stop(const char *why) {
    if (otaStarted_) ArduinoOTA.end();
    otaStarted_ = false;
    if (state_ != State::Off && state_ != State::Failed) {
        WiFi.disconnect(true, true);        // also forgets the credentials
        WiFi.mode(WIFI_OFF);
    }
    state_ = (strncmp(why, "failed", 6) == 0) ? State::Failed : State::Off;
    setStatus(String(why));
}

void WifiOta::poll(uint32_t nowMs) {
    switch (state_) {
    case State::Connecting:
        if (WiFi.status() == WL_CONNECTED) {
            ArduinoOTA.setHostname(OTA_HOSTNAME);
            ArduinoOTA.setPassword(OTA_PASSWORD);
            ArduinoOTA.setRebootOnSuccess(true);
            ArduinoOTA
                .onStart([this]() {
                    state_ = State::Updating;
                    lastPct_ = 0;
                    setStatus("updating 0%");
                })
                .onProgress([this](unsigned int done, unsigned int total) {
                    const int pct = total ? (int)(done * 100ULL / total) : 0;
                    if (pct / 10 != lastPct_ / 10) { lastPct_ = pct; setStatus(String("updating ") + pct + "%"); }
                })
                .onEnd([this]() { setStatus("done, restarting"); })
                .onError([this](ota_error_t e) {
                    const char *why = e == OTA_AUTH_ERROR ? "failed: wrong OTA password"
                                    : e == OTA_BEGIN_ERROR ? "failed: no room for the new firmware"
                                    : e == OTA_CONNECT_ERROR ? "failed: upload connection"
                                    : e == OTA_RECEIVE_ERROR ? "failed: upload interrupted"
                                    : "failed: upload check";
                    failWhy_ = why;     // stopped after handle() returns, not inside it
                });
            ArduinoOTA.begin();
            otaStarted_ = true;
            state_ = State::Ready;
            sinceMs_ = nowMs;
            setStatus(String("ready ") + WiFi.localIP().toString());
        } else if ((int32_t)(nowMs - sinceMs_) > (int32_t)OTA_CONNECT_MS) {
            static String why;              // stop() keeps the pointer only for this call
            why = joinFailure();
            stop(why.c_str());
        }
        break;

    case State::Ready:
    case State::Updating:
        ArduinoOTA.handle();                // blocks for the whole upload once one starts
        if (failWhy_) {
            const char *why = failWhy_;
            failWhy_ = nullptr;
            stop(why);
        } else if (WiFi.status() != WL_CONNECTED) {
            stop("failed: Wi-Fi lost");
        } else if (state_ == State::Updating) {
            // handle() came back without finishing: wait for another try
            state_ = State::Ready;
            sinceMs_ = nowMs;
            setStatus(String("ready ") + WiFi.localIP().toString());
        } else if ((int32_t)(nowMs - sinceMs_) > (int32_t)OTA_IDLE_MS) {
            stop("off: no upload within 10 min");
        }
        break;

    default:
        break;
    }
}
