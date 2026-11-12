// wifi_ota.h - over-the-air firmware updates over Wi-Fi (home Wi-Fi, or the phone's hotspot).
//
// Wi-Fi is off in normal running. The phone app sends a Wi-Fi name and password over
// Bluetooth (BLE_OTA_UUID) when the rider taps NODE UPDATE with the engine off. The node
// then joins that network and waits for an ArduinoOTA upload from VS Code / PlatformIO
// (env:node_ota), as OTA_HOSTNAME.local or by the IP address shown in the app.
//
// - Nothing is stored: the credentials live in RAM until update mode ends.
// - Update mode ends after OTA_IDLE_MS with no upload, if the engine starts, when the phone
//   sends STOP, or on any failure. Wi-Fi is then switched fully off.
// - An upload is checked (MD5, and the OTA password) before the node switches to it, so an
//   interrupted upload leaves the old firmware running.
// - A firmware built without an OTA password (secrets.ini missing) refuses update mode.
#pragma once
#include <Arduino.h>

class WifiOta {
public:
    enum class State : uint8_t { Off, Connecting, Ready, Updating, Failed };

    // Starts update mode. Returns false (and sets status) if it can't.
    bool start(const char *ssid, const char *password);
    // Ends update mode and switches Wi-Fi off. 'why' is shown in the status.
    void stop(const char *why);
    // Prints the networks the node can see (USB serial 'w'), then switches Wi-Fi off again.
    void scan();
    // Call every loop. Blocks while an upload is in progress (the engine is off).
    void poll(uint32_t nowMs);

    State state() const { return state_; }
    bool active() const { return state_ == State::Connecting || state_ == State::Ready || state_ == State::Updating; }
    // Short text for the phone and the status line, e.g. "ready 192.168.43.57".
    const String &status() const { return status_; }
    // Bumped every time status() changes, so it can be sent on change.
    uint32_t statusSeq() const { return statusSeq_; }

private:
    void setStatus(const String &s);
    State    state_ = State::Off;
    String   status_ = "off";
    uint32_t statusSeq_ = 0;
    uint32_t sinceMs_ = 0;
    bool     otaStarted_ = false;
    int      lastPct_ = 0;
    const char *failWhy_ = nullptr;
};

extern WifiOta wifiOta;
