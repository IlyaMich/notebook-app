# STO-08 V5 R1: promotable, fail-closed process-death host driver.
# Audit is read-only (Git, file hashes, local SDK, read-only ADB). NO GRADLE.
# Install and Execute are distinct, separately-approved modes with exact typed
# confirmations. Neither alters Git, project sources, build outputs or main DB.
# Install allows only pinned debug and androidTest APKs on the sole disposable AVD.
# Execute allows only isolated process-death instrumentation on that AVD and
# a single explicit `am force-stop com.notebook.app` after post-commit proof.
# On failure preserve test fixture/evidence. Never auto-retry or delete it.
[CmdletBinding()]
param(
    [ValidateSet('Audit','Install','Execute')][string]$Mode = 'Audit',
    [ValidateSet(26,37)][int]$ApiLevel = 37
)
$ErrorActionPreference = 'Stop'

$repo = 'C:\Proj\notebook-app-sto08-i4b-2b'
$branch = 'feature/sto-08-c3a2-i4b-2b-byte-inspector'
$baselineHead = '155d4f4f5f79375dbd87216ac73256aa370fe2e8'
$main = '9226f1850fa6c53612ace5bd79f99954a1c77259'
$driverRel = 'android/scripts/sto08_i5b7d3c_hold_process_death_host_driver.ps1'
$baselineDriverGitBlob = '2abc131254b26da734feabb8ae6b7fc76f2d2602'
$allowedDocumentationChanges = @(
    'docs/architecture/sto_08_i5b2_export_hold_contract_v0_1.md',
    'docs/architecture/sto_08_i5b4_export_hold_persistence_decision_packet_v0_1.md',
    'docs/architecture/sto_08_i5b7d_lifecycle_recovery_decision_packet_v0_1.md'
)
$testRel = 'android/app/src/androidTest/java/com/notebook/app/data/assets/ExportCommittedHoldProcessDeathAndroidTest.kt'
$testSha = 'E76024ED3D7C942053346D4EB95F2D25F797084FA6FC5C84E718D70E9DF1A293'
$sourcePins = [ordered]@{
    'android/app/src/main/java/com/notebook/app/data/assets/ExportAtomicHoldAdmission.kt' = '3B45687EF7C8E4744EAE178E3CE751D2008D4FF61CF173F8549C10043A20BC99'
    'android/app/src/main/java/com/notebook/app/data/assets/ExportHoldRecoveryInventory.kt' = '313D7824001F2A6191C3451C3504DE0B1F2236DDAC20598F21E4D0090544A595'
    'android/app/src/main/java/com/notebook/app/data/local/dao/ExportHoldAdmissionDao.kt' = 'BB89C5A6E6DC93F17D84C0EB0FE7E902EAF61A4DCBA490A54CA0C1C6697C521D'
    'android/app/src/main/java/com/notebook/app/data/local/ExportHoldSchemaGuards.kt' = 'CE1CEFA53B4AFF07AA45AD5EBE95A61F3783EACAD5576F7451370336606C7B08'
}
$target = 'com.notebook.app'
$testPackage = 'com.notebook.app.test'
$runner = 'com.notebook.app.test/androidx.test.runner.AndroidJUnitRunner'
$testClass = 'com.notebook.app.data.assets.ExportCommittedHoldProcessDeathAndroidTest'
$prepareMethod = 'prepareCommittedHoldsAndAwaitExternalProcessDeath'
$verifyMethod = 'verifyCommittedHoldsInFreshProcess'
$appApkRel = 'android/app/build/outputs/apk/debug/app-debug.apk'
$testApkRel = 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
$appApkSha = '17D2F162715BAC6555BF36806CFD26EB1F713156466B9DB5CB569595C6D4A412'
$testApkSha = '0CFBC64614E2C44408E444B8B9DE0BC53676C4484B6012D17360D11AFD9BEF8F'
$expectedAvd = if ($ApiLevel -eq 26) { 'STO08_API26_Disposable' } else { 'STO07_S03B_Disposable' }

function Assert([bool]$Condition,[string]$Message) { if (-not $Condition) { throw $Message } }
function At([string]$Rel) { return Join-Path $repo ($Rel -replace '/', '\') }
function Git([string[]]$Arguments) {
    $output = @(& git.exe -C $repo @Arguments)
    Assert ($LASTEXITCODE -eq 0) "Git failure: $($Arguments -join ' ')"
    return $output
}
function OneGit([string[]]$Arguments) {
    $items = @(Git $Arguments)
    Assert ($items.Count -eq 1) "Unexpected Git output count: $($Arguments -join ' ')"
    return [string]$items[0]
}
function Sha([string]$File) {
    Assert (Test-Path -LiteralPath $File -PathType Leaf) "Missing protected file: $File"
    return (Get-FileHash -LiteralPath $File -Algorithm SHA256).Hash
}
function CheckRepo {
    Assert (Test-Path -LiteralPath $repo -PathType Container) 'Worktree D is missing'
    Assert ((OneGit @('branch','--show-current')) -ceq $branch) 'Unexpected branch'
    Assert ((OneGit @('rev-parse','origin/main')) -ceq $main) 'Local origin/main changed; re-audit'
    $status = @(Git @('status','--porcelain=v1','--untracked-files=all'))
    Assert ($status.Count -eq 0) "Worktree D is not clean: $($status -join '; ')"
    # Promotion is a single driver-only commit directly following the frozen
    # fully-tested baseline. Later commits may update ONLY three named docs.
    # Any code/config/test/APK-affecting update fails closed until re-reviewed.
    $currentHead = OneGit @('rev-parse','HEAD')
    Assert ($currentHead -cne $baselineHead) 'Driver-only promotion commit is not present'
    $commits = @(Git @('rev-list','--reverse',"$baselineHead..$currentHead"))
    Assert ($commits.Count -gt 0) 'Expected promoted descendant of exact integration baseline'
    $promotionCommit = [string]$commits[0]
    Assert ((OneGit @('rev-parse',"$promotionCommit^")) -ceq $baselineHead) 'Driver promotion must directly descend from pinned baseline'
    $promotionPaths = @(Git @('diff','--name-only',$baselineHead,$promotionCommit))
    Assert ($promotionPaths.Count -eq 1 -and $promotionPaths[0] -ceq $driverRel) 'Promotion commit changed more than the tracked host driver'
    $subsequentPaths = @(Git @('diff','--name-only',$promotionCommit,$currentHead))
    foreach ($changed in $subsequentPaths) {
        Assert ($allowedDocumentationChanges -ccontains $changed) "Unreviewed subsequent repository change: $changed"
    }
    # Check the frozen original driver blob remains the one independently audited.
    Assert ((OneGit @('rev-parse',"${baselineHead}:$driverRel")) -ceq $baselineDriverGitBlob) 'Historical driver blob differs from pinned baseline'
    # Invoke ONLY the tracked driver from the guarded worktree, not an outside copy.
    $running = (Resolve-Path -LiteralPath $PSCommandPath).Path
    $tracked = (Resolve-Path -LiteralPath (At $driverRel)).Path
    Assert ($running -ceq $tracked) 'Run the committed driver from its exact worktree location'
    foreach ($rel in @($driverRel,$testRel) + @($sourcePins.Keys)) {
        $null = Git @('cat-file','-e',"HEAD:$rel")
        Assert (Test-Path -LiteralPath (At $rel) -PathType Leaf) "Tracked file missing: $rel"
    }
    Assert ((Sha (At $testRel)) -ceq $testSha) 'Process-death test SHA changed'
    foreach ($rel in @($sourcePins.Keys)) {
        Assert ((Sha (At $rel)) -ceq $sourcePins[$rel]) "Protected source SHA changed: $rel"
    }
    Write-Host "VERIFIED clean HEAD=$currentHead, driver promotion=$promotionCommit, baseline=$baselineHead, local origin/main=$main; protected test/sources pinned."
}
function CheckLocalApks {
    $app = At $appApkRel
    $testApk = At $testApkRel
    Assert ((Sha $app) -ceq $appApkSha) 'Local debug APK hash mismatch; refuse'
    Assert ((Sha $testApk) -ceq $testApkSha) 'Local androidTest APK hash mismatch; refuse'
    Assert ((Get-Item -LiteralPath $app).Length -gt 1024) 'Local app APK size unreasonable'
    Assert ((Get-Item -LiteralPath $testApk).Length -gt 1024) 'Local test APK size unreasonable'
    Write-Host "VERIFIED pinned local APP=$appApkSha TEST=$testApkSha (NO build)."
}
# Separate native stdout/stderr; PowerShell 5.1 must not turn stderr into
# native-command exception objects. Only specified commands and exit codes pass.
function Adb([string[]]$Arguments,[int[]]$Allowed = @(0)) {
    $argsList = @('-s',$script:serial) + $Arguments
    foreach ($arg in $argsList) {
        Assert ($arg -notmatch '[\r\n"]') "Unsafe ADB argument: $arg"
    }
    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $script:adbExe
    $psi.Arguments = ($argsList | ForEach-Object { '"' + $_ + '"' }) -join ' '
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $psi
    try {
        Assert ($process.Start()) 'Could not start pinned local ADB'
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        $exitCode = $process.ExitCode
    } finally { $process.Dispose() }
    Assert ($Allowed -contains $exitCode) "ADB exit ${exitCode}: $($Arguments -join ' '); stderr=$stderr; stdout=$stdout"
    return @($stdout -split '\r?\n' | Where-Object { $_.Trim().Length -gt 0 })
}
function DeviceIdentity {
    $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    $script:adbExe = Join-Path $sdk 'platform-tools\adb.exe'
    Assert (Test-Path -LiteralPath $script:adbExe -PathType Leaf) 'ADB not installed in expected SDK'
    $devices = @(& $script:adbExe devices -l)
    Assert ($LASTEXITCODE -eq 0) 'adb devices -l failed'
    $rows = @($devices | Where-Object {
        $_.Trim().Length -gt 0 -and $_ -notmatch '^List of devices attached' -and $_ -notmatch '^\*'
    })
    Assert ($rows.Count -eq 1) "Must be exactly one attached device: $($rows -join '; ')"
    $match = [regex]::Match([string]$rows[0], '^(emulator-\d+)\s+device(?:\s|$)')
    Assert $match.Success "Sole device is not an online emulator: $($rows[0])"
    $script:serial = $match.Groups[1].Value
    $api = (@(Adb @('shell','getprop','ro.build.version.sdk')) -join '').Trim()
    $boot = (@(Adb @('shell','getprop','sys.boot_completed')) -join '').Trim()
    $name = @(Adb @('emu','avd','name')) | ForEach-Object { $_.ToString().Trim() }
    Assert ($api -ceq "$ApiLevel") "Unexpected API $api, expected $ApiLevel"
    Assert ($boot -ceq '1') 'Emulator not booted'
    Assert (($name -contains $expectedAvd) -and ($name -contains 'OK')) "Expected exact disposable AVD $expectedAvd"
    Write-Host "VERIFIED sole $script:serial API=$ApiLevel AVD=$expectedAvd booted=1."
}
function ReadPid {
    $text = (@(Adb @('shell','pidof',$target) @(0,1)) -join ' ').Trim()
    if ($text.Length -eq 0) { return $null }
    Assert ($text -match '^\d+$') "Ambiguous target PID: $text"
    return [int]$text
}
function GetPackages {
    $inventory = @(Adb @('shell','pm','list','packages') | ForEach-Object { $_.ToString().Trim() })
    Assert ($inventory.Count -gt 0) 'Empty installed-package inventory; refuse'
    $bad = @($inventory | Where-Object { $_ -cnotmatch '^package:[A-Za-z0-9._]+$' })
    Assert ($bad.Count -eq 0) 'Invalid package inventory; refuse'
    return @($inventory)
}
function CheckPackages([bool]$RequireInstalled) {
    $packages = @(GetPackages)
    $appFound = ($packages -ccontains "package:$target")
    $testFound = ($packages -ccontains "package:$testPackage")
    Write-Host "INSTALLED_APP=$appFound INSTALLED_TEST=$testFound"
    if ($RequireInstalled) {
        Assert ($appFound -and $testFound) 'Both pinned APKs must already be installed; use separately approved Install mode'
    }
    if ($testFound) {
        $instruments = (@(Adb @('shell','pm','list','instrumentation')) -join "`n")
        Assert ($instruments.Contains($runner) -and $instruments.Contains("target=$target")) 'Unexpected installed test instrumentation runner'
        Write-Host 'VERIFIED exact instrumentation component and target package.'
    }
    return @($appFound,$testFound)
}
function OneInstalledBase([string]$Package) {
    $paths = @(Adb @('shell','pm','path',$Package))
    Assert ($paths.Count -eq 1) "Expected one non-split installed APK for $Package"
    $match = [regex]::Match([string]$paths[0], '^package:(/data/app/[A-Za-z0-9_./~+@=\-]+/base\.apk)$')
    Assert $match.Success "Unsafe or unexpected installed APK path: $($paths -join '; ')"
    Assert ($match.Groups[1].Value -notmatch '/\.\.?/') 'Traversal in APK path'
    return $match.Groups[1].Value
}
function EvidenceRoot([string]$Kind) {
    $downloads = Join-Path $env:USERPROFILE 'Downloads'
    Assert (Test-Path -LiteralPath $downloads -PathType Container) 'Downloads directory missing'
    $nonce = [guid]::NewGuid().ToString('D')
    $path = Join-Path $downloads "sto08_v4_${Kind}_api${ApiLevel}_$nonce"
    Assert (-not (Test-Path -LiteralPath $path)) 'Evidence collision; refuse'
    [void][IO.Directory]::CreateDirectory($path)
    Write-Host "EVIDENCE_DIR=$path"
    return $path
}
function PullAndVerify([string]$Phase,[string]$Root) {
    Assert (@('after-install','before-prepare','before-kill') -ccontains $Phase) 'Unknown APK proof phase'
    CheckRepo
    CheckLocalApks
    DeviceIdentity
    $null = CheckPackages $true
    $phaseDir = Join-Path $Root "apk-$Phase"
    Assert (-not (Test-Path -LiteralPath $phaseDir)) 'APK evidence directory collision'
    [void][IO.Directory]::CreateDirectory($phaseDir)
    $appRemote = OneInstalledBase $target
    $testRemote = OneInstalledBase $testPackage
    $appPulled = Join-Path $phaseDir 'installed-app-base.apk'
    $testPulled = Join-Path $phaseDir 'installed-test-base.apk'
    $null = Adb @('pull',$appRemote,$appPulled)
    $null = Adb @('pull',$testRemote,$testPulled)
    Assert ((Sha $appPulled) -ceq $appApkSha) "DEVICE APP APK SHA differs at $Phase; refuse to kill"
    Assert ((Sha $testPulled) -ceq $testApkSha) "DEVICE TEST APK SHA differs at $Phase; refuse to kill"
    CheckRepo
    CheckLocalApks
    Write-Host "VERIFIED installed APK bytes at $Phase, hashes match both pinned local files."
}
function LogMatches([string]$Marker) {
    $lines = @(Adb @('logcat','-d','-v','brief','-s','STO08_PD_HOLD:I','*:S'))
    return @($lines | Where-Object { $_.Contains($Marker) })
}
function PhaseArgs([string]$Method,[string]$Phase,[string]$Nonce) {
    return @('-s',$script:serial,'shell','am','instrument','-w','-r',
        '-e','sto08HoldPdPhase',$Phase,'-e','sto08HoldRun',$Nonce,
        '-e','class',"$testClass#$Method",$runner)
}
function ValidUuid([string]$Value) {
    try { return ([guid]::Parse($Value).ToString('D') -ceq $Value) }
    catch { return $false }
}

Write-Host "=== STO-08 V5-R1: PROMOTED PROCESS-DEATH DRIVER ($Mode / API $ApiLevel) ==="
Write-Host 'Audit: Git + APK files + SDK/device/package inventory only. No Gradle, install, pull, instrumentation, kill or Git writes.'
Write-Host 'Install and Execute: separate approvals are mandatory; each has an independent exact confirmation.'
Write-Host 'No production export wiring, source edits, receipts, hold release, VERIFIED promotion or GC.'
Write-Host "Driver location: $PSCommandPath"
Write-Host "`n=== Gate 0: Committed source integrity ==="
CheckRepo
Write-Host "`n=== Gate 1: Local APK integrity ==="
CheckLocalApks
Write-Host "`n=== Gate 2: Sole disposable device identity and package inventory ==="
DeviceIdentity
$installed = @(CheckPackages $false)
$targetPid = ReadPid
Write-Host "TARGET_APP_PID=$(if ($null -eq $targetPid) {'NOT_RUNNING'} else {$targetPid})"
if ($Mode -eq 'Audit') {
    CheckRepo
    CheckLocalApks
    Write-Host "`n=== STO-08 V5-R1 AUDIT PASSED ==="
    if (-not $installed[0] -and -not $installed[1]) {
        Write-Host 'NEXT_STAGE=INSTALL_PINNED_DEBUG_AND_TEST_APKS (NOT AUTHORIZED; seek separate approval).'
    } elseif ($installed[0] -and $installed[1]) {
        Write-Host 'NEXT_STAGE=INSTALLED_APK_BYTE_ATTESTATION_AND_PROCESS_DEATH (NOT AUTHORIZED).'
    } else {
        Write-Host 'NEXT_STAGE=PARTIAL_PACKAGE_STATE; STOP AND INVESTIGATE. DO NOT AUTO-REPLACE.'
    }
    Write-Host 'No APK installed/pulled, instrumentation, process death, fixture writes or cleanup was attempted.'
    exit 0
}

Assert ($null -eq $targetPid) 'Target app is active: do not disturb its process or data'
if ($Mode -eq 'Install') {
    Assert (-not $installed[0] -and -not $installed[1]) 'Install refuses existing or partially installed packages; no replace/uninstall'
    Write-Host 'Install scope: pinned local APP and TEST APKs on EXACT disposable AVD only; no instrumentation or process kill.'
    $reply = Read-Host "After separate V4 API $ApiLevel Install approval, type INSTALL STO-08 V4 API$ApiLevel PINNED-APKS"
    Assert ($reply -ceq "INSTALL STO-08 V4 API$ApiLevel PINNED-APKS") 'Install confirmation missing'
    CheckRepo; CheckLocalApks; DeviceIdentity
    $again = @(CheckPackages $false)
    Assert (-not $again[0] -and -not $again[1]) 'Installed packages changed; no install'
    Assert ($null -eq (ReadPid)) 'App became active; no install'
    $root = EvidenceRoot 'install'
    Write-Host 'Installing only exact pinned debug app APK; no -r/reinstall.'
    $appInstall = @(Adb @('install',(At $appApkRel)))
    Assert (@($appInstall | Where-Object { $_.Trim() -ceq 'Success' }).Count -eq 1) 'App install did not report success; preserve state and stop'
    CheckRepo; DeviceIdentity
    $afterApp = @(CheckPackages $false)
    Assert ($afterApp[0] -and -not $afterApp[1]) 'Unexpected packages after app install; stop'
    Write-Host 'Installing only exact pinned androidTest APK; no -r/reinstall.'
    $testInstall = @(Adb @('install',(At $testApkRel)))
    Assert (@($testInstall | Where-Object { $_.Trim() -ceq 'Success' }).Count -eq 1) 'Test install did not report success; preserve state and stop'
    PullAndVerify 'after-install' $root
    Assert ($null -eq (ReadPid)) 'Target app is running after installation; do not launch instrumentation'
    CheckRepo
    Write-Host "=== STO-08 V4 API $ApiLevel PINNED-APK INSTALL VERIFIED ==="
    Write-Host 'No test/kill performed. Separate Execute approval still required. No automatic retries or cleanup.'
    exit 0
}

Assert ($Mode -ceq 'Execute') 'Unexpected execution mode'
Assert ($installed[0] -and $installed[1]) 'Execute requires both installed pinned APKs'
Write-Host 'Execute scope: after byte-proof and READY marker, force-stop ONLY the isolated target package, then verify fresh-process holdings.'
$reply = Read-Host "After separate V4 API $ApiLevel Process-Death approval, type EXECUTE STO-08 V4 API$ApiLevel PROCESS-DEATH"
Assert ($reply -ceq "EXECUTE STO-08 V4 API$ApiLevel PROCESS-DEATH") 'Process-death confirmation missing'
CheckRepo; CheckLocalApks; DeviceIdentity
$null = CheckPackages $true
Assert ($null -eq (ReadPid)) 'Target app started; do not interrupt'
$nonce = [guid]::NewGuid().ToString('D')
Assert (ValidUuid $nonce) 'Nonce invalid'
Assert (@(LogMatches "run=$nonce").Count -eq 0) 'Nonce already occurs in logcat'
$root = EvidenceRoot 'process_death'
$prepareOut = Join-Path $root 'prepare_stdout.txt'
$prepareErr = Join-Path $root 'prepare_stderr.txt'
$verifyOut = Join-Path $root 'verify_stdout.txt'
$verifyErr = Join-Path $root 'verify_stderr.txt'
PullAndVerify 'before-prepare' $root
Assert ($null -eq (ReadPid)) 'Target process started before prepare; stop'
$prepareArgs = PhaseArgs $prepareMethod 'hold-prepare' $nonce
Write-Host "Launching host-driven PREPARE on $script:serial (nonce=$nonce)."
$child = Start-Process -FilePath $script:adbExe -ArgumentList ($prepareArgs -join ' ') -PassThru `
    -RedirectStandardOutput $prepareOut -RedirectStandardError $prepareErr
$clock = [Diagnostics.Stopwatch]::StartNew()
$marker = $null
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    Start-Sleep -Seconds 1
    $found = @(LogMatches "READY_TO_KILL run=$nonce")
    Assert ($found.Count -le 1) 'Duplicate READY markers; DO NOT KILL'
    if ($found.Count -eq 1) { $marker = [string]$found[0]; break }
    Assert (-not $child.HasExited) 'PREPARE transport exited before READY; DO NOT KILL'
}
Assert ($null -ne $marker) 'No READY marker within 60 seconds; DO NOT KILL'
$markerMatch = [regex]::Match($marker,
    'READY_TO_KILL run=([0-9a-f-]{36}) pid=(\d+) operation=([0-9a-f-]{36})(?:\s|$)')
Assert $markerMatch.Success 'Invalid READY marker; DO NOT KILL'
Assert ($markerMatch.Groups[1].Value -ceq $nonce) 'READY nonce mismatch; DO NOT KILL'
$preparedPid = [int]$markerMatch.Groups[2].Value
$operationId = $markerMatch.Groups[3].Value
Assert ((ValidUuid $operationId) -and $preparedPid -gt 0) 'Noncanonical operation or invalid PID; DO NOT KILL'
Assert ($preparedPid -eq (ReadPid)) 'READY PID differs from live app PID; DO NOT KILL'
Assert (-not $child.HasExited) 'PREPARE finished unexpectedly; DO NOT KILL'
Write-Host "VERIFIED committed READY marker for PID=$preparedPid; reattesting both installed APKs."
PullAndVerify 'before-kill' $root
DeviceIdentity
$null = CheckPackages $true
Assert ($clock.Elapsed.TotalSeconds -lt 100) 'PREPARE wait deadline near; DO NOT KILL'
$secondMarker = @(LogMatches "READY_TO_KILL run=$nonce")
Assert ($secondMarker.Count -eq 1 -and $secondMarker[0] -ceq $marker) 'READY marker changed; DO NOT KILL'
Assert ($preparedPid -eq (ReadPid)) 'PID changed during byte attestation; DO NOT KILL'
Assert (-not $child.HasExited) 'PREPARE exited during byte attestation; DO NOT KILL'
CheckRepo
# The only application-process termination command in this driver.
Write-Host "TERMINATING ONLY target package=$target PID=$preparedPid on $expectedAvd."
$null = Adb @('shell','am','force-stop',$target)
Start-Sleep -Seconds 2
Assert ($null -eq (ReadPid)) 'Target process still alive after force-stop; evidence inconclusive'
Assert ($child.WaitForExit(15000)) 'PREPARE host transport did not exit after force-stop'
# The transport exit status is not evidence of JUnit success (PREPARE dies).
DeviceIdentity
CheckRepo
Write-Host 'Launching fresh VERIFY instrumentation without APK reinstall or app-data clear.'
$verifyArgs = PhaseArgs $verifyMethod 'hold-verify' $nonce
$verifyChild = Start-Process -FilePath $script:adbExe -ArgumentList ($verifyArgs -join ' ') -PassThru `
    -RedirectStandardOutput $verifyOut -RedirectStandardError $verifyErr
Assert ($verifyChild.WaitForExit(90000)) 'VERIFY timed out; preserve all fixture/evidence'
$verifyChild.WaitForExit()
$hostExit = $verifyChild.ExitCode
Assert (Test-Path -LiteralPath $verifyOut -PathType Leaf) 'VERIFY stdout missing'
Assert (Test-Path -LiteralPath $verifyErr -PathType Leaf) 'VERIFY stderr missing'
$lines = @(Get-Content -LiteralPath $verifyOut)
$text = $lines -join "`n"
$statuses = @($lines | Where-Object { $_ -match '^INSTRUMENTATION_STATUS_CODE: -?\d+$' })
Assert ($statuses.Count -eq 2 -and $statuses[0] -ceq 'INSTRUMENTATION_STATUS_CODE: 1' -and
    $statuses[1] -ceq 'INSTRUMENTATION_STATUS_CODE: 0') 'VERIFY exact start/pass statuses missing'
Assert (@($lines | Where-Object { $_ -ceq "INSTRUMENTATION_STATUS: test=$verifyMethod" }).Count -eq 2) 'VERIFY method evidence ambiguous'
Assert (@($lines | Where-Object { $_ -ceq "INSTRUMENTATION_STATUS: class=$testClass" }).Count -eq 2) 'VERIFY class evidence ambiguous'
Assert (@($lines | Where-Object { $_.Trim() -ceq 'OK (1 test)' }).Count -eq 1) 'VERIFY JUnit 1/1 success not proven'
Assert (@($lines | Where-Object { $_.Trim() -ceq 'INSTRUMENTATION_CODE: -1' }).Count -eq 1) 'VERIFY instrumentation result ambiguous'
Assert (-not [regex]::IsMatch($text,
    '(?im)FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|INSTRUMENTATION_RESULT:\s*shortMsg=|INSTRUMENTATION_STATUS_CODE:\s*-[0-9]+')) 'VERIFY output reports failure'
Assert ((Get-Item -LiteralPath $verifyErr).Length -eq 0) 'VERIFY stderr is nonempty; investigate before claiming pass'
$verified = @(LogMatches "VERIFY_PASSED run=$nonce")
Assert ($verified.Count -eq 1) 'Fresh VERIFY_PASSED marker missing/ambiguous'
$matchVerify = [regex]::Match([string]$verified[0],
    'VERIFY_PASSED run=([0-9a-f-]{36}) oldPid=(\d+) newPid=(\d+) operation=([0-9a-f-]{36})(?:\s|$)')
Assert $matchVerify.Success 'Invalid fresh VERIFY marker'
Assert ($matchVerify.Groups[1].Value -ceq $nonce) 'VERIFY nonce mismatch'
Assert ([int]$matchVerify.Groups[2].Value -eq $preparedPid -and
    [int]$matchVerify.Groups[3].Value -ne $preparedPid) 'Fresh-process PID transition not proven'
Assert ($matchVerify.Groups[4].Value -ceq $operationId) 'Recovered operation ID differs from READY'
CheckRepo
Write-Host "HOST_ADB_VERIFY_EXIT=$hostExit (not used alone as a pass signal)"
Write-Host "=== STO-08 V4 API $ApiLevel PROCESS-DEATH HOLD RETENTION PASSED ==="
Write-Host "run=$nonce oldPid=$preparedPid newPid=$($matchVerify.Groups[3].Value) operation=$operationId"
Write-Host "EVIDENCE_DIR=$root"
Write-Host 'Only isolated Room holds survived. NO production receipt, protected release, VERIFIED or GC is proved.'
