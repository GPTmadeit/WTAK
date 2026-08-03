# Live demo driver for ATAK Watch.
#
# Brings up a visible Wear OS emulator, installs the app, turns on CoT mesh,
# then continuously walks your own position along a track while a second
# "teammate" transmits real TAK Protocol v1 position reports over the mesh.
# Everything on screen is genuine app behaviour — there is no sample data in
# the app itself.
#
#   powershell -ExecutionPolicy Bypass -File scripts\demo.ps1
#
# Options:
#   -Lat / -Lon    start position (default: Times Square, NYC)
#   -Steps         how many moves before the track loops (default 40)
#   -DelayMs       pace of the walk (default 1500 ms)
#   -Headless      no emulator window (for testing the script itself)
#   -SkipBuild     use the APK already built
#
# Ctrl-C stops the demo. The emulator keeps running so you can keep exploring.

param(
    [double]$Lat = 40.7580,
    [double]$Lon = -73.9855,
    [int]$Steps = 40,
    [int]$DelayMs = 1500,
    [string]$Avd = "atak_watch",
    [switch]$Headless,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$Sdk  = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "C:\Android\sdk" }
$Adb  = Join-Path $Sdk "platform-tools\adb.exe"
$Emu  = Join-Path $Sdk "emulator\emulator.exe"
$Pkg  = "com.atakwatch.minimap"
$Root = Split-Path $PSScriptRoot -Parent
$Apk  = Join-Path $Root "app\build\outputs\apk\debug\app-debug.apk"
$Tools = Join-Path $Root "tools"

function Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }

# ---------------------------------------------------------------- build
if (-not $SkipBuild) {
    Step "Building debug APK"
    & (Join-Path $Root "gradlew.bat") :app:assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "build failed" }
}
if (-not (Test-Path $Apk)) { throw "APK not found at $Apk (drop -SkipBuild)" }

# ---------------------------------------------------------------- emulator
$devices = & $Adb devices
if ($devices -notmatch "emulator-\d+\s+device") {
    $emuArgs = @("-avd", $Avd, "-no-audio", "-no-boot-anim", "-gpu", "swiftshader_indirect")
    if ($Headless) { $emuArgs += "-no-window" }
    Step "Booting emulator '$Avd'"
    Start-Process -FilePath $Emu -ArgumentList $emuArgs
    & $Adb wait-for-device
    Step "Waiting for boot"
    do { Start-Sleep 3; $b = (& $Adb shell getprop sys.boot_completed).Trim() } while ($b -ne "1")
} else {
    Step "Using the emulator that is already running"
}

# ---------------------------------------------------------------- install
Step "Installing and granting permissions"
& $Adb install -r $Apk | Out-Null
foreach ($p in @("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS")) {
    & $Adb shell pm grant $Pkg "android.permission.$p" 2>$null
}
# Wear emulators boot onto a charging screen that covers the app.
& $Adb shell dumpsys battery unplug        | Out-Null
& $Adb shell dumpsys battery set status 3  | Out-Null

Step "Launching with CoT mesh enabled"
& $Adb shell am start -n "$Pkg/.MainActivity" --ez enable_mesh true | Out-Null
Start-Sleep 5

# ------------------------------------------------- teammate mesh frames
# Pre-build the teammate's position reports so the loop only has to transmit.
# These are genuine TAK Protocol v1 frames, byte-identical to what ATAK sends.
$frameDir = Join-Path $env:TEMP "atakwatch-demo"
New-Item -ItemType Directory -Force -Path $frameDir | Out-Null
$haveJava = $null -ne (Get-Command java -ErrorAction SilentlyContinue)

if ($haveJava) {
    Step "Generating teammate position reports"
    Push-Location $Tools
    for ($i = 0; $i -lt $Steps; $i++) {
        # BRAVO walks a wide arc a few hundred metres off your track.
        $t = [math]::PI * 2 * $i / $Steps
        $bLat = $Lat + 0.0030 + 0.0016 * [math]::Sin($t)
        $bLon = $Lon + 0.0022 * [math]::Cos($t)
        $f = Join-Path $frameDir ("b{0:d3}.bin" -f $i)
        & java FakeEud.java dump $f $bLat $bLon "BRAVO-2" "Blue" "Team Member" | Out-Null
    }
    Pop-Location
    & $Adb shell rm -rf /data/local/tmp/atakdemo 2>$null
    & $Adb shell mkdir -p /data/local/tmp/atakdemo | Out-Null
    & $Adb push $frameDir/. /data/local/tmp/atakdemo/ | Out-Null
    Step "Teammate ready ($Steps reports)"
} else {
    Write-Warning "java not found - skipping the teammate; your own position will still move."
}

# ---------------------------------------------------------------- the walk
Write-Host ""
Write-Host "LIVE. Your marker walks north-east; BRAVO-2 orbits to the north." -ForegroundColor Green
Write-Host "Try: crown to zoom, flag to drop a waypoint, menu -> Contacts." -ForegroundColor Green
Write-Host "Ctrl-C to stop (the emulator stays up)." -ForegroundColor Green
Write-Host ""

$i = 0
while ($true) {
    $k = $i % $Steps
    # Your own track: a steady north-east walk, ~15 m per step.
    $myLat = $Lat + 0.00012 * $k
    $myLon = $Lon + 0.00010 * $k

    # geo fix takes LONGITUDE first, then LATITUDE.
    & $Adb emu geo fix $myLon $myLat | Out-Null

    if ($haveJava) {
        $remote = "/data/local/tmp/atakdemo/" + ("b{0:d3}.bin" -f $k)
        # Emulator NAT drops inbound UDP, so transmit from inside the guest on
        # loopback - the app's mesh listener receives it exactly the same way.
        # -q 1 quits 1s after EOF; -w would block here and stall the walk.
        & $Adb shell "cat $remote | nc -u -q 1 127.0.0.1 6969" 2>$null | Out-Null
    }

    Write-Host ("  step {0,3}  you {1:F5},{2:F5}" -f $k, $myLat, $myLon)
    [Console]::Out.Flush()
    Start-Sleep -Milliseconds $DelayMs
    $i++
}
