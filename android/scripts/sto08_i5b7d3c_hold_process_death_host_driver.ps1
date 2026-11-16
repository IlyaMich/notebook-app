[CmdletBinding()]
param(
    [ValidateSet('Audit','Execute')][string]$Mode = 'Audit',
    [ValidateSet(26,37)][int]$ApiLevel = 26
)
$ErrorActionPreference = 'Stop'
$repo = 'C:\Proj\notebook-app-sto08-i4b-2b'
$head = '2a802060b871a2ce4d6d1c4ce5e7786176bef37d'
$branch = 'feature/sto-08-c3a2-i4b-2b-byte-inspector'
$testRel = 'android/app/src/androidTest/java/com/notebook/app/data/assets/ExportCommittedHoldProcessDeathAndroidTest.kt'
$driverRel = 'android/scripts/sto08_i5b7d3c_hold_process_death_host_driver.ps1'
$testSha = 'E76024ED3D7C942053346D4EB95F2D25F797084FA6FC5C84E718D70E9DF1A293'
$sourcePins = @{
    'android/app/src/main/java/com/notebook/app/data/assets/ExportAtomicHoldAdmission.kt' = '3B45687EF7C8E4744EAE178E3CE751D2008D4FF61CF173F8549C10043A20BC99'
    'android/app/src/main/java/com/notebook/app/data/assets/ExportHoldRecoveryInventory.kt' = '313D7824001F2A6191C3451C3504DE0B1F2236DDAC20598F21E4D0090544A595'
    'android/app/src/main/java/com/notebook/app/data/local/dao/ExportHoldAdmissionDao.kt' = 'BB89C5A6E6DC93F17D84C0EB0FE7E902EAF61A4DCBA490A54CA0C1C6697C521D'
    'android/app/src/main/java/com/notebook/app/data/local/ExportHoldSchemaGuards.kt' = 'CE1CEFA53B4AFF07AA45AD5EBE95A61F3783EACAD5576F7451370336606C7B08'
}
$adbSerial = 'emulator-5554'
$targetPackage = 'com.notebook.app'
$testPackage = 'com.notebook.app.test'
$instrumentation = 'com.notebook.app.test/androidx.test.runner.AndroidJUnitRunner'
$testClass = 'com.notebook.app.data.assets.ExportCommittedHoldProcessDeathAndroidTest'
$prepareMethod = 'prepareCommittedHoldsAndAwaitExternalProcessDeath'
$verifyMethod = 'verifyCommittedHoldsInFreshProcess'
# Proven local APKs: both must be re-pulled and byte-compared from the actual
# target emulator BEFORE PREPARE and again immediately BEFORE FORCE-STOP.
$appApkRel = 'android/app/build/outputs/apk/debug/app-debug.apk'
$testApkRel = 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
$appApkSha = '17D2F162715BAC6555BF36806CFD26EB1F713156466B9DB5CB569595C6D4A412'
$testApkSha = '0CFBC64614E2C44408E444B8B9DE0BC53676C4484B6012D17360D11AFD9BEF8F'

function Assert([bool]$ok, [string]$errorMessage) { if (-not $ok) { throw $errorMessage } }
function Here([string]$rel) { Join-Path $repo ($rel -replace '/', '\') }
function Git([string[]]$arguments) {
    $out = @(& git.exe -C $repo @arguments)
    Assert ($LASTEXITCODE -eq 0) "Git failure: $($arguments -join ' ')"
    return $out
}
function CheckRepo {
    Assert ((@(Git @('branch','--show-current')) | Select-Object -First 1) -ceq $branch) 'Unexpected branch'
    Assert ((@(Git @('rev-parse','HEAD')) | Select-Object -First 1) -ceq $head) 'Unexpected commit; host driver must be re-reviewed'
    Assert (@(Git @('diff','--cached','--name-only')).Count -eq 0) 'Staged Git changes; refuse'
    $actual = @(& git.exe -C $repo status --porcelain=v1 --untracked-files=all | Sort-Object)
    Assert ($LASTEXITCODE -eq 0) 'Git status failed'
    $wanted = @("?? $testRel", "?? $driverRel") | Sort-Object
    Assert (($actual -join "`n") -ceq ($wanted -join "`n")) 'Expected exactly two untracked harness files and no other changes'
    foreach ($rel in @($sourcePins.Keys | Sort-Object)) {
        $actualHash = (Get-FileHash -LiteralPath (Here $rel) -Algorithm SHA256).Hash
        Assert ($actualHash -ceq $sourcePins[$rel]) "Protected source hash mismatch: $rel"
    }
    Assert ((Get-FileHash -LiteralPath (Here $testRel) -Algorithm SHA256).Hash -ceq $testSha) 'Process-death instrumentation class changed'
    Assert ((Resolve-Path -LiteralPath $PSCommandPath).Path -ceq (Resolve-Path -LiteralPath (Here $driverRel)).Path) 'Host driver must run from exact protected worktree file'
    Write-Host 'VERIFIED local HEAD, exact two-file untracked inventory, unchanged protected sources and exact instrumentation hash.'
}
# Capture stdout/stderr independently so PowerShell 5.1 never rewraps native
# stderr as RemoteException. ADB exit codes remain mandatory.
function Adb([string[]]$arguments,[int[]]$allowedExitCodes = @(0)) {
    $all = @('-s',$adbSerial) + $arguments
    foreach ($part in $all) {
        Assert ($part -notmatch '[\r\n"]') 'Unsafe ADB argument; refuse'
    }
    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $script:adbPath
    $psi.Arguments = (@($all | ForEach-Object { '"' + $_ + '"' }) -join ' ')
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $psi
    try {
        Assert ($process.Start()) 'Cannot launch exact installed ADB executable'
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        $exitCode = $process.ExitCode
    } finally { $process.Dispose() }
    Assert ($allowedExitCodes -contains $exitCode) "ADB exit $exitCode for $($all -join ' '); stderr=$stderr; stdout=$stdout"
    # Some ADB subcommands write progress messages to stderr; no stderr text is
    # accepted as proof. All essential facts are independently parsed/hash-checked.
    return @($stdout -split '\r?\n' | Where-Object { $_.Trim().Length -gt 0 })
}
function ReadTargetPid {
    $text = (@(Adb @('shell','pidof',$targetPackage) @(0,1)) -join ' ').Trim()
    if ($text.Length -eq 0) { return $null }
    Assert ($text -match '^\d+$') "Ambiguous target PID: $text"
    return [int]$text
}
function CheckDevice([bool]$requireIdle = $true) {
    $devices = @(& $script:adbPath devices -l)
    Assert ($LASTEXITCODE -eq 0) 'adb devices failed'
    $attached = @($devices | Where-Object {
        $_.Trim().Length -gt 0 -and $_ -notmatch '^List of devices attached'
    })
    Assert ($attached.Count -eq 1 -and $attached[0] -match '^emulator-5554\s+device(?:\s|$)') 'Requires emulator-5554 as sole attached device (no offline/extra entries)'
    Assert ((@(Adb @('shell','getprop','ro.build.version.sdk')) -join '').Trim() -ceq "$ApiLevel") 'Incorrect Android API level'
    Assert ((@(Adb @('shell','getprop','sys.boot_completed')) -join '').Trim() -ceq '1') 'Device not fully booted'
    $avdLines = @(Adb @('emu','avd','name')) | ForEach-Object { $_.Trim() }
    $expectedAvd = if ($ApiLevel -eq 26) { 'STO08_API26_Disposable' } else { 'STO07_S03B_Disposable' }
    Assert (($avdLines -contains $expectedAvd) -and ($avdLines -contains 'OK')) "Unexpected AVD name; expected $expectedAvd"
    $installedTarget = @(Adb @('shell','pm','path',$targetPackage)) -join "`n"
    $installedTest = @(Adb @('shell','pm','path',$testPackage)) -join "`n"
    Assert ($installedTarget -match 'package:' -and $installedTest -match 'package:') 'Both debug/test APKs must already be installed; driver never installs them'
    $instruments = @(Adb @('shell','pm','list','instrumentation')) -join "`n"
    Assert ($instruments.Contains($instrumentation) -and $instruments.Contains("target=$targetPackage")) 'Installed instrumentation component/target mismatch'
    if ($requireIdle) {
        Assert ($null -eq (ReadTargetPid)) 'Target app process already running; do not force-stop or disturb it'
    }
    Write-Host "VERIFIED sole $adbSerial, API $ApiLevel, exact AVD $expectedAvd, installed target/test packages and runner."
}
function OneInstalledBaseApk([string]$packageName) {
    $paths = @(Adb @('shell','pm','path',$packageName))
    Assert ($paths.Count -eq 1) "Expected exactly one installed (non-split) APK for $packageName"
    $match = [regex]::Match($paths[0].Trim(),
        '^package:(/data/app/[A-Za-z0-9_./~+@=\-]+/base\.apk)$')
    Assert $match.Success "Unexpected installed APK path for $packageName"
    Assert ($match.Groups[1].Value -notmatch '/\.\.?/') 'Installed APK path contains traversal'
    return $match.Groups[1].Value
}
function CheckInstalledApkBytes([string]$phase,[string]$evidenceRoot) {
    Assert (@('before-prepare','before-kill') -ccontains $phase) 'Unexpected APK verification phase'
    CheckRepo
    CheckDevice ($phase -ceq 'before-prepare')
    $appLocal = Here $appApkRel
    $testLocal = Here $testApkRel
    Assert (Test-Path -LiteralPath $appLocal -PathType Leaf) 'Missing pinned local app APK'
    Assert (Test-Path -LiteralPath $testLocal -PathType Leaf) 'Missing pinned local test APK'
    Assert ((Get-FileHash -LiteralPath $appLocal -Algorithm SHA256).Hash -ceq $appApkSha) 'Local app APK SHA changed'
    Assert ((Get-FileHash -LiteralPath $testLocal -Algorithm SHA256).Hash -ceq $testApkSha) 'Local test APK SHA changed'
    $appRemote = OneInstalledBaseApk $targetPackage
    $testRemote = OneInstalledBaseApk $testPackage
    $phaseDir = Join-Path $evidenceRoot "apk-$phase"
    Assert (-not (Test-Path -LiteralPath $phaseDir)) 'APK evidence phase directory already exists'
    [void][IO.Directory]::CreateDirectory($phaseDir)
    $appPulled = Join-Path $phaseDir 'installed-app-base.apk'
    $testPulled = Join-Path $phaseDir 'installed-test-base.apk'
    $null = Adb @('pull',$appRemote,$appPulled)
    $null = Adb @('pull',$testRemote,$testPulled)
    foreach ($file in @($appPulled,$testPulled)) {
        Assert (Test-Path -LiteralPath $file -PathType Leaf) "Missing pulled APK: $file"
        Assert ((Get-Item -LiteralPath $file).Length -gt 1024) "Unexpected pulled APK size: $file"
    }
    $actualApp = (Get-FileHash -LiteralPath $appPulled -Algorithm SHA256).Hash
    $actualTest = (Get-FileHash -LiteralPath $testPulled -Algorithm SHA256).Hash
    Assert ($actualApp -ceq $appApkSha) "Installed APP APK SHA mismatch during $phase; NO KILL"
    Assert ($actualTest -ceq $testApkSha) "Installed TEST APK SHA mismatch during $phase; NO KILL"
    CheckRepo
    Assert ((Get-FileHash -LiteralPath $appLocal -Algorithm SHA256).Hash -ceq $appApkSha) 'Local app changed during APK verification'
    Assert ((Get-FileHash -LiteralPath $testLocal -Algorithm SHA256).Hash -ceq $testApkSha) 'Local test changed during APK verification'
    Write-Host "VERIFIED $phase installed APP=$actualApp TEST=$actualTest AVD=API$ApiLevel."
}
function PhaseOutput([string]$method,[string]$phase,[string]$nonce) {
    return @('-s',$adbSerial,'shell','am','instrument','-w','-r',
        '-e','sto08HoldPdPhase',$phase,'-e','sto08HoldRun',$nonce,
        '-e','class',"$testClass#$method",$instrumentation)
}
function LogMarkers([string]$pattern) {
    $lines = @(Adb @('logcat','-d','-v','brief','-s','STO08_PD_HOLD:I','*:S'))
    return @($lines | Where-Object { $_.Contains($pattern) })
}

Write-Host '=== STO-08 I5-B7D-3C: HOLD PROCESS-DEATH HOST DRIVER ==='
Write-Host 'Default Audit mode: no ADB, APK, kill, fixture, Gradle, source, SDK or Git writes.'
Write-Host 'Execute mode is NOT approved by the compile-only checkpoint and requires separate explicit authorization.'
CheckRepo
if ($Mode -eq 'Audit') {
    Write-Host 'AUDIT PASSED: exact local harness sources; no device touched. Execute remains UNAUTHORIZED.'
    exit 0
}

$approved = Read-Host "To AUTHORIZE API $ApiLevel app process termination on the named disposable AVD, type EXECUTE I5-B7D-3D API$ApiLevel"
Assert ($approved -ceq "EXECUTE I5-B7D-3D API$ApiLevel") 'Explicit authorization was not given'
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adbPath = Join-Path $sdk 'platform-tools\adb.exe'
Assert (Test-Path -LiteralPath $adbPath -PathType Leaf) 'Installed adb.exe not found'
CheckDevice
$nonce = [guid]::NewGuid().ToString('D')
Assert ([guid]::Parse($nonce).ToString('D') -ceq $nonce) 'Generated nonce invalid'
$previous = @(LogMarkers "run=$nonce")
Assert ($previous.Count -eq 0) 'Non-fresh run nonce already in logcat'
$runDir = Join-Path $env:TEMP "sto08-pd-hold-$nonce"
Assert (-not (Test-Path -LiteralPath $runDir)) 'Host evidence directory already exists'
[void][IO.Directory]::CreateDirectory($runDir)
$prepareOut = Join-Path $runDir 'prepare_stdout.txt'
$prepareErr = Join-Path $runDir 'prepare_stderr.txt'
$verifyOut = Join-Path $runDir 'verify_stdout.txt'
Write-Host '=== Pre-PREPARE: re-pull installed APK bytes and prove exact emulator identity ==='
CheckInstalledApkBytes 'before-prepare' $runDir
$arguments = PhaseOutput $prepareMethod 'hold-prepare' $nonce
Write-Host "Launching dedicated PREPARE instrumentation, nonce=$nonce. Evidence: $runDir"
$child = Start-Process -FilePath $adbPath -ArgumentList ($arguments -join ' ') -PassThru `
    -RedirectStandardOutput $prepareOut -RedirectStandardError $prepareErr
$marker = $null
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    Start-Sleep -Seconds 1
    $found = @(LogMarkers "READY_TO_KILL run=$nonce")
    Assert ($found.Count -le 1) 'Multiple READY markers for this nonce; DO NOT kill'
    if ($found.Count -eq 1) { $marker = $found[0]; break }
    Assert (-not $child.HasExited) 'Prepare instrumentation exited before READY marker; DO NOT kill'
}
Assert ($null -ne $marker) 'Timed out without exact READY marker; DO NOT kill'
$match = [regex]::Match($marker,'READY_TO_KILL run=([0-9a-f-]{36}) pid=(\d+) operation=([0-9a-f-]{36})')
Assert ($match.Success -and $match.Groups[1].Value -ceq $nonce) 'READY marker format/nonce mismatch'
$preparedPid = [int]$match.Groups[2].Value
$operationId = $match.Groups[3].Value
Assert ($preparedPid -gt 0 -and $preparedPid -eq (ReadTargetPid)) 'READY PID does not match active target app PID; DO NOT kill'
Assert (-not $child.HasExited) 'Prepare instrumentation already exited; DO NOT kill'
Write-Host '=== Pre-TERMINATION: re-pull installed APKs and revalidate AVD ==='
CheckInstalledApkBytes 'before-kill' $runDir
CheckDevice $false
$foundAgain = @(LogMarkers "READY_TO_KILL run=$nonce")
Assert ($foundAgain.Count -eq 1 -and $foundAgain[0] -ceq $marker) 'READY marker changed or disappeared; NO KILL'
Assert ($preparedPid -eq (ReadTargetPid)) 'Target process PID changed during APK verification; NO KILL'
Assert (-not $child.HasExited) 'Prepare instrumentation exited before termination; NO KILL'
# This is the ONLY termination call, after exact nonce/marker/PID/AVD and live-process checks.
Write-Host "VERIFIED post-commit READY marker for exact PID $preparedPid; terminating ONLY $targetPackage on API $ApiLevel."
$null = Adb @('shell','am','force-stop',$targetPackage)
Start-Sleep -Seconds 2
Assert ($null -eq (ReadTargetPid)) 'Target PID still present after force-stop; process death not proven'
Assert ($child.WaitForExit(15000)) 'Prepare instrumentation transport did not terminate after app force-stop'
# Prepare instrumentation abnormal exit is EXPECTED but is NEVER itself a pass criterion.
$verifyArgs = PhaseOutput $verifyMethod 'hold-verify' $nonce
Write-Host 'Launching fresh VERIFY instrumentation without reinstalling APKs or clearing app data.'
# Capture the fresh VERIFY runner in separate host evidence streams. Native
# PowerShell 5.1 stderr must never become a RemoteException pipeline object.
$verifyErr = Join-Path $runDir 'verify_stderr.txt'
$verifyChild = Start-Process -FilePath $adbPath -ArgumentList ($verifyArgs -join ' ') -PassThru `
    -RedirectStandardOutput $verifyOut -RedirectStandardError $verifyErr
Assert ($verifyChild.WaitForExit(90000)) 'VERIFY instrumentation timed out; preserve fixture and host evidence'
$verifyChild.WaitForExit()
$verifyExit = $verifyChild.ExitCode
Assert (Test-Path -LiteralPath $verifyOut -PathType Leaf) 'Missing fresh VERIFY stdout evidence'
Assert (Test-Path -LiteralPath $verifyErr -PathType Leaf) 'Missing fresh VERIFY stderr evidence'
$verifyLines = @(Get-Content -LiteralPath $verifyOut)
Assert ((Get-Item -LiteralPath $verifyErr).Length -eq 0) 'Unexpected VERIFY stderr; preserve evidence and investigate'
# ADB's process exit is not an AndroidJUnitRunner verdict. In particular,
# an otherwise successful `am instrument` may yield a nonzero host exit.
# Require a complete, unique, one-test protocol instead; the independently
# checked nonce/PID/operation marker below is also mandatory.
$verifyText = $verifyLines -join "`n"
$verifyStatuses = @($verifyLines | Where-Object { $_ -match '^INSTRUMENTATION_STATUS_CODE: -?\d+$' })
Assert ($verifyStatuses.Count -eq 2 -and
    $verifyStatuses[0] -ceq 'INSTRUMENTATION_STATUS_CODE: 1' -and
    $verifyStatuses[1] -ceq 'INSTRUMENTATION_STATUS_CODE: 0') 'Fresh VERIFY start/pass statuses not exactly proven'
Assert (@($verifyLines | Where-Object { $_ -ceq "INSTRUMENTATION_STATUS: test=$verifyMethod" }).Count -eq 2) 'Unexpected VERIFY method evidence'
Assert (@($verifyLines | Where-Object { $_ -ceq "INSTRUMENTATION_STATUS: class=$testClass" }).Count -eq 2) 'Unexpected VERIFY class evidence'
Assert (@($verifyLines | Where-Object { $_.Trim() -ceq 'OK (1 test)' }).Count -eq 1) 'Fresh VERIFY JUnit 1/1 success not proven'
Assert (@($verifyLines | Where-Object { $_.Trim() -ceq 'INSTRUMENTATION_CODE: -1' }).Count -eq 1) 'Fresh VERIFY instrumentation success code missing or ambiguous'
Assert (-not [regex]::IsMatch($verifyText,
    '(?im)FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|INSTRUMENTATION_RESULT:\s*shortMsg=|INSTRUMENTATION_STATUS_CODE:\s*-[0-9]+')) 'Fresh VERIFY runner contains a failure or error'
$verified = @(LogMarkers "VERIFY_PASSED run=$nonce")
Assert ($verified.Count -eq 1) 'Missing or ambiguous fresh VERIFY_PASSED marker'
$matchVerify = [regex]::Match($verified[0],
    'VERIFY_PASSED run=([0-9a-f-]{36}) oldPid=(\d+) newPid=(\d+) operation=([0-9a-f-]{36})')
Assert ($matchVerify.Success -and $matchVerify.Groups[1].Value -ceq $nonce) 'Verify nonce mismatch'
Assert ([int]$matchVerify.Groups[2].Value -eq $preparedPid -and
    [int]$matchVerify.Groups[3].Value -ne $preparedPid) 'New-process PID transition not proven'
Assert ($matchVerify.Groups[4].Value -ceq $operationId) 'Recovered operation ID mismatch'
CheckRepo
# Do not silently discard the host transport status: record it after all
# Android/JUnit/unique recovery-marker and repository evidence is proven.
Write-Host "OBSERVED VERIFY_ADB_HOST_EXIT=$verifyExit; Android/JUnit/nonce/PID/operation proof independently PASSED."
Write-Host "=== I5-B7D-3D API $ApiLevel EXACT PROCESS-DEATH HOLD RETENTION GATE PASSED ==="
Write-Host "nonce=$nonce preparePid=$preparedPid verifyPid=$($matchVerify.Groups[3].Value) operation=$operationId"
Write-Host 'Only isolated Room holds survived; NO protected receipt, byte verification, release, VERIFIED or GC proved.'
