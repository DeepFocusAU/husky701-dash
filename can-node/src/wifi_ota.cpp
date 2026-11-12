#include "wifi_ota.h"
#include "config.h"
#include <WiFi.h>
#include <ArduinoOTA.h>

WifiOta wifiOta;

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
        setStatus("failed: no hotspot name");
        return false;
    }

    WiFi.persistent(false);                 // never write the credentials to flash
    WiFi.mode(WIFI_STA);
    WiFi.setHostname(OTA_HOSTNAME);
    WiFi.setSleep(true);                    // modem sleep: needed with Bluetooth running
    WiFi.begin(ssid, password);
    state_ = State::Connecting;
    sinceMs_ = millis();
    otaStarted_ = false;
    failWhy_ = nullptr;
    setStatus(String("connecting to ") + ssid);
    return true;
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
        } else if (nowMs - sinceMs_ > OTA_CONNECT_MS) {
            stop("failed: couldn't join the hotspot");
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
            stop("failed: hotspot lost");
        } else if (state_ == State::Updating) {
            // handle() came back without finishing: wait for another try
            state_ = State::Ready;
            sinceMs_ = nowMs;
            setStatus(String("ready ") + WiFi.localIP().toString());
        } else if (nowMs - sinceMs_ > OTA_IDLE_MS) {
            stop("off: no upload within 10 min");
        }
        break;

    default:
        break;
    }
}
