// taps.h - the dash's piezo switch, wired to the CAN node for the Android dash.
//
// Same logic as the ESP32 dash: the switch pulses the pin to GND, and a gesture
// ends TAP_GAP_MS after the last pulse. The finished count goes to the phone.
#pragma once
#include <Arduino.h>

void tapsBegin();

// Returns the tap count once a gesture has finished, 0 = none (or more than TAP_MAX).
int tapsPoll();
