# STO-07 S03-B: remove only explicitly named disposable guest test filler files.
# Intended as a recovery command if a later pressure test fails.
# Does not remove app data, notes, databases, the AVD image, or other files.
param([string]$Serial = 'emulator-5556')
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$adb = Join-Path (Join-Path $env:LOCALAPPDATA 'Android\Sdk') 'platform-tools\adb.exe'
$expected = 'STO07_S03B_Disposable'
$dir = '/data/local/tmp/sto07_s03b_pressure'
if (-not (Test-Path -LiteralPath $adb)) { throw "ADB unavailable: $adb" }
$devices = @(& $adb devices -l)
if (($devices -join "`n") -notmatch ('(?m)^' + [regex]::Escape($Serial) + '\s+device\b')) {
    throw "Safety stop: $Serial is not connected and ready."
}
$identity = @(& $adb -s $Serial emu avd name)
if ($LASTEXITCODE -ne 0 -or $identity.Count -lt 1 -or $identity[0].Trim() -ne $expected) {
    throw "Safety stop: $Serial is not the disposable $expected AVD."
}
& $adb -s $Serial shell test -d $dir | Out-Null
$directoryStatus = $LASTEXITCODE
if ($directoryStatus -eq 1) {
    Write-Host "No pressure directory found at $dir. Nothing removed."
    return
}
if ($directoryStatus -ne 0) { throw "Could not inspect $dir (exit $directoryStatus)" }
$names = @(& $adb -s $Serial shell ls -1A $dir)
if ($LASTEXITCODE -ne 0) { throw "Could not enumerate $dir" }
$names = @($names | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ -ne '' })
foreach ($name in $names) {
    if ($name -cnotmatch '^fill_[0-9]{3}\.bin$') {
        throw "Unexpected item $name in $dir. Refusing to delete any files."
    }
}
foreach ($name in $names) {
    $path = "$dir/$name"
    & $adb -s $Serial shell rm -f $path
    if ($LASTEXITCODE -ne 0) { throw "Could not remove disposable filler file $path" }
    Write-Host "Removed: $path"
}
& $adb -s $Serial shell rmdir $dir
if ($LASTEXITCODE -ne 0) { throw "Could not remove empty pressure directory $dir" }
Write-Host 'S03-B disposable pressure files cleaned. Notebook data was not touched.'
& $adb -s $Serial shell df -k /data
