// selftest.h - made-up CAN frames for bench-testing without a bike (env:selftest).
#pragma once
#include <stdint.h>
#include "can_bus.h"

// Call often. Passes frames to 'sink' every 20 ms, the same way real ones arrive.
void selftestFeed(void (*sink)(const CanFrame &), uint32_t nowMs);
