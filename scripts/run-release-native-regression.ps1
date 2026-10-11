[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Device,
    [Parameter(Mandatory)][string]$ReleaseApk,
    [Parameter(Mandatory)][string]$DebugApk,
    [Parameter(Mandatory)][string]$TestApk,
    [Parameter(Mandatory)][string]$BackupPath,
    [Parameter(Mandatory)][string]$BackupSha256,
    [Parameter(Mandatory)][string]$ResultsDir
)
$ErrorActionPreference = 'Stop'
if ($Device -ne '5A101JEBF24790') { throw 'Only the approved regression Pixel may run these tests.' }
foreach ($path in @($ReleaseApk, $DebugApk, $TestApk, $BackupPath)) {
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing input: $path" }
}
if ((Get-FileHash -LiteralPath $BackupPath -Algorithm SHA256).Hash -ne $BackupSha256) {
    throw 'Backup checksum mismatch before device mutation.'
}
if ((Test-Path -LiteralPath $ResultsDir) -and @(Get-ChildItem -LiteralPath $ResultsDir -Force).Count) {
    throw 'Use a fresh results directory; previous evidence must not be overwritten.'
}
& python "$PSScriptRoot/validate-native-page-size.py" $ReleaseApk --aapt D:/Android/Sdk/build-tools/35.0.0/aapt.exe
if ($LASTEXITCODE -ne 0) { throw 'Release APK native checks failed.' }
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null
$inputs = @{
    releaseSha256 = (Get-FileHash -LiteralPath $ReleaseApk).Hash
    debugSha256 = (Get-FileHash -LiteralPath $DebugApk).Hash
    testSha256 = (Get-FileHash -LiteralPath $TestApk).Hash
}
$inputs | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $ResultsDir 'candidate-input.json')
function Invoke-CheckedAdb([string[]]$Arguments) {
    & adb -s $Device @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($Arguments[0])" }
}
function Get-InstalledApkHash([string]$Package) {
    $location = ((& adb -s $Device shell pm path $Package) -join '').Trim()
    if ($LASTEXITCODE -ne 0 -or $location -notmatch '^package:(/data/app/[^\s]+/base\.apk)$') { throw "Unexpected APK location: $Package" }
    $hashOutput = ((& adb -s $Device shell sha256sum $Matches[1]) -join '').Trim()
    if ($LASTEXITCODE -ne 0 -or $hashOutput -notmatch '^([a-fA-F0-9]{64})\s') { throw "Cannot hash installed APK: $Package" }
    return $Matches[1].ToUpperInvariant()
}
try {
    Invoke-CheckedAdb @('install', '-r', $ReleaseApk)
    Invoke-CheckedAdb @('install', '-r', $TestApk)
    $inputs.installedReleaseSha256 = Get-InstalledApkHash 'com.u1.slicer.orca'
    $inputs.installedTestSha256 = Get-InstalledApkHash 'com.u1.slicer.orca.test'
    if ($inputs.installedReleaseSha256 -ne $inputs.releaseSha256 -or $inputs.installedTestSha256 -ne $inputs.testSha256) {
        throw 'Actual installed release/test APK hashes do not match the preserved inputs.'
    }
    $inputs | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $ResultsDir 'candidate-input.json')
    Invoke-CheckedAdb @('shell', 'am', 'force-stop', 'com.u1.slicer.orca')
    Invoke-CheckedAdb @('shell', 'pm', 'clear', 'com.u1.slicer.orca')
    $classes = 'com.u1.slicer.slicing.GenericKlipperNativeConfigTest,com.u1.slicer.slicing.GenericIndependentExtruderNativeTest,com.u1.slicer.slicing.MarlinFlavorTest,com.u1.slicer.slicing.SingleExtruderMachineTest'
    & "$PSScriptRoot/run-instrumented-shard.ps1" -Device $Device -ClassFilter $classes -ResultsDir (Join-Path $ResultsDir 'native')
    $summary = Get-Content -LiteralPath (Join-Path $ResultsDir "native/shard-0-$Device.json") -Raw | ConvertFrom-Json
    if ($summary.passed -ne 18 -or $summary.failed -ne 0 -or $summary.skipped -ne 0 -or $summary.completionCode -ne -1 -or $summary.adbExit -ne 0) {
        throw 'Release native test count or completion mismatch.'
    }
    $passedMethods = @()
    $currentClass = ''
    $currentMethod = ''
    foreach ($line in Get-Content -LiteralPath (Join-Path $ResultsDir "native/shard-0-$Device.log")) {
        if ($line -match '^INSTRUMENTATION_STATUS: class=(.+)$') { $currentClass = $Matches[1] }
        if ($line -match '^INSTRUMENTATION_STATUS: test=(.+)$') { $currentMethod = $Matches[1] }
        if ($line -eq 'INSTRUMENTATION_STATUS_CODE: 0') { $passedMethods += "$currentClass#$currentMethod" }
    }
    $expectedClassCounts = @{
        'com.u1.slicer.slicing.GenericKlipperNativeConfigTest' = 13
        'com.u1.slicer.slicing.GenericIndependentExtruderNativeTest' = 3
        'com.u1.slicer.slicing.MarlinFlavorTest' = 1
        'com.u1.slicer.slicing.SingleExtruderMachineTest' = 1
    }
    if (@($passedMethods | Select-Object -Unique).Count -ne 18) { throw 'Release native evidence must contain 18 unique passed methods.' }
    foreach ($class in $expectedClassCounts.Keys) {
        if (@($passedMethods | Where-Object { $_.StartsWith("$class#") }).Count -ne $expectedClassCounts[$class]) { throw "Release native class coverage mismatch: $class" }
    }
} finally {
    try {
        Invoke-CheckedAdb @('install', '-r', $DebugApk)
    } finally {
        & "$PSScriptRoot/restore-reconciliation-device-data.ps1" -Device $Device -BackupPath $BackupPath `
            -ExpectedSha256 $BackupSha256 -EvidenceDir (Join-Path $ResultsDir 'restore')
    }
}
Write-Host 'All 18 native checks passed against the release APK; original app files restored and byte-verified.'
