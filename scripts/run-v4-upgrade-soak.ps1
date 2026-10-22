[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$CandidateApk,
    [Parameter(Mandatory = $true)]
    [string]$Device,
    [string]$V4Apk = "release/phase7/baseline/u1-slicer-orca-v4.0.4.apk",
    [string]$AndroidTestApk = "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
    [string]$ResultsDir = "release/phase7/evidence/task7.3/upgrade-soak"
)

$ErrorActionPreference = "Stop"
if ($Device -ne "5A101JEBF24790") { throw "This regression run is restricted to the approved Pixel 9a." }
$AppId = "com.u1.slicer.orca"
$MainActivity = "$AppId/com.u1.slicer.MainActivity"
$TestId = "com.u1.slicer.orca.test"
$Runner = "$TestId/androidx.test.runner.AndroidJUnitRunner"
$script:FixtureRunNumber = 0
$ExpectedV4Sha256 = "2f93b91cee22cc6437009677e12b212c09f69c002dd529e4a201aebbd8725e78"

if (-not (Test-Path -LiteralPath $CandidateApk)) { throw "Candidate APK not found: $CandidateApk" }
if (-not (Test-Path -LiteralPath $V4Apk)) { throw "v4.0.4 APK not found: $V4Apk" }
if (-not (Test-Path -LiteralPath $AndroidTestApk)) { throw "Android test APK not found: $AndroidTestApk" }

$v4Hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $V4Apk).Hash.ToLowerInvariant()
if ($v4Hash -ne $ExpectedV4Sha256) {
    throw "Released v4.0.4 APK hash mismatch: expected $ExpectedV4Sha256, got $v4Hash"
}

New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null
$env:ANDROID_SERIAL = $Device

function Invoke-Adb {
    param([Parameter(Position = 0, ValueFromRemainingArguments = $true)][string[]]$Args)
    & adb -s $Device @Args
    if ($LASTEXITCODE -ne 0) { throw "adb failed ($LASTEXITCODE): adb -s $Device $($Args -join ' ')" }
}

function Install-Apk([string]$Path, [switch]$AllowDowngrade) {
    $args = @("install", "-r")
    if ($AllowDowngrade) { $args += "-d" }
    $args += (Resolve-Path -LiteralPath $Path).Path
    Invoke-Adb @args | Out-Host
}

function Launch-App([string]$Label) {
    Write-Host "Launching $Label"
    Invoke-Adb shell am force-stop $AppId | Out-Null
    $output = @(& adb -s $Device shell am start -W -n $MainActivity 2>&1)
    $output | Out-Host
    $joined = $output -join "`n"
    if ($LASTEXITCODE -ne 0 -or $joined -match "Error type|does not exist") {
        throw "Could not launch ${MainActivity}: $joined"
    }
    Start-Sleep -Seconds 3
}

function Run-Instrumented([string]$ClassName, [string]$Label) {
    Write-Host "Running $Label"
    $output = @(& adb -s $Device shell am instrument -w -r -e class $ClassName $Runner 2>&1)
    $adbExit = $LASTEXITCODE
    $script:FixtureRunNumber++
    $output | Set-Content -LiteralPath (Join-Path $ResultsDir ('fixture-{0:00}.log' -f $script:FixtureRunNumber))
    $output | Out-Host
    if ($adbExit -ne 0) { throw "Instrumentation failed ($adbExit): $Label" }
    $joined = $output -join "`n"
    if ($joined -notmatch "OK \(") {
        throw "Instrumentation did not report OK: $Label"
    }
    if ($joined -match "FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=Error") {
        throw "Instrumentation reported a failure: $Label"
    }
}

function Save-Log([string]$Name) {
    $path = Join-Path $ResultsDir $Name
    & adb -s $Device logcat -d | Out-File -Encoding utf8 -FilePath $path
    if ($LASTEXITCODE -ne 0) { throw "Could not capture logcat to $path" }
    Write-Host "Saved $path"
    return $path
}

function Assert-ApkChangedExit([string]$Path, [string]$Label) {
    $log = Get-Content -Raw -LiteralPath $Path
    if ($log -notmatch "APK change detected .*exiting") {
        throw "$Label did not log the intentional APK_CHANGED exit; inspect $Path"
    }
    Write-Host "$Label recorded the intentional APK_CHANGED exit"
}

Write-Host "Using only approved device $Device; no print or network host is contacted."
$priorPackagePath = @(& adb -s $Device shell pm path $AppId 2>&1)
$hasPriorInstall = $LASTEXITCODE -eq 0 -and (($priorPackagePath -join "`n") -match "package:")
$priorStateSnapshotCreated = $false
try {
    if ($hasPriorInstall) {
        Install-Apk $AndroidTestApk
        Run-Instrumented "com.u1.slicer.migration.PriorDeviceStateSnapshotTest#snapshotPrinterConfigAndMachineProfiles" "prior device state snapshot"
        $priorStateSnapshotCreated = $true
    }

    Install-Apk $V4Apk -AllowDowngrade
    Install-Apk $AndroidTestApk
    Launch-App "released v4.0.4"
    Run-Instrumented "com.u1.slicer.migration.V4UpgradeMigrationSeedTest#seedReleasedV4PrintersConfig" "v4 fixture seed"
    Save-Log "01-v4-seeded.logcat.txt"

    # The seed test writes and reads back the released persistence shape. Installing the
    # candidate immediately avoids a second UI launch mutating the fixture before the upgrade.
    Install-Apk $CandidateApk
    Invoke-Adb logcat -c | Out-Null
    Launch-App "candidate first process"
    $firstCandidateLog = Save-Log "02-candidate-first-process.logcat.txt"
    Assert-ApkChangedExit $firstCandidateLog "candidate first process"
    Run-Instrumented "com.u1.slicer.migration.V5UpgradeMigrationVerificationTest#verifyV4PrintersConfigAndLegacyU1SliceAfterUpgrade" "v4 data and native U1 slice verification"
    Run-Instrumented "com.u1.slicer.migration.V5UpgradeMigrationVerificationTest#seedAndVerifyGenericProfileSurvivesCandidateReinstall" "generic profile seed"

    # Reinstalling the same candidate proves same-version APK timestamp handling. The next launch
    # must take the intentional APK_CHANGED exit path again; the following instrumentation run checks
    # that machine_profiles and all v4 printer data survived it.
    Install-Apk $CandidateApk
    Invoke-Adb logcat -c | Out-Null
    Launch-App "candidate same-version reinstall"
    $reinstallLog = Save-Log "03-candidate-reinstall-process.logcat.txt"
    Assert-ApkChangedExit $reinstallLog "candidate same-version reinstall"
    Run-Instrumented "com.u1.slicer.migration.V5UpgradeMigrationVerificationTest#verifyGenericProfileSurvivesCandidateReinstall" "post-reinstall v4 data, native U1 slice, and generic profile verification"

    Write-Host "v4.0.4 -> candidate upgrade soak completed on $Device."
} finally {
    if ($hasPriorInstall -and $priorStateSnapshotCreated) {
        Install-Apk $CandidateApk
        Install-Apk $AndroidTestApk
        Run-Instrumented "com.u1.slicer.migration.PriorDeviceStateRestoreTest#restorePrinterConfigAndMachineProfiles" "prior device state restore"
    } elseif (-not $hasPriorInstall) {
        & adb -s $Device uninstall $TestId | Out-Host
        & adb -s $Device uninstall $AppId | Out-Host
    }
}
