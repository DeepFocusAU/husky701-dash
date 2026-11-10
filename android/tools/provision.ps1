# provision.ps1 - turns a Samsung Note 10 into the Husky701 dash appliance over adb.
#
# Before running:
#   1. Factory reset the phone. Skip every account during setup (no Google, no Samsung
#      account): Android only allows a device owner on a phone with no accounts.
#   2. Settings > About phone > Software information: tap Build number 7 times.
#   3. Settings > Developer options: turn on USB debugging. Plug in, accept the prompt.
#
# Run from the android folder:
#   powershell -ExecutionPolicy Bypass -File tools\provision.ps1
#   powershell -ExecutionPolicy Bypass -File tools\provision.ps1 -Release   # undo everything
#
# Everything here can be undone with -Release. Apps are disabled, not uninstalled.

param([switch]$Release, [string]$Apk = (Join-Path $env:USERPROFILE "Android\build\Husky701.Android\app\outputs\apk\debug\app-debug.apk"))

$ErrorActionPreference = "Stop"
$adb = Join-Path $env:USERPROFILE "Android\sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
$pkg = "com.husky701.dash"

function Adb { & $adb @args; if ($LASTEXITCODE -ne 0) { throw "adb $args failed" } }
function AdbTry { & $adb @args 2>$null | Out-Null }

# Samsung extras the dash never needs. Disabling stops them running in the background
# and popping up over the dash. Re-enable with: adb shell pm enable <package>
$disable = @(
    "com.samsung.android.bixby.agent", "com.samsung.android.bixby.wakeup",
    "com.samsung.android.app.spage", "com.samsung.android.visionintelligence",
    "com.samsung.android.app.routines", "com.samsung.android.app.tips",
    "com.samsung.android.game.gamehome", "com.samsung.android.game.gametools",
    "com.samsung.android.aremoji", "com.samsung.android.ardrawing",
    "com.samsung.android.app.reminder", "com.samsung.android.service.peoplestripe",
    "com.samsung.android.app.sharelive", "com.samsung.android.mdx",
    "com.samsung.android.kidsinstaller", "com.samsung.android.app.watchmanagerstub",
    "com.samsung.android.da.daagent", "com.samsung.android.smartswitchassistant",
    "com.facebook.katana", "com.facebook.system", "com.facebook.appmanager", "com.facebook.services",
    "com.microsoft.skydrive", "com.linkedin.android", "com.netflix.mediaclient", "com.spotify.music",
    "com.sec.android.app.sbrowser", "com.android.chrome", "com.google.android.youtube",
    "com.google.android.apps.youtube.music", "com.google.android.gm", "com.google.android.apps.maps",
    "com.samsung.android.email.provider", "com.samsung.android.calendar", "com.samsung.android.messaging",
    "com.sec.android.gallery3d", "com.sec.android.app.camera"
)

Adb wait-for-device
Write-Host "Phone: $(& $adb shell getprop ro.product.model)  Android $(& $adb shell getprop ro.build.version.release)"

if ($Release) {
    Write-Host "Releasing appliance mode..."
    AdbTry shell am broadcast -a "$pkg.RELEASE" -n "$pkg/.ReleaseReceiver"
    foreach ($p in $disable) { AdbTry shell pm enable $p }
    AdbTry shell settings put global window_animation_scale 1
    AdbTry shell settings put global transition_animation_scale 1
    AdbTry shell settings put global animator_duration_scale 1
    Write-Host "Done. The dash is a normal app again; uninstall with: adb uninstall $pkg"
    exit 0
}

if (-not (Test-Path $Apk)) { throw "APK not found at $Apk. Build it first: .\gradlew.bat assembleDebug" }

Write-Host "Installing the dash..."
Adb install -r -g $Apk

Write-Host "Making the dash the device owner..."
& $adb shell dpm set-device-owner "$pkg/.DashAdminReceiver"
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "set-device-owner failed. Usually an account is still on the phone (Google, Samsung,"
    Write-Host "or one added by an app). Remove all accounts in Settings > Accounts and backup,"
    Write-Host "or factory reset and skip accounts, then run this again."
    exit 1
}

Write-Host "Disabling apps the dash doesn't need..."
foreach ($p in $disable) { AdbTry shell pm disable-user --user 0 $p }

Write-Host "Settings..."
AdbTry shell settings put global window_animation_scale 0      # instant, no transitions
AdbTry shell settings put global transition_animation_scale 0
AdbTry shell settings put global animator_duration_scale 0
AdbTry shell settings put system screen_off_timeout 600000     # 10 min if ever unpinned
AdbTry shell settings put global auto_time 1                   # phone clock from the network / GPS
AdbTry shell appops set com.husky701.dash SYSTEM_ALERT_WINDOW allow   # tacho strip over the map app
AdbTry shell settings put global heads_up_notifications_enabled 0
AdbTry shell cmd bluetooth_manager enable

Write-Host "Starting the dash..."
Adb shell am start -n "$pkg/.MainActivity"

Write-Host ""
Write-Host "Done. The phone now boots straight into the dash, pinned, with no lock screen."
Write-Host "Maintenance: Diagnostics page, 4 taps twice. Undo all of it: tools\provision.ps1 -Release"
