[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BackupPath,
    [Parameter(Mandatory)][string]$ExpectedSha256,
    [Parameter(Mandatory)][string]$Device,
    [Parameter(Mandatory)][string]$EvidenceDir
)
$ErrorActionPreference = 'Stop'
if ($Device -ne '5A101JEBF24790') { throw 'Only the approved regression Pixel may be restored.' }
$backup = (Resolve-Path -LiteralPath $BackupPath).Path
if ((Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash -ne $ExpectedSha256) {
    throw 'Backup checksum mismatch. Refusing to clear app data.'
}
$entries = @(& tar -tf $backup)
if ($LASTEXITCODE -ne 0 -or !$entries.Count) { throw 'Backup archive is unreadable.' }
foreach ($entry in $entries) {
    if ($entry -notmatch '^(files|databases|shared_prefs)(/|$)' -or $entry -match '(^|/)\.\.(/|$)') {
        throw "Unexpected archive entry: $entry"
    }
}
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
$evidence = (Resolve-Path -LiteralPath $EvidenceDir).Path
$expectedDir = Join-Path $evidence 'expected-sandbox'
$actualDir = Join-Path $evidence 'restored-sandbox'
if ((Test-Path -LiteralPath $expectedDir) -or (Test-Path -LiteralPath $actualDir)) {
    throw 'Use a fresh evidence directory for each restoration verification.'
}
New-Item -ItemType Directory -Path $expectedDir, $actualDir | Out-Null
& tar -xf $backup -C $expectedDir
if ($LASTEXITCODE -ne 0) { throw 'Cannot extract expected sandbox.' }
function Invoke-CheckedAdb {
    param([Parameter(ValueFromRemainingArguments)][string[]]$AdbArgs)
    & adb -s $Device @AdbArgs
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($AdbArgs -join ' ')" }
}
$remoteArchive = '/data/local/tmp/u1-reconciliation-restore.tar'
Invoke-CheckedAdb push $backup $remoteArchive
Invoke-CheckedAdb shell chmod 644 $remoteArchive
$remoteHash = (& adb -s $Device shell sha256sum $remoteArchive) -join ''
if ($LASTEXITCODE -ne 0 -or $remoteHash.Split(' ')[0] -ne $ExpectedSha256.ToLowerInvariant()) {
    throw 'Device backup checksum mismatch. Refusing to clear app data.'
}
Invoke-CheckedAdb shell am force-stop com.u1.slicer.orca
# The verified full backup covers every durable app file before this scoped reset.
Invoke-CheckedAdb shell pm clear com.u1.slicer.orca
Invoke-CheckedAdb shell run-as com.u1.slicer.orca tar -xf $remoteArchive
$restoredTar = Join-Path $evidence 'restored-data.tar'
$adbExecutable = (Get-Command adb).Source
# cmd preserves binary stdout on both Windows PowerShell and PowerShell 7.
& cmd /d /c "`"$adbExecutable`" -s $Device exec-out run-as com.u1.slicer.orca tar -cf - files databases shared_prefs > `"$restoredTar`""
if ($LASTEXITCODE -ne 0) { throw 'Cannot capture restored sandbox.' }
& tar -xf $restoredTar -C $actualDir
if ($LASTEXITCODE -ne 0) { throw 'Cannot extract restored sandbox.' }
function File-Manifest([string]$Root) {
    @(Get-ChildItem -LiteralPath $Root -File -Recurse | ForEach-Object {
        $relative = [IO.Path]::GetRelativePath($Root, $_.FullName)
        "$relative`t$((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash)"
    } | Sort-Object)
}
$expectedManifest = File-Manifest $expectedDir
$actualManifest = File-Manifest $actualDir
$difference = Compare-Object $expectedManifest $actualManifest
if ($difference) { $difference | Out-Host; throw 'Restored sandbox differs from the original backup.' }
$expectedManifest | Set-Content -LiteralPath (Join-Path $evidence 'verified-file-hashes.tsv')
Invoke-CheckedAdb shell rm $remoteArchive
Write-Host "Restored and byte-verified all $($expectedManifest.Count) original app files. The app remains stopped."
