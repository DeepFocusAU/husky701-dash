#include "nav.h"
#include <Preferences.h>
#include <math.h>
#include <string.h>

static Preferences nprefs;

static const float METERS_PER_E7 = 0.0111319491f;
// Metres per screen pixel
static const float MAP_SCALES[] = {5, 10, 25, 50, 100, 250, 500, 1000, 2500};
static const int   MAP_SCALE_COUNT = sizeof(MAP_SCALES) / sizeof(MAP_SCALES[0]);
static const float MAP_FIT_PX = 70;     // what must fit within this many pixels of the bike

void NavModel::offsetMeters(const NavPoint &a, const NavPoint &b, float &x, float &y) {
    const float midLatDeg = (float)(((int64_t)a.latE7 + b.latE7) / 2) * 1e-7f;
    x = (float)((int64_t)b.lonE7 - a.lonE7) * METERS_PER_E7 * cosf(midLatDeg * (float)M_PI / 180.0f);
    y = (float)((int64_t)b.latE7 - a.latE7) * METERS_PER_E7;
}

void NavModel::begin() {
    nprefs.begin("nav", false);

    nextId_ = nprefs.getUShort("nextId", 1);
    size_t got = nprefs.getBytes("wp", wp_, sizeof(wp_));
    wpCount_ = (int)(got / sizeof(NavWaypoint));

    got = nprefs.getBytes("trail", trail_, sizeof(trail_));
    trailCount_ = (int)(got / sizeof(NavPoint));
    trailSpacingM_ = nprefs.getFloat("spacing", NAV_TRAIL_SPACING_M);

    lastTrailSaveMs_ = millis();
}

void NavModel::update(bool fix, int32_t latE7, int32_t lonE7, float kmh, float courseDeg, uint8_t sats,
                      int16_t altM, uint8_t hdopX10) {
    const uint32_t now = millis();

    if (menuOpen_ && now - menuLastInputMs_ > NAV_MENU_TIMEOUT_MS) menuCancel();

    if (now - lastUpdateMs_ < 200) return;
    lastUpdateMs_ = now;

    fix_ = fix;
    sats_ = sats;
    if (fix) {
        altM_ = altM;
        hdopX10_ = hdopX10;
    }
    kmh_ = fix ? kmh : 0;
    if (fix) {
        pos_ = {latE7, lonE7};
        hasPos_ = true;

        // GPS course means nothing until the bike is moving.
        headingValid_ = kmh >= NAV_HEADING_MIN_KMH;
        if (headingValid_) headingDeg_ = courseDeg;

        addTrailPoint();
        updateNavigation();
        updateMapScale();
    } else {
        headingValid_ = false;
    }

    if (trailDirty_ && now - lastTrailSaveMs_ >= NAV_TRAIL_SAVE_MS) saveTrail();
}

void NavModel::addTrailPoint() {
    if (trailCount_ > 0) {
        float x, y;
        offsetMeters(trail_[trailCount_ - 1], pos_, x, y);
        if (x * x + y * y < trailSpacingM_ * trailSpacingM_) return;
    }

    // Full: keep every other point and space later ones further apart. The trail
    // always covers the whole trip, with older parts in less detail.
    if (trailCount_ >= NAV_TRAIL_MAX) {
        int kept = 0;
        for (int i = 0; i < trailCount_; i += 2) trail_[kept++] = trail_[i];
        trailCount_ = kept;
        trailSpacingM_ *= 2;
        navTrailIdx_ /= 2;
    }

    trail_[trailCount_++] = pos_;
    trailDirty_ = true;
}

void NavModel::updateNavigation() {
    if (!navActive_) return;

    float x, y;
    offsetMeters(pos_, targetPos(), x, y);
    navDistM_ = sqrtf(x * x + y * y);
    navBearingDeg_ = atan2f(x, y) * 180.0f / (float)M_PI;
    if (navBearingDeg_ < 0) navBearingDeg_ += 360.0f;

    if (navTracBack_) {
        // Next point back along the trail. The last leg is the start of the track.
        if (navDistM_ < NAV_ARRIVE_M) {
            if (navTrailIdx_ > 0) navTrailIdx_--;
            else navArrived_ = true;
        }
        return;
    }

    if (navDistM_ < NAV_ARRIVE_M) {
        if (navCurrent_ > navFinal_) {
            navCurrent_--;      // reached a breadcrumb: on to the next one back
            navArrived_ = false;
        } else {
            navArrived_ = true;
        }
    } else if (navDistM_ > NAV_ARRIVE_M * 2) {
        navArrived_ = false;
    }
}

void NavModel::updateMapScale() {
    // Fit what matters: recent trail, plus the waypoint being navigated to.
    float needM = 0;
    if (navActive_) needM = fmaxf(needM, navDistM_);
    for (int i = max(0, trailCount_ - NAV_MAP_RECENT_POINTS); i < trailCount_; i++) {
        float x, y;
        offsetMeters(pos_, trail_[i], x, y);
        needM = fmaxf(needM, fmaxf(fabsf(x), fabsf(y)));
    }
    const float needMPerPx = needM / MAP_FIT_PX;

    float best = MAP_SCALES[MAP_SCALE_COUNT - 1];
    for (int i = MAP_SCALE_COUNT - 1; i >= 0; i--) {
        if (MAP_SCALES[i] >= needMPerPx) best = MAP_SCALES[i];
    }
    // Zoom out at once, but only zoom in with some margin, so it doesn't flick
    // between two scales at the edge of one.
    if (best > mapMPerPx_ || needMPerPx < best * 0.7f) mapMPerPx_ = best;
}

void NavModel::dropWaypoint() {
    if (!fix_) {
        showToast("NO GPS FIX");
        return;
    }

    // Full: forget the oldest, keeping navigation pointing at the same waypoints.
    if (wpCount_ >= NAV_WAYPOINT_MAX) {
        memmove(&wp_[0], &wp_[1], sizeof(NavWaypoint) * (wpCount_ - 1));
        wpCount_--;
        if (navActive_ && !navTracBack_) {
            if (navFinal_ == 0) {
                stopNavigation();
            } else {
                navFinal_--;
                navCurrent_--;
            }
        }
    }

    wp_[wpCount_++] = {pos_, nextId_};
    char s[24];
    snprintf(s, sizeof(s), "W%u SAVED", nextId_);
    showToast(s);
    nextId_++;
    saveWaypoints();
}

void NavModel::startNavigation(int waypointIndex) {
    // Breadcrumbs lead back in order: start from whichever is nearest among the
    // selected one and those dropped after it, then work back to the selected one.
    int nearest = waypointIndex;
    float nearestSq = 1e18f;
    for (int i = waypointIndex; i < wpCount_; i++) {
        float x, y;
        offsetMeters(pos_, wp_[i].pos, x, y);
        const float d = x * x + y * y;
        if (d < nearestSq) {
            nearestSq = d;
            nearest = i;
        }
    }
    navFinal_ = waypointIndex;
    navCurrent_ = nearest;
    navTracBack_ = false;
    navActive_ = true;
    navArrived_ = false;
    updateNavigation();
}

void NavModel::startTracBack() {
    // join the trail at the nearest point, then work back to its start
    int nearest = trailCount_ - 1;
    float nearestSq = 1e18f;
    for (int i = 0; i < trailCount_; i++) {
        float x, y;
        offsetMeters(pos_, trail_[i], x, y);
        const float d = x * x + y * y;
        if (d < nearestSq) {
            nearestSq = d;
            nearest = i;
        }
    }
    navTrailIdx_ = nearest;
    navTracBack_ = true;
    navActive_ = true;
    navArrived_ = false;
    updateNavigation();
}

void NavModel::stopNavigation() {
    navActive_ = false;
    navArrived_ = false;
    navTracBack_ = false;
}

void NavModel::clearAll() {
    stopNavigation();
    wpCount_ = 0;
    nextId_ = 1;
    trailCount_ = 0;
    trailSpacingM_ = NAV_TRAIL_SPACING_M;
    saveWaypoints();
    saveTrail();
    showToast("ALL CLEARED");
}

// ---- menu: [Stop navigating], [TracBack], then waypoints newest first, then Clear all ----

int NavModel::menuCount() const {
    return wpCount_ + 1 + (navActive_ ? 1 : 0) + (menuHasTracBack() ? 1 : 0);
}

void NavModel::menuLabel(int item, char *buf, size_t n) const {
    if (navActive_) {
        if (item == 0) {
            snprintf(buf, n, "Stop navigating");
            return;
        }
        item--;
    }
    if (menuHasTracBack()) {
        if (item == 0) {
            snprintf(buf, n, "TracBack: retrace track");
            return;
        }
        item--;
    }
    if (item < wpCount_) {
        const NavWaypoint &w = wp_[wpCount_ - 1 - item];
        if (fix_) {
            float x, y;
            offsetMeters(pos_, w.pos, x, y);
            const float d = sqrtf(x * x + y * y);
            if (d < 1000) snprintf(buf, n, "W%u   %.0f m", w.id, d);
            else          snprintf(buf, n, "W%u   %.1f km", w.id, d / 1000.0f);
        } else {
            snprintf(buf, n, "W%u", w.id);
        }
        return;
    }
    snprintf(buf, n, clearConfirm_ ? "2 taps again: clear all" : "Clear waypoints + trail");
}

void NavModel::menuOpen() {
    menuOpen_ = true;
    menuIdx_ = 0;
    touchMenu();
}

void NavModel::menuNext() {
    menuIdx_ = (menuIdx_ + 1) % menuCount();
    touchMenu();
}

void NavModel::menuPrev() {
    menuIdx_ = (menuIdx_ + menuCount() - 1) % menuCount();
    touchMenu();
}

void NavModel::menuCancel() {
    menuOpen_ = false;
    clearConfirm_ = false;
}

void NavModel::menuChoose() {
    menuLastInputMs_ = millis();

    int item = menuIdx_;
    if (navActive_) {
        if (item == 0) {
            stopNavigation();
            menuCancel();
            return;
        }
        item--;
    }
    if (menuHasTracBack()) {
        if (item == 0) {
            if (!fix_) showToast("NO GPS FIX");
            else       startTracBack();
            menuCancel();
            return;
        }
        item--;
    }
    if (item < wpCount_) {
        if (!fix_) {
            showToast("NO GPS FIX");
        } else {
            startNavigation(wpCount_ - 1 - item);
        }
        menuCancel();
        return;
    }
    if (!clearConfirm_) {
        clearConfirm_ = true;
        return;
    }
    clearAll();
    menuCancel();
}

// ---- storage ----

void NavModel::saveWaypoints() {
    if (!persist_) return;
    nprefs.putBytes("wp", wp_, sizeof(NavWaypoint) * wpCount_);
    nprefs.putUShort("nextId", nextId_);
}

void NavModel::saveTrail() {
    lastTrailSaveMs_ = millis();
    if (!persist_) return;
    nprefs.putBytes("trail", trail_, sizeof(NavPoint) * trailCount_);
    nprefs.putFloat("spacing", trailSpacingM_);
    trailDirty_ = false;
}

void NavModel::showToast(const char *text) {
    strncpy(toast_, text, sizeof(toast_) - 1);
    toast_[sizeof(toast_) - 1] = 0;
    toastUntilMs_ = millis() + NAV_TOAST_MS;
}

const char *NavModel::toast() const {
    return millis() < toastUntilMs_ ? toast_ : nullptr;
}
