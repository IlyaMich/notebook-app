# Opt-in STO-04 API 37 app-private filesystem probe; no storage-pressure operations.
param(
    [string]$RepoRoot = 'C:\Proj\notebook-app',
    [string]$Serial = 'emulator-5556'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$expectedAvd = 'STO07_S03B_Disposable'
$expectedBranch = 'feature/sto-04-android-asset-staging'
$runner = 'com.notebook.app.test/androidx.test.runner.AndroidJUnitRunner'
$testClass = 'com.notebook.app.data.assets.ManagedAssetFileStoreAndroidTest'

function Assert-Exit([string]$operation) {
    if ($LASTEXITCODE -ne 0) { throw "$operation failed ($LASTEXITCODE)." }
}

if (-not (Test-Path -LiteralPath $RepoRoot -PathType Container)) {
    throw "Repository directory not found: $RepoRoot"
}
Set-Location -LiteralPath $RepoRoot -ErrorAction Stop
$branch = (& git branch --show-current).Trim()
Assert-Exit 'Git branch inspection'
if ($branch -ne $expectedBranch) { throw "Wrong branch: $branch" }

$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) { throw "ADB missing: $adb" }

function Assert-Device {
    $devices = @(& $adb devices -l)
    Assert-Exit 'ADB device enumeration'
    $pattern = '(?m)^' + [regex]::Escape($Serial) + '\s+device\b'
    if (($devices -join "`n") -notmatch $pattern) {
        throw "Required disposable device $Serial is not connected/ready. Devices: $($devices -join '; ')"
    }
    $identity = @(& $adb -s $Serial emu avd name)
    Assert-Exit 'AVD identity query'
    if ($identity.Count -lt 1 -or $identity[0].Trim() -ne $expectedAvd) {
        throw "Refusing to target ${Serial}: AVD is not $expectedAvd"
    }
    $boot = ([string](& $adb -s $Serial shell getprop sys.boot_completed)).Trim()
    Assert-Exit 'Boot status'
    if ($boot -ne '1') { throw "Disposable Android has not finished booting." }
}

Write-Host '=== Verify exact disposable emulator before build ==='
Assert-Device

$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
if (-not (Test-Path -LiteralPath "$env:JAVA_HOME\bin\java.exe")) {
    throw "JAVA_HOME unavailable: $env:JAVA_HOME"
}
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

Write-Host '=== Build test APKs (no device operation) ==='
Set-Location -LiteralPath (Join-Path $RepoRoot 'android')
& .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
Assert-Exit 'Gradle APK build'

$appApk = Join-Path $RepoRoot 'android\app\build\outputs\apk\debug\app-debug.apk'
$testApk = Join-Path $RepoRoot 'android\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
foreach ($apk in @($appApk, $testApk)) {
    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
        throw "Missing built APK: $apk"
    }
}

Write-Host '=== Recheck AVD; install without clearing app data ==='
Assert-Device
& $adb -s $Serial install -r $appApk
Assert-Exit 'App APK installation'
Assert-Device
& $adb -s $Serial install -r $testApk
Assert-Exit 'Test APK installation'

Write-Host '=== Run only STO-04 device filesystem tests ==='
Assert-Device
$raw = @(& $adb -s $Serial shell am instrument -w -e class $testClass $runner 2>&1)
$instrumentExit = $LASTEXITCODE
$raw | ForEach-Object { Write-Host $_ }
$report = $raw -join "`n"
if ($instrumentExit -ne 0 -or $report -notmatch 'OK \(3 tests\)' -or $report -match 'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=') {
    throw "STO-04 Android filesystem probe did not pass. Do not claim device support."
}
Write-Host 'STO-04 DEVICE FILESYSTEM PROBE PASSED (3 tests; no pressure applied).'
