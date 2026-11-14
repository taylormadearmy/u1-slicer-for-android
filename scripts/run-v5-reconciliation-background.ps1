[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Device,
    [Parameter(Mandatory)][string]$CandidateApk,
    [Parameter(Mandatory)][string]$TestApk,
    [Parameter(Mandatory)][string]$ReleaseApk,
    [Parameter(Mandatory)][string]$Published405Apk,
    [Parameter(Mandatory)][string]$Published404Apk,
    [Parameter(Mandatory)][string]$BackupPath,
    [Parameter(Mandatory)][string]$BackupSha256,
    [Parameter(Mandatory)][string]$ResultsRoot
)
$ErrorActionPreference = 'Stop'
if ($Device -ne '5A101JEBF24790') { throw 'Only the approved regression Pixel may run this suite.' }
if (Test-Path -LiteralPath $ResultsRoot) { throw 'Use a new results root; interrupted evidence must be preserved.' }
foreach ($path in @($CandidateApk, $TestApk, $ReleaseApk, $Published405Apk, $Published404Apk, $BackupPath)) {
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing input before device mutation: $path" }
}
if ((Get-FileHash -LiteralPath $BackupPath -Algorithm SHA256).Hash -ne $BackupSha256) { throw 'Backup checksum mismatch before device mutation.' }
function Write-Status([hashtable]$Snapshot, [string]$Path) {
    $temporaryPath = "$Path.tmp"
    $Snapshot | ConvertTo-Json | Set-Content -LiteralPath $temporaryPath -Encoding UTF8
    if (Test-Path -LiteralPath $Path) {
        [IO.File]::Replace($temporaryPath, $Path, [NullString]::Value)
    } else {
        [IO.File]::Move($temporaryPath, $Path)
    }
}
$auditRoot = Join-Path (Split-Path $PSScriptRoot -Parent) '.audit/v5-reconciliation'
New-Item -ItemType Directory -Force -Path $auditRoot | Out-Null
# Windows releases this exclusive handle even if the host process is killed.
$deviceLock = [IO.File]::Open((Join-Path $auditRoot "device-$Device.lock"), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
try {
    New-Item -ItemType Directory -Path $ResultsRoot | Out-Null
    $statusPath = Join-Path $ResultsRoot 'status.json'
    $status = @{ processId=$PID; phase='debug-regression'; success=$null; startedAt=(Get-Date).ToUniversalTime().ToString('o'); finishedAt=$null; error=$null }
    Write-Status $status $statusPath
    try {
        $debugParameters = @{
            Device=$Device; CandidateApk=$CandidateApk; TestApk=$TestApk
            Published405Apk=$Published405Apk; Published404Apk=$Published404Apk
            BackupPath=$BackupPath; BackupSha256=$BackupSha256
            ResultsDir=(Join-Path $ResultsRoot 'debug')
        }
        $global:LASTEXITCODE = 0
        & "$PSScriptRoot/run-v5-reconciliation-regression.ps1" @debugParameters
        $debugSucceeded = $?
        if (!$debugSucceeded -or $LASTEXITCODE -ne 0) { throw "Debug regression driver returned exit code $LASTEXITCODE." }
        $status.phase = 'release-native'
        Write-Status $status $statusPath
        $releaseParameters = @{
            Device=$Device; ReleaseApk=$ReleaseApk; DebugApk=$CandidateApk; TestApk=$TestApk
            BackupPath=$BackupPath; BackupSha256=$BackupSha256
            ResultsDir=(Join-Path $ResultsRoot 'release-native')
        }
        $global:LASTEXITCODE = 0
        & "$PSScriptRoot/run-release-native-regression.ps1" @releaseParameters
        $releaseSucceeded = $?
        if (!$releaseSucceeded -or $LASTEXITCODE -ne 0) { throw "Release native driver returned exit code $LASTEXITCODE." }
        $status.phase = 'complete'
        $status.success = $true
    } catch {
        $status.success = $false
        $status.error = $_.Exception.Message
        Write-Error $_ -ErrorAction Continue
    } finally {
        $status.finishedAt = (Get-Date).ToUniversalTime().ToString('o')
        Write-Status $status $statusPath
    }
} finally {
    $deviceLock.Dispose()
}
if (!$status.success) { exit 1 }
