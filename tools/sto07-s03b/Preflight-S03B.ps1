# STO-07 S03-B: read-only host/device preflight for a controlled low-storage test.
# DOES NOT allocate storage, remove files, wipe an AVD or alter the app database.
param(
    [string]$RepoRoot = 'C:\Proj\notebook-app',
    [string]$Serial = 'emulator-5556'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ExpectedAvd = 'STO07_S03B_Disposable'
$ExpectedBranch = 'feature/sto-07-s03b-device-low-storage'
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'

function Assert-Exit([string]$Operation) {
    if ($LASTEXITCODE -ne 0) { throw "$Operation failed: exit code $LASTEXITCODE" }
}
function Assert-Device {
    $devices = @(& $adb devices -l)
    Assert-Exit 'List ADB devices'
    if (($devices -join "`n") -notmatch ('(?m)^' + [regex]::Escape($Serial) + '\s+device\b')) {
        throw "Device $Serial not ready; run the existing baseline auto-start script to start the disposable AVD."
    }
    $identity = @(& $adb -s $Serial emu avd name)
    Assert-Exit 'Read AVD identity'
    if ($identity.Count -lt 1 -or $identity[0].Trim() -ne $ExpectedAvd) {
        throw "REFUSING: $Serial is not $ExpectedAvd. Reported: $($identity -join ', ')"
    }
    $boot = ([string](& $adb -s $Serial shell getprop sys.boot_completed)).Trim()
    Assert-Exit 'Read boot status'
    if ($boot -ne '1') { throw "AVD is not booted (sys.boot_completed=$boot)." }
}

if (-not (Test-Path -LiteralPath $adb)) { throw "ADB executable not found: $adb" }
if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot '.git'))) { throw "Git repository not found: $RepoRoot" }
Push-Location $RepoRoot
try {
    Write-Host '=== 1. Git branch and fixture ==='
    $branch = ([string](& git branch --show-current)).Trim()
    Assert-Exit 'Read branch'
    if ($branch -ne $ExpectedBranch) { throw "Wrong branch: $branch (expected $ExpectedBranch)" }
    $fixture = 'android\app\src\androidTest\java\com\notebook\app\data\local\Sto07S03bDeviceBaselineTest.kt'
    if (-not (Test-Path -LiteralPath $fixture)) { throw "Baseline fixture missing: $fixture" }
    Write-Host "Branch: $branch"
    Write-Host "Baseline fixture: present"

    Write-Host "`n=== 2. Disposable device identity ==="
    & $adb start-server | Out-Null
    Assert-Exit 'Start ADB'
    Assert-Device
    Write-Host "Confirmed: $Serial = $ExpectedAvd, booted"

    Write-Host "`n=== 3. Host disk budget ==="
    $avdDir = Join-Path $env:USERPROFILE '.android\avd\STO07_S03B_Disposable.avd'
    if (-not (Test-Path -LiteralPath $avdDir)) { throw "Disposable AVD directory not found: $avdDir" }
    $hostDrive = [System.IO.DriveInfo]::new([System.IO.Path]::GetPathRoot($avdDir))
    $hostFreeGiB = [Math]::Round($hostDrive.AvailableFreeSpace / 1GB, 2)
    Write-Host "Host free space on $($hostDrive.Name): $hostFreeGiB GiB"
    if ($hostDrive.AvailableFreeSpace -lt 20GB) {
        throw 'Insufficient host free space for the future disposable-AVD pressure experiment (need >=20 GiB).'
    }

    Write-Host "`n=== 4. Guest /data budget ==="
    $df = @(& $adb -s $Serial shell df -k /data)
    Assert-Exit 'Inspect guest filesystem'
    $df | ForEach-Object { Write-Host $_ }
    $row = ($df | Where-Object { $_ -match '^/dev/' } | Select-Object -Last 1)
    if (-not $row) { throw 'Could not parse /data filesystem information. No storage operation attempted.' }
    $cols = @($row.Trim() -split '\s+')
    if ($cols.Count -lt 5) { throw "Unexpected df output: $row" }
    $freeKiB = [int64]$cols[3]
    $freeGiB = [Math]::Round($freeKiB / 1048576.0, 2)
    if ($freeKiB -lt 262144) { throw "Disposable /data is already low on space: $freeGiB GiB. No new pressure allowed." }
    Write-Host "Guest free storage: $freeGiB GiB"

    Write-Host "`n=== 5. Installed debug app / run-as permission ==="
    $package = @(& $adb -s $Serial shell pm path com.notebook.app)
    Assert-Exit 'Find installed app'
    if (($package -join "`n") -notmatch 'package:') { throw 'Notebook debug app is not installed on the disposable AVD.' }
    $appDir = @(& $adb -s $Serial shell run-as com.notebook.app pwd 2>&1)
    Assert-Exit 'Check run-as debug permission'
    Write-Host "App sandbox: $($appDir -join ', ')"

    Write-Host "`n=== 6. Guest allocation utility availability (inspection only) ==="
    $dd = @(& $adb -s $Serial shell 'command -v dd' 2>&1)
    Assert-Exit 'Check dd utility'
    Write-Host "dd: $($dd -join ', ')"
    $fallocate = @(& $adb -s $Serial shell 'command -v fallocate' 2>&1)
    if ($LASTEXITCODE -eq 0) { Write-Host "fallocate: $($fallocate -join ', ')" }
    else { Write-Host 'fallocate: unavailable (a future test would require a guarded dd fallback)' }

    Write-Host "`n=== 7. Verify original persisted revision-7 baseline ==="
    $selector = 'com.notebook.app.data.local.Sto07S03bDeviceBaselineTest#verifyBaselineAfterNewProcess'
    $result = @(& $adb -s $Serial shell am instrument -w -e class $selector -e sto07Phase verify 'com.notebook.app.test/androidx.test.runner.AndroidJUnitRunner' 2>&1)
    $exit = $LASTEXITCODE
    $result | ForEach-Object { Write-Host $_ }
    if ($exit -ne 0 -or ($result -join "`n") -notmatch 'OK \(1 test\)') {
        throw 'Baseline verification failed. Do not apply storage pressure.'
    }

    Write-Host "`nS03-B PREFLIGHT PASSED — no storage pressure was applied."
    Write-Host 'Baseline: revision 7 + one blue line shape, validated on disposable AVD.'
    Write-Host 'A future pressure test must still establish FAILED, rollback, and safe retry.'
} finally {
    Pop-Location
}
