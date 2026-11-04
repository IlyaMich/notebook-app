# STO-07 S03-B: opt-in, destructive-to-disposable-AVD ONLY storage pressure experiment.
# CAUTION: Temporarily drives the disposable emulator's /data free space to ~16 MiB.
# Never target physical hardware or the Pixel_Tablet AVD. Cleanup runs in finally.
# This script does NOT wipe AVDs or change files on any other Android device.
param(
    [string]$RepoRoot = 'C:\Proj\notebook-app',
    [string]$Serial = 'emulator-5556'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ExpectedAvd = 'STO07_S03B_Disposable'
$ExpectedBranch = 'feature/sto-07-s03b-device-low-storage'
$Package = 'com.notebook.app'
$Runner = 'com.notebook.app.test/androidx.test.runner.AndroidJUnitRunner'
$ClassName = 'com.notebook.app.data.local.Sto07S03bRealStorageRecoveryTest'
$FixtureFile = 'android\app\src\androidTest\java\com\notebook\app\data\local\Sto07S03bRealStorageRecoveryTest.kt'
$FillDir = '/data/local/tmp/sto07_s03b_pressure'
$TargetFreeKiB = 16384L # 16 MiB remains; the large edit must exceed this.
$ChunkKiB = 131072L # 128 MiB max per filler file.
$MaxFillerCount = 80
$Job = $null
$FillStarted = $false
$Cleaned = $false
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$cleanup = Join-Path $PSScriptRoot 'Cleanup-S03B-Pressure.ps1'

function Assert-Exit([string]$action) {
    if ($LASTEXITCODE -ne 0) { throw "$action failed (exit $LASTEXITCODE)" }
}
function Assert-Device {
    $devices = @(& $adb devices -l)
    Assert-Exit 'List ADB devices'
    if (($devices -join "`n") -notmatch ('(?m)^' + [regex]::Escape($Serial) + '\s+device\b')) {
        throw "Device $Serial not connected and ready; do not proceed."
    }
    $identity = @(& $adb -s $Serial emu avd name)
    Assert-Exit 'Read AVD name'
    if ($identity.Count -lt 1 -or $identity[0].Trim() -cne $ExpectedAvd) {
        throw "SAFETY STOP: $Serial is not $ExpectedAvd. Identity=$($identity -join '; ')"
    }
    $boot = ([string](& $adb -s $Serial shell getprop sys.boot_completed)).Trim()
    Assert-Exit 'Check boot status'
    if ($boot -ne '1') { throw "Disposable emulator is not fully booted." }
}
function Get-GuestKiB([string]$path) {
    $lines = @(& $adb -s $Serial shell df -k $path)
    Assert-Exit "Read filesystem usage for $path"
    $row = @($lines | Where-Object { $_ -match '^/dev/' } | Select-Object -Last 1)
    if ($row.Count -ne 1) { throw "Unexpected guest df for $path : $($lines -join '; ')" }
    $parts = @($row[0].Trim() -split '\s+')
    if ($parts.Count -lt 5) { throw "Cannot parse guest df: $($row[0])" }
    return [pscustomobject]@{ Device = $parts[0]; Total = [int64]$parts[1]; Free = [int64]$parts[3] }
}
function Get-PressureLogs {
    $result = @(& $adb -s $Serial logcat -d -s 'STO07_S03B_PRESSURE:I' '*:S' 2>&1)
    Assert-Exit 'Read pressure diagnostic logs'
    return $result -join "`n"
}
function Wait-ForMarker([string]$marker, [int]$seconds) {
    $end = [DateTime]::UtcNow.AddSeconds($seconds)
    while ([DateTime]::UtcNow -lt $end) {
        $logs = Get-PressureLogs
        if ($logs -match [regex]::Escape($marker)) {
            if ($null -ne $Job -and $Job.State -ne 'Running' -and $marker -ne 'S03B_RECOVERED') {
                $jobOutput = (Receive-Job -Job $Job -Keep | Out-String)
                throw "Instrumentation exited before the host could act on $marker. Output: $jobOutput"
            }
            return
        }
        if ($null -ne $Job -and $Job.State -ne 'Running') {
            $jobOutput = (Receive-Job -Job $Job -Keep | Out-String)
            throw "Instrumentation ended before $marker. Output: $jobOutput; logs: $logs"
        }
        Start-Sleep -Seconds 2
    }
    throw "Timed out waiting for $marker. Recent logs: $(Get-PressureLogs)"
}
function Signal-Test([string]$filename) {
    Assert-Device
    & $adb -s $Serial shell run-as $Package touch "files/$filename"
    Assert-Exit "Signal test via $filename"
}
function Remove-PressureFiles {
    if ($FillStarted -and -not $Cleaned) {
        Assert-Device
        & $cleanup -Serial $Serial
        $script:Cleaned = $true
    }
}
function Invoke-Verification([string]$method, [string]$phase) {
    Assert-Device
    $selector = "$ClassName#$method"
    $output = @(& $adb -s $Serial shell am instrument -w -e class $selector -e sto07Phase $phase $Runner 2>&1)
    $code = $LASTEXITCODE
    $output | ForEach-Object { Write-Host $_ }
    if ($code -ne 0 -or ($output -join "`n") -notmatch 'OK \(1 test\)') {
        throw "Verification phase $phase failed."
    }
}

if (-not (Test-Path -LiteralPath $adb)) { throw "ADB not found at $adb" }
if (-not (Test-Path -LiteralPath $cleanup)) { throw "Cleanup script missing: $cleanup" }
if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot '.git'))) { throw "Repo missing: $RepoRoot" }
Push-Location $RepoRoot
try {
    Write-Host '=== Safety: branch, exact AVD identity and preexisting baseline ==='
    $branch = ([string](& git branch --show-current)).Trim()
    Assert-Exit 'Read branch'
    if ($branch -cne $ExpectedBranch) { throw "Wrong branch $branch (expected $ExpectedBranch)" }
    Assert-Device
    if (-not (Test-Path 'android\app\src\androidTest\java\com\notebook\app\data\local\Sto07S03bDeviceBaselineTest.kt')) {
        throw 'Previously verified baseline fixture is missing; stop.'
    }

    $avdDir = Join-Path $env:USERPROFILE '.android\avd\STO07_S03B_Disposable.avd'
    if (-not (Test-Path -LiteralPath $avdDir)) { throw 'Disposable AVD directory missing.' }
    $drive = [System.IO.DriveInfo]::new([System.IO.Path]::GetPathRoot($avdDir))
    if ($drive.AvailableFreeSpace -lt 20GB) { throw 'Insufficient host free space (<20 GiB).' }
    $guest = Get-GuestKiB '/data'
    $guestTmp = Get-GuestKiB '/data/local/tmp'
    if ($guest.Device -cne $guestTmp.Device) { throw '/data/local/tmp is not on the /data filesystem.' }
    if ($guest.Free -lt 262144) { throw 'Guest /data already has <256 MiB free; do not add pressure.' }
    if ($guest.Free -gt 8388608) { throw 'Guest available space exceeds 8 GiB; unexpected disposable AVD size.' }
    Write-Host "Host free: $([math]::Round($drive.AvailableFreeSpace / 1GB, 2)) GiB; guest free: $([math]::Round($guest.Free / 1048576.0, 2)) GiB"

    # Use the tracked androidTest fixture. Do not copy or modify source files.
    $path = Join-Path $RepoRoot $FixtureFile
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Recovery Kotlin fixture not found in repository: $FixtureFile"
    }
    Write-Host "Using repository Kotlin fixture: $FixtureFile"

    Write-Host '=== Build app/test APKs (without touching device) ==='
    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    Push-Location (Join-Path $RepoRoot 'android')
    try {
        & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
        Assert-Exit 'Build APKs'
    } finally { Pop-Location }

    $appApk = Join-Path $RepoRoot 'android\app\build\outputs\apk\debug\app-debug.apk'
    $testApk = Join-Path $RepoRoot 'android\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
    if (-not (Test-Path $appApk) -or -not (Test-Path $testApk)) { throw 'Built APK missing.' }
    Assert-Device
    & $adb -s $Serial install -r $appApk
    Assert-Exit 'Install app APK'
    Assert-Device
    & $adb -s $Serial install -r $testApk
    Assert-Exit 'Install test APK'

    Write-Host '=== Verify baseline in app database before any pressure ==='
    $baselineSelector = 'com.notebook.app.data.local.Sto07S03bDeviceBaselineTest#verifyBaselineAfterNewProcess'
    $baseline = @(& $adb -s $Serial shell am instrument -w -e class $baselineSelector -e sto07Phase verify $Runner 2>&1)
    $baselineCode = $LASTEXITCODE
    if ($baselineCode -ne 0 -or ($baseline -join "`n") -notmatch 'OK \(1 test\)') {
        throw "Baseline fixture verification failed: $($baseline -join '; ')"
    }
    Write-Host 'Original baseline verified.'

    # Protect against mixing files from another run. Manual cleanup is available.
    & $adb -s $Serial shell test -e $FillDir | Out-Null
    $directoryStatus = $LASTEXITCODE
    if ($directoryStatus -eq 0) {
        throw "A previous pressure directory exists ($FillDir). Run guarded Cleanup-S03B-Pressure.ps1 before retrying."
    }
    if ($directoryStatus -ne 1) { throw "Could not inspect pressure directory (exit $directoryStatus)." }

    Write-Host '=== Start instrumentation while free storage is healthy ==='
    & $adb -s $Serial logcat -c
    Assert-Exit 'Clear disposable emulator log buffer'
    $selector = "$ClassName#deviceFullFailureAndRetryInProductionRoom"
    $Job = Start-Job -ScriptBlock {
        param($adbPath, $device, $selected, $runnerName)
        $result = @(& $adbPath -s $device shell am instrument -w -e class $selected -e sto07Phase pressure $runnerName 2>&1)
        $status = $LASTEXITCODE
        $result
        "S03B_ADB_EXIT_CODE=$status"
    } -ArgumentList $adb, $Serial, $selector, $Runner
    Wait-ForMarker 'S03B_ARMED' 180
    Write-Host 'Instrumented app is armed; beginning bounded device-only pressure.'

    Assert-Device
    & $adb -s $Serial shell mkdir $FillDir
    Assert-Exit 'Create dedicated pressure directory'
    $FillStarted = $true

    Write-Host '=== Controlled /data pressure, with fixed 16 MiB free-space floor ==='
    $count = 0
    while ($true) {
        $space = Get-GuestKiB '/data'
        if ($space.Free -le ($TargetFreeKiB + 1024)) { break }
        if ($count -ge $MaxFillerCount) { throw 'Refusing to create more than 80 bounded filler files.' }
        $sizeKiB = [math]::Min($ChunkKiB, $space.Free - $TargetFreeKiB)
        if ($sizeKiB -lt 1) { break }
        $name = 'fill_{0:D3}.bin' -f $count
        $file = "$FillDir/$name"
        Assert-Device
        $sizeBytes = [int64]$sizeKiB * 1024L
        & $adb -s $Serial shell fallocate -l $sizeBytes $file
        Assert-Exit "Allocate disposable filler $name"
        $after = Get-GuestKiB '/data'
        if ($after.Free -ge $space.Free) {
            throw 'fallocate did not consume real guest filesystem space; refusing to continue.'
        }
        $count++
        if ($count % 5 -eq 0) {
            Write-Host "Filler files: $count; guest free: $([math]::Round($after.Free / 1024.0, 1)) MiB"
        }
    }
    $pressured = Get-GuestKiB '/data'
    Write-Host "Pressure applied: $count dedicated files; guest free $([math]::Round($pressured.Free / 1024.0, 1)) MiB"
    if ($pressured.Free -lt 8192 -or $pressured.Free -gt 32768) {
        throw 'Guest free space outside guarded 8–32 MiB band. Cleanup required; no edit attempted.'
    }

    Write-Host '=== Trigger real Room autosave under low guest storage ==='
    Signal-Test 'sto07-s03b-pressure-go.flag'
    Wait-ForMarker 'S03B_FAILED' 200
    Write-Host 'Observed FAILED, pending 1, revision 7. Releasing guest pressure before retry.'

    Remove-PressureFiles
    $recoveredSpace = Get-GuestKiB '/data'
    if ($recoveredSpace.Free -lt 262144) {
        throw 'Cleanup did not restore at least 256 MiB. Do not retry.'
    }
    Signal-Test 'sto07-s03b-pressure-recover.flag'
    Wait-ForMarker 'S03B_RECOVERED' 200
    Wait-Job -Job $Job -Timeout 30 | Out-Null
    if ($Job.State -ne 'Completed') { throw "Instrumentation job did not finish: $($Job.State)" }
    $output = @(Receive-Job -Job $Job)
    $output | ForEach-Object { Write-Host $_ }
    if (($output -join "`n") -notmatch 'OK \(1 test\)' -or
        ($output -join "`n") -notmatch 'S03B_ADB_EXIT_CODE=0') {
        throw 'Pressure instrumentation did not return a passing result.'
    }
    Write-Host '=== Independent new-process recovery verification ==='
    Assert-Device
    & $adb -s $Serial shell am force-stop $Package
    Assert-Exit 'Force-stop disposable app'
    Invoke-Verification 'verifyRecoveryAfterFreshProcess' 'verifyPressure'
    Write-Host '=== Final guest free storage ==='
    & $adb -s $Serial shell df -k /data
    Assert-Exit 'Final storage check'
    Write-Host 'S03-B REAL LOW-STORAGE FAILURE / ROLLBACK / RETRY / PROCESS REOPEN PASSED'
    Write-Host 'The original revision-7 baseline was preserved; temporary pressure note recovered to revision 8.'
} finally {
    if ($FillStarted -and -not $Cleaned) {
        try {
            Write-Host '=== FINALLY: recover dedicated guest filler files ==='
            Remove-PressureFiles
        } catch {
            Write-Warning "AUTOMATIC CLEANUP FAILED: $_"
            Write-Warning "Run $cleanup manually ONLY after verifying the disposable AVD identity."
        }
    }
    if ($null -ne $Job) {
        if ($Job.State -eq 'Running') { Stop-Job -Job $Job -ErrorAction SilentlyContinue }
        Remove-Job -Job $Job -Force -ErrorAction SilentlyContinue
    }
    Pop-Location
}
