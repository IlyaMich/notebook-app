# STO-07 S03-B: one-command, opt-in baseline creation for a disposable emulator.
# Creates a test-only instrumentation fixture in the feature branch; builds APKs;
# installs and executes only on the AVD named STO07_S03B_Disposable.
# No storage exhaustion, database wipe, AVD wipe or deletion is performed.
param(
    [string]$RepoRoot = 'C:\Proj\notebook-app',
    [string]$Serial = 'emulator-5556'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$expectedAvd = 'STO07_S03B_Disposable'
$expectedBranch = 'feature/sto-07-s03b-device-low-storage'
$targetPackage = 'com.notebook.app'
$runner = 'com.notebook.app.test/androidx.test.runner.AndroidJUnitRunner'
$testClass = 'com.notebook.app.data.local.Sto07S03bDeviceBaselineTest'

function Assert-NativeSuccess([string]$action) {
    if ($LASTEXITCODE -ne 0) {
        throw "$action failed (exit code $LASTEXITCODE). Stop without further changes."
    }
}

$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) {
    throw "ADB not found: $adb"
}

function Assert-DisposableAvd {
    $devices = @(& $adb devices -l)
    Assert-NativeSuccess 'ADB devices'
    if (-not (($devices -join "`n") -match "(?m)^$([regex]::Escape($Serial))\s+device\b")) {
        throw "Expected device $Serial is not connected and ready. Connected devices: $($devices -join '; ')"
    }
    $identity = @(& $adb -s $Serial emu avd name)
    Assert-NativeSuccess 'AVD identity check'
    if ($identity.Count -lt 1 -or $identity[0].Trim() -ne $expectedAvd) {
        throw "Safety check failed: $Serial is NOT $expectedAvd; got: $($identity -join '; ')"
    }
    $boot = ([string](& $adb -s $Serial shell getprop sys.boot_completed)).Trim()
    Assert-NativeSuccess 'Android boot check'
    if ($boot -ne '1') {
        throw "$Serial ($expectedAvd) is not fully booted (sys.boot_completed=$boot)."
    }
}

function Ensure-DisposableAvd {
    # Starting the ADB server is safe; no application or device data is changed.
    & $adb start-server | Out-Null
    Assert-NativeSuccess 'ADB server startup'

    $devices = @(& $adb devices -l)
    Assert-NativeSuccess 'ADB device enumeration'
    $serialPattern = '(?m)^' + [regex]::Escape($Serial) + '\s+'
    if (($devices -join "`n") -match ($serialPattern + 'device\b')) {
        Write-Host "Disposable emulator already connected: $Serial"
        Assert-DisposableAvd
        return
    }
    if (($devices -join "`n") -match $serialPattern) {
        throw "$Serial is present but not ready. Do not start a second emulator. ADB: $($devices -join '; ')"
    }

    # Another serial may already be running this disposable AVD. Never start a
    # second copy or treat that device as the original Pixel_Tablet.
    foreach ($line in $devices) {
        if ($line -match '^(emulator-\d+)\s+device\b') {
            $otherSerial = $Matches[1]
            $otherIdentity = @(& $adb -s $otherSerial emu avd name)
            if ($LASTEXITCODE -eq 0 -and $otherIdentity.Count -gt 0 -and
                $otherIdentity[0].Trim() -eq $expectedAvd) {
                throw "$expectedAvd is already connected as $otherSerial, not $Serial. Stop that emulator before retrying; no duplicate launch attempted."
            }
        }
    }

    $emulator = Join-Path $sdk 'emulator\emulator.exe'
    if (-not (Test-Path -LiteralPath $emulator)) {
        throw "Android Emulator executable not found: $emulator"
    }
    $avds = @(& $emulator -list-avds)
    Assert-NativeSuccess 'AVD inventory'
    if ($avds -notcontains $expectedAvd) {
        throw "Required disposable AVD $expectedAvd does not exist. No device started."
    }

    $running = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -match '^(emulator|qemu-system-x86_64)\.exe$' -and
        $_.CommandLine -match '(?i)(?:^|\s)-avd\s+STO07_S03B_Disposable(?:\s|$)'
    })

    if ($running.Count -eq 0) {
        $ports = @(Get-NetTCPConnection -LocalPort 5556,5557 -ErrorAction SilentlyContinue)
        if ($ports.Count -gt 0) {
            throw 'Emulator ports 5556/5557 are occupied. Refusing to start a device or change any data.'
        }
        Write-Host "Starting ONLY $expectedAvd on $Serial (no wipe, no storage pressure)."
        Start-Process -FilePath $emulator -ArgumentList @(
            '-avd', $expectedAvd,
            '-port', '5556',
            '-no-snapshot',
            '-no-boot-anim'
        ) | Out-Null
    } else {
        Write-Host "$expectedAvd already has an emulator process; checking for ADB readiness without launching a duplicate."
    }

    $deadline = [DateTime]::UtcNow.AddMinutes(3)
    $checks = 0
    while ([DateTime]::UtcNow -lt $deadline) {
        $checks++
        $devices = @(& $adb devices -l)
        $deviceReady = (($devices -join "`n") -match ($serialPattern + 'device\b'))
        if ($deviceReady) {
            $identity = @(& $adb -s $Serial emu avd name)
            if ($LASTEXITCODE -eq 0 -and $identity.Count -gt 0) {
                if ($identity[0].Trim() -ne $expectedAvd) {
                    throw "Safety check failed: $Serial belongs to $($identity[0]); expected $expectedAvd."
                }
                $boot = ([string](& $adb -s $Serial shell getprop sys.boot_completed)).Trim()
                if ($LASTEXITCODE -eq 0 -and $boot -eq '1') {
                    Assert-DisposableAvd
                    Write-Host "Disposable emulator booted and verified: $Serial ($expectedAvd)."
                    return
                }
            }
        }
        if ($checks % 10 -eq 0) {
            Write-Host "Waiting for $expectedAvd to finish booting; ADB: $($devices -join '; ')"
        }
        Start-Sleep -Seconds 3
    }
    throw "Disposable emulator did not become ready on $Serial. Check Android Emulator window and 'adb devices -l'. No fixture was created."
}

function Invoke-FixturePhase([string]$phase, [string]$method) {
    Assert-DisposableAvd
    Write-Host "`n=== Instrumentation: $phase ==="
    $testSelector = "${testClass}#${method}"
    $lines = @(& $adb -s $Serial shell am instrument -w `
        -e class $testSelector -e sto07Phase $phase $runner 2>&1)
    $exitCode = $LASTEXITCODE
    $lines | ForEach-Object { Write-Host $_ }
    $output = $lines -join "`n"
    if ($exitCode -ne 0 -or $output -notmatch 'OK \(1 test\)' -or $output -match 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED') {
        throw "S03-B $phase instrumentation did not pass. Stop; inspect the output above."
    }
}

Write-Host '=== Validate repository and device ==='
if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot '.git'))) {
    throw "Repository not found: $RepoRoot"
}
Push-Location $RepoRoot
try {
    $branch = ([string](& git branch --show-current)).Trim()
    Assert-NativeSuccess 'Git branch check'
    if ($branch -ne $expectedBranch) {
        throw "Expected branch $expectedBranch; current branch is $branch. No files were changed."
    }
    Ensure-DisposableAvd

    # The Kotlin fixture is committed separately in androidTest. Never copy or overwrite it.
    $testRel = 'android\app\src\androidTest\java\com\notebook\app\data\local\Sto07S03bDeviceBaselineTest.kt'
    if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot $testRel))) {
        throw "Baseline Kotlin fixture not found in repository: $testRel"
    }
    Write-Host "Using repository Kotlin fixture: $testRel"

    Write-Host "`n=== Build app and instrumentation APKs (no emulator involved) ==="
    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    if (-not (Test-Path -LiteralPath $env:JAVA_HOME)) {
        throw 'Android Studio JBR not found; check JAVA_HOME.'
    }
    Push-Location (Join-Path $RepoRoot 'android')
    try {
        & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
        Assert-NativeSuccess 'APK builds'
    } finally {
        Pop-Location
    }

    $appApk = Join-Path $RepoRoot 'android\app\build\outputs\apk\debug\app-debug.apk'
    $testApk = Join-Path $RepoRoot 'android\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
    if (-not (Test-Path -LiteralPath $appApk)) { throw "APK missing: $appApk" }
    if (-not (Test-Path -LiteralPath $testApk)) { throw "Test APK missing: $testApk" }

    Write-Host "`n=== Install ONLY on $Serial ($expectedAvd) ==="
    Assert-DisposableAvd
    & $adb -s $Serial install -r $appApk
    Assert-NativeSuccess 'App APK install'
    Assert-DisposableAvd
    & $adb -s $Serial install -r $testApk
    Assert-NativeSuccess 'Instrumentation APK install'

    # The fixture deliberately persists in the real app database so it can be
    # reopened in the normal Notebook UI. Re-running the script is idempotent.
    Invoke-FixturePhase 'prepare' 'createBaselineInDisposableAppDatabase'

    Write-Host "`n=== Force-stop ONLY the disposable app before independent verification ==="
    Assert-DisposableAvd
    & $adb -s $Serial shell am force-stop $targetPackage
    Assert-NativeSuccess 'Disposable app force-stop'

    Invoke-FixturePhase 'verify' 'verifyBaselineAfterNewProcess'

    Write-Host "`n=== Launch Notebook on the disposable AVD ==="
    Assert-DisposableAvd
    & $adb -s $Serial shell monkey -p $targetPackage -c android.intent.category.LAUNCHER 1
    Assert-NativeSuccess 'Disposable app launch'
    Write-Host "`n=== Final disposable-AVD free-space check ==="
    & $adb -s $Serial shell df -k /data
    Assert-NativeSuccess 'Storage inspection'

    Write-Host "`nS03-B BASELINE SUCCESSFULLY CREATED AND VERIFIED"
    Write-Host 'Notebook: STO07 S03B Recovery'
    Write-Host 'Note: Baseline before low storage'
    Write-Host 'Content: one persisted blue line shape; revision: 7'
    Write-Host "Verified in a NEW app process on $Serial ($expectedAvd)."
    Write-Host "No low-storage pressure has been applied."
    Write-Host "Repository test fixture: $testRel"
} finally {
    Pop-Location
}
