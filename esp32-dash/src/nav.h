// nav.h - GPS position, breadcrumb waypoints, snail trail and track-back.
//
// Waypoints save to flash at once. The trail saves every few minutes while it's
// changing, so it survives key-off, apart from the last few minutes.
#pragma once
#include <Arduino.h>
#include "config.h"

struct NavPoint {
    int32_t latE7;
    int32_t lonE7;
};

struct NavWaypoint {
    NavPoint pos;
    uint16_t id;    // the number shown on screen. Never reused until Clear all
};

class NavModel {
public:
    void begin();
    void setPersist(bool persist) { persist_ = persist; }   // false: sim data isn't saved

    // Call often. Values are only used when fix is true.
    void update(bool fix, int32_t latE7, int32_t lonE7, float kmh, float courseDeg, uint8_t sats,
                int16_t altM, uint8_t hdopX10);

    // ---- taps ----
    void dropWaypoint();
    void menuOpen();
    void menuNext();
    void menuPrev();
    void menuChoose();
    void menuCancel();
    bool menuIsOpen() const { return menuOpen_; }

    // ---- for the screen ----
    bool  fix() const { return fix_; }
    bool  hasPosition() const { return hasPos_; }   // true once there's been any fix
    NavPoint position() const { return pos_; }
    uint8_t sats() const { return sats_; }
    float kmh() const { return kmh_; }
    bool  headingValid() const { return headingValid_; }
    float headingDeg() const { return headingDeg_; }

    int trailCount() const { return trailCount_; }
    const NavPoint &trailPoint(int i) const { return trail_[i]; }
    int waypointCount() const { return wpCount_; }
    const NavWaypoint &waypoint(int i) const { return wp_[i]; }
    float mapMetersPerPixel() const { return mapMPerPx_; }
    int16_t altitudeM() const { return altM_; }
    float accuracyM() const { return hdopX10_ * 0.5f; }  // rough: HDOP x 5 m

    bool  navigating() const { return navActive_; }
    bool  arrived() const { return navArrived_; }
    bool  tracBack() const { return navTracBack_; }     // retracing the trail, not going to a waypoint
    uint16_t targetId() const { return navTracBack_ ? 0 : wp_[navCurrent_].id; }   // next breadcrumb to reach
    uint16_t finalId() const { return navTracBack_ ? 0 : wp_[navFinal_].id; }      // the one that was selected
    NavPoint targetPos() const { return navTracBack_ ? trail_[navTrailIdx_] : wp_[navCurrent_].pos; }
    float targetDistM() const { return navDistM_; }
    float targetBearingDeg() const { return navBearingDeg_; }

    const char *toast() const;          // short message, or nullptr

    int  menuCount() const;
    int  menuIndex() const { return menuIdx_; }
    void menuLabel(int item, char *buf, size_t n) const;

    // Metres from a to b, as x east and y north. Good for the distances used here.
    static void offsetMeters(const NavPoint &a, const NavPoint &b, float &x, float &y);

private:
    NavPoint    trail_[NAV_TRAIL_MAX];
    int         trailCount_ = 0;
    float       trailSpacingM_ = NAV_TRAIL_SPACING_M;
    bool        trailDirty_ = false;
    bool        persist_ = true;
    uint32_t    lastTrailSaveMs_ = 0;

    NavWaypoint wp_[NAV_WAYPOINT_MAX];
    int         wpCount_ = 0;
    uint16_t    nextId_ = 1;

    uint32_t lastUpdateMs_ = 0;
    bool     fix_ = false, hasPos_ = false, headingValid_ = false;
    NavPoint pos_{0, 0};
    uint8_t  sats_ = 0;
    float    kmh_ = 0, headingDeg_ = 0;
    float    mapMPerPx_ = 10;
    int16_t  altM_ = 0;
    uint8_t  hdopX10_ = 0;

    bool     navActive_ = false, navArrived_ = false, navTracBack_ = false;
    int      navTrailIdx_ = 0;                  // TracBack: trail point being headed for
    int      navCurrent_ = 0, navFinal_ = 0;    // indexes into wp_
    float    navDistM_ = 0, navBearingDeg_ = 0;

    bool     menuOpen_ = false, clearConfirm_ = false;
    int      menuIdx_ = 0;
    uint32_t menuLastInputMs_ = 0;

    char     toast_[24] = "";
    uint32_t toastUntilMs_ = 0;

    void addTrailPoint();
    void updateNavigation();
    void updateMapScale();
    void startNavigation(int waypointIndex);
    void startTracBack();
    bool menuHasTracBack() const { return trailCount_ >= 2; }
    void stopNavigation();
    void clearAll();
    void saveWaypoints();
    void saveTrail();
    void showToast(const char *text);
    void touchMenu() { menuLastInputMs_ = millis(); clearConfirm_ = false; }
};
