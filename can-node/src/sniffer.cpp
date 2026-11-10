#include "sniffer.h"

// Longest log line: "12345678 12B 8 11 22 33 44 55 66 77 88\n"
static constexpr int LOG_LINE_LEN = 48;

void Sniffer::setMode(Mode m) {
    mode_ = m;
    dropped_ = 0;
}

void Sniffer::print(const CanFrame &f, uint32_t now) {
    // Never wait for the USB port: the dash link matters more than the log.
    if (Serial.availableForWrite() < LOG_LINE_LEN) {
        dropped_++;
        return;
    }
    char line[LOG_LINE_LEN];
    int n = snprintf(line, sizeof(line), "%8lu %03lX %u", (unsigned long)now,
                     (unsigned long)f.id, f.dlc);
    for (uint8_t i = 0; i < f.dlc; i++) n += snprintf(line + n, sizeof(line) - n, " %02X", f.data[i]);
    line[n++] = '\n';
    Serial.write((const uint8_t *)line, n);
}

void Sniffer::feed(const CanFrame &f, uint32_t now) {
    Entry *e = nullptr;
    for (int i = 0; i < used_; i++) {
        if (table_[i].id == f.id) {
            e = &table_[i];
            break;
        }
    }

    bool changed = true;
    if (e) {
        changed = e->dlc != f.dlc || memcmp(e->data, f.data, f.dlc) != 0;
    } else if (used_ < MAX_IDS) {
        e = &table_[used_++];
        e->id = f.id;
        e->count = 0;
    }
    if (e) {
        e->count++;
        e->dlc = f.dlc;
        memcpy(e->data, f.data, f.dlc);
    }

    if (mode_ == Mode::All || (mode_ == Mode::Changes && changed)) print(f, now);
}

void Sniffer::printTable() {
    const uint32_t now = millis();
    const float secs = (now - tableResetMs_) / 1000.0f;

    Serial.printf("# %d IDs over %.1f s (ID, frames/s, latest bytes)\n", used_, secs);
    for (int i = 0; i < used_; i++) {
        const Entry &e = table_[i];
        Serial.printf("%03lX %7.1f ", (unsigned long)e.id, secs > 0 ? e.count / secs : 0.0f);
        for (uint8_t b = 0; b < e.dlc; b++) Serial.printf(" %02X", e.data[b]);
        Serial.println();
    }

    // Restart the rate window so each print shows recent rates.
    for (int i = 0; i < used_; i++) table_[i].count = 0;
    tableResetMs_ = now;
}
