[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$CandidateApk,
    [Parameter(Mandatory)][string]$PublishedApk,
    [Parameter(Mandatory)][string]$Device,
    [string]$TestApk = 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
    [string]$ResultsDir = "app/build/test-progress/v405-upgrade-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
)
$ErrorActionPreference = 'Stop'
if ($Device -ne '5A101JEBF24790') { throw 'This reconciliation regression requires the approved Pixel 9a.' }
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null
$fixtureSequence = 0
$publishedHash = (Get-FileHash -LiteralPath $PublishedApk -Algorithm SHA256).Hash
if ($publishedHash -ne 'EC721A79D14A13E7F202D56F0EB3470D132FCFAD79678EB912F12A55750BB979') {
    throw 'The published 4.0.5 APK checksum does not match the release asset.'
}
function Invoke-CheckedAdb {
    param([Parameter(ValueFromRemainingArguments)][string[]]$AdbArgs)
    & adb -s $Device @AdbArgs
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($AdbArgs -join ' ')" }
}
function Run-Fixture([string]$Method) {
    $script:fixtureSequence++
    $output = @(& adb -s $Device shell am instrument -w -r -e class "com.u1.slicer.migration.V405UpgradeMigrationTest#$Method" com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner 2>&1)
    $adbExit = $LASTEXITCODE
    $output | Set-Content -LiteralPath (Join-Path $ResultsDir "$script:fixtureSequence-$Method.log")
    $output | Out-Host
    if ($adbExit -ne 0 -or ($output -join "`n") -notmatch 'OK \(1 test\)' -or
        ($output -join "`n") -match 'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=') {
        throw "Published 405 upgrade fixture failed: $Method"
    }
}
# Caller must preserve the complete sandbox before any regression suite modifies it.
Invoke-CheckedAdb -AdbArgs @('install', '-r', '-d', (Resolve-Path -LiteralPath $PublishedApk).Path)
Invoke-CheckedAdb -AdbArgs @('install', '-r', (Resolve-Path -LiteralPath $TestApk).Path)
Run-Fixture 'seedPublished405DataAndGenericJob'
Invoke-CheckedAdb -AdbArgs @('install', '-r', (Resolve-Path -LiteralPath $CandidateApk).Path)
Invoke-CheckedAdb -AdbArgs @('shell', 'am', 'start', '-W', '-n', 'com.u1.slicer.orca/com.u1.slicer.MainActivity')
Run-Fixture 'verifyPublished405PrintersAndSchema9JobAfterUpgrade'
Invoke-CheckedAdb -AdbArgs @('install', '-r', (Resolve-Path -LiteralPath $CandidateApk).Path)
Run-Fixture 'verifyPublished405PrintersAndSchema9JobAfterUpgrade'
Write-Host 'Published 405 printer snapshots, credentials, profile identity, and schema 9 job survived upgrade and reinstall.'
