# Builds, boots a Wear OS emulator, installs ATAK Watch, feeds it a GPS fix,
# and screenshots it. Captures the non-obvious emulator gotchas (charging
# screen, geo-fix lon/lat order) so you don't have to rediscover them.
#
# Usage:   powershell -ExecutionPolicy Bypass -File scripts\run-on-emulator.ps1
# Options: -Lat 40.7580 -Lon -73.9855   (default: Times Square, NYC)
#          -Avd atak_watch              (must already exist)

param(
    [string]$Avd = "atak_watch",
    [double]$Lat = 40.7580,
    [double]$Lon = -73.9855,
    # Screenshot/CI runs don't need a window; a person watching does.
    [switch]$Headless
)

$ErrorActionPreference = "Stop"
$Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "C:\Android\sdk" }
$Adb = Join-Path $Sdk "platform-tools\adb.exe"
$Emu = Join-Path $Sdk "emulator\emulator.exe"
$Pkg = "com.atakwatch.minimap"
$Apk = Join-Path $PSScriptRoot "..\app\build\outputs\apk\debug\app-debug.apk"

Write-Host "==> Building debug APK..."
& (Join-Path $PSScriptRoot "..\gradlew.bat") :app:assembleDebug

# Boot the emulator only if nothing is connected yet.
$devices = & $Adb devices
if ($devices -notmatch "emulator-\d+\s+device") {
    $emuArgs = @("-avd", $Avd, "-no-audio", "-no-boot-anim", "-gpu", "swiftshader_indirect")
    if ($Headless) { $emuArgs += "-no-window" }
    Write-Host ("==> Booting emulator '$Avd'" + $(if ($Headless) { " (headless)" } else { " (window)" }) + "...")
    Start-Process -FilePath $Emu -ArgumentList $emuArgs
    & $Adb wait-for-device
    Write-Host "==> Waiting for boot to complete..."
    do { Start-Sleep 3; $b = (& $Adb shell getprop sys.boot_completed).Trim() } while ($b -ne "1")
}

Write-Host "==> Installing APK..."
& $Adb install -r $Apk | Out-Null

Write-Host "==> Granting location permission..."
& $Adb shell pm grant $Pkg android.permission.ACCESS_FINE_LOCATION
& $Adb shell pm grant $Pkg android.permission.ACCESS_COARSE_LOCATION

# Wear emulators boot onto a charging screen that sits on top of everything.
Write-Host "==> Dismissing charging screen (unplug virtual battery)..."
& $Adb shell dumpsys battery unplug   | Out-Null
& $Adb shell dumpsys battery set status 3 | Out-Null

Write-Host "==> Launching app..."
& $Adb shell am start -n "$Pkg/.MainActivity" | Out-Null

# geo fix takes LONGITUDE first, then LATITUDE. Send a few times so the GPS
# provider delivers a fix the app's location overlay can lock onto.
Write-Host "==> Feeding GPS fix ($Lat, $Lon) and loading tiles..."
for ($i = 0; $i -lt 8; $i++) { & $Adb emu geo fix $Lon $Lat | Out-Null; Start-Sleep 2 }

$shot = Join-Path $PSScriptRoot "..\screenshots\latest.png"
Write-Host "==> Capturing screenshot -> $shot"
# PowerShell 5.1 redirection mangles binary output (re-encodes as text), so
# route the screencap through cmd.exe, whose > is a raw byte redirect.
cmd /c "`"$Adb`" exec-out screencap -p > `"$shot`""
Write-Host "Done. Screenshot at $shot"
