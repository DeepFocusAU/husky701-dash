#include "sdlog.h"
#include "config.h"
#include <SD.h>
#include <SPI.h>

// Longest line: "4294967295,7FF,8,1122334455667788\n"
static constexpr int LOG_LINE_LEN = 40;

const char *SdLog::stateName() const {
    switch (state_) {
    case State::Starting: return "starting";
    case State::NoCard:   return "no card";
    case State::Logging:  return "logging";
    default:              return "FAILED";
    }
}

void SdLog::begin() {
    buf_ = xStreamBufferCreate(SD_BUFFER_BYTES, 1);
    if (!buf_) {
        state_ = State::NoCard;
        return;
    }
    // Core 0: the Arduino loop, which handles CAN and the dash link, runs on core 1.
    xTaskCreatePinnedToCore(taskEntry, "sdlog", 8192, this, 1, nullptr, 0);
}

void SdLog::log(const CanFrame &f, uint32_t now) {
    if (state_ != State::Logging) return;

    char line[LOG_LINE_LEN];
    int n = snprintf(line, sizeof(line), "%lu,%03lX,%u,", (unsigned long)now,
                     (unsigned long)f.id, f.dlc);
    for (uint8_t i = 0; i < f.dlc; i++) n += snprintf(line + n, sizeof(line) - n, "%02X", f.data[i]);
    line[n++] = '\n';

    if (xStreamBufferSpacesAvailable(buf_) < (size_t)n) {
        dropped_ = dropped_ + 1;
        return;
    }
    xStreamBufferSend(buf_, line, n, 0);
}

void SdLog::logText(const char *tag, const char *text, uint32_t now) {
    if (state_ != State::Logging) return;

    char line[128];
    const int n = snprintf(line, sizeof(line), "%lu,%s,%s\n", (unsigned long)now, tag, text);
    if (n <= 0 || n >= (int)sizeof(line)) return;

    if (xStreamBufferSpacesAvailable(buf_) < (size_t)n) {
        dropped_ = dropped_ + 1;
        return;
    }
    xStreamBufferSend(buf_, line, n, 0);
}

void SdLog::taskEntry(void *self) {
    static_cast<SdLog *>(self)->run();
    vTaskDelete(nullptr);
}

// Returns the number after the highest LOGnnnnn.CSV on the card.
static unsigned nextFileNumber() {
    unsigned highest = 0;
    File root = SD.open("/");
    if (!root) return 1;
    for (File f = root.openNextFile(); f; f = root.openNextFile()) {
        unsigned num;
        if (sscanf(f.name(), "LOG%u.CSV", &num) == 1 && num > highest) highest = num;
        f.close();
    }
    root.close();
    return highest + 1;
}

void SdLog::run() {
    static SPIClass spi(HSPI);      // static: the SD library keeps a pointer to it
    spi.begin(PIN_SD_SCLK, PIN_SD_MISO, PIN_SD_MOSI, PIN_SD_CS);

    // A reset during a write can leave the card busy for a while, so try a few times.
    bool mounted = false;
    for (int attempt = 0; attempt < SD_MOUNT_TRIES && !mounted; attempt++) {
        if (attempt > 0) {
            SD.end();
            vTaskDelay(pdMS_TO_TICKS(SD_MOUNT_RETRY_MS));
        }
        mounted = SD.begin(PIN_SD_CS, spi, SD_SPI_HZ);
    }
    if (!mounted) {
        state_ = State::NoCard;
        return;
    }

    char path[20];
    snprintf(fileName_, sizeof(fileName_), "LOG%05u.CSV", nextFileNumber());
    snprintf(path, sizeof(path), "/%s", fileName_);

    File file = SD.open(path, FILE_WRITE);
    if (!file) {
        state_ = State::Failed;
        return;
    }
    file.println("ms,id,len,data");
    file.flush();
    state_ = State::Logging;

    static uint8_t chunk[4096];
    uint32_t lastFlush = millis();
    for (;;) {
        const size_t got = xStreamBufferReceive(buf_, chunk, sizeof(chunk), pdMS_TO_TICKS(200));
        if (got > 0) {
            if (file.write(chunk, got) != got) {
                file.close();
                state_ = State::Failed;
                return;
            }
            bytes_ = bytes_ + got;
        }
        // Key-off cuts power with no warning, so this is the most that can be lost.
        if (millis() - lastFlush >= SD_FLUSH_MS) {
            lastFlush = millis();
            file.flush();
        }
    }
}
