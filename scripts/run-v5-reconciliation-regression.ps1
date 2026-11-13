[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Device,
    [Parameter(Mandatory)][string]$CandidateApk,
    [Parameter(Mandatory)][string]$TestApk,
    [Parameter(Mandatory)][string]$Published405Apk,
    [Parameter(Mandatory)][string]$Published404Apk,
    [Parameter(Mandatory)][string]$BackupPath,
    [Parameter(Mandatory)][string]$BackupSha256,
    [Parameter(Mandatory)][string]$ResultsDir,
    [switch]$ResumeAfterTestSetupFix,
    [string]$PriorResultsDir,
    [switch]$ResumeAfterIndependentTests,
    [string]$PriorFocusedResultsDir
)
$ErrorActionPreference = 'Stop'
if ($Device -ne '5A101JEBF24790') { throw 'Only the approved regression Pixel may run this suite.' }
& "$PSScriptRoot/test-v405-upgrade-arguments.ps1"
foreach ($path in @($CandidateApk, $TestApk, $Published405Apk, $Published404Apk, $BackupPath)) {
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing input: $path" }
}
& python "$PSScriptRoot/validate-native-page-size.py" $CandidateApk --aapt D:/Android/Sdk/build-tools/35.0.0/aapt.exe
if ($LASTEXITCODE -ne 0) { throw 'Candidate native page-size checks failed before device mutation.' }
if ((Get-FileHash -LiteralPath $BackupPath -Algorithm SHA256).Hash -ne $BackupSha256) {
    throw 'Backup checksum mismatch. Refusing to run device tests.'
}
if ((Test-Path -LiteralPath $ResultsDir) -and @(Get-ChildItem -LiteralPath $ResultsDir -Force).Count) {
    throw 'Use a fresh results directory; previous regression evidence must not be overwritten.'
}
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null
$results = (Resolve-Path -LiteralPath $ResultsDir).Path
@{
    sha256 = (Get-FileHash -LiteralPath $CandidateApk).Hash
    testSha256 = (Get-FileHash -LiteralPath $TestApk).Hash
    recordedAt = (Get-Date).ToUniversalTime().ToString('o')
    provenance = 'Host inputs recorded before device mutation'
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $results 'candidate-input.json')
$env:ANDROID_SERIAL = $Device
if ($ResumeAfterIndependentTests -and !$ResumeAfterTestSetupFix) { throw 'Independent-test resume also requires the complete diagnosed full-run evidence.' }
if ($ResumeAfterTestSetupFix) {
    $prior = Get-Content (Join-Path $PriorResultsDir 'candidate-input.json') -Raw | ConvertFrom-Json
    $summary = Get-Content (Join-Path $PriorResultsDir "full-device/shard-0-$Device.json") -Raw | ConvertFrom-Json
    if ($prior.sha256 -ne (Get-FileHash -LiteralPath $CandidateApk).Hash -or
        $summary.passed -ne 486 -or $summary.failed -ne 7 -or $summary.skipped -ne 0 -or
        $summary.completionCode -ne -1 -or $summary.adbExit -ne 0) {
        throw 'Resume requires the unchanged app APK and the complete 486-pass, seven-test-setup-failure run.'
    }
    $failureClasses = @()
    $failureMethods = @()
    $failureStacks = @()
    $currentClass = ''
    $currentMethod = ''
    $currentStack = ''
    foreach ($line in Get-Content (Join-Path $PriorResultsDir "full-device/shard-0-$Device.log")) {
        if ($line -eq 'INSTRUMENTATION_STATUS_CODE: 1') { $currentStack = '' }
        if ($line -match '^INSTRUMENTATION_STATUS: class=(.+)$') { $currentClass = $Matches[1] }
        if ($line -match '^INSTRUMENTATION_STATUS: test=(.+)$') { $currentMethod = $Matches[1] }
        if ($line -match '^INSTRUMENTATION_STATUS: stack=(.+)$') { $currentStack = $Matches[1] }
        if ($line -match '^INSTRUMENTATION_STATUS_CODE: -(1|2)$') {
            $failureClasses += $currentClass
            $failureMethods += "$currentClass#$currentMethod"
            $failureStacks += $currentStack
        }
    }
    $expectedFailures = @('com.u1.slicer.slicing.GcodeBaselineDiffTest', 'com.u1.slicer.slicing.MarlinFlavorTest', 'com.u1.slicer.slicing.SingleExtruderMachineTest')
    if ($failureClasses.Count -ne 7 -or @($failureClasses | Where-Object { $_ -notin $expectedFailures }).Count -or
        @($failureClasses | Where-Object { $_ -eq $expectedFailures[0] }).Count -ne 5 -or
        @($failureClasses | Where-Object { $_ -eq $expectedFailures[1] }).Count -ne 1 -or
        @($failureClasses | Where-Object { $_ -eq $expectedFailures[2] }).Count -ne 1) {
        throw 'Other test failures cannot be bypassed by the test-setup resume.'
    }
    $expectedMethods = @(
        'com.u1.slicer.slicing.GcodeBaselineDiffTest#snapshot_flippy_layerTool_plate4',
        'com.u1.slicer.slicing.GcodeBaselineDiffTest#snapshot_calibCube_dualColour',
        'com.u1.slicer.slicing.GcodeBaselineDiffTest#snapshot_3DBenchy_stl',
        'com.u1.slicer.slicing.GcodeBaselineDiffTest#snapshot_coloredBenchy_semm',
        'com.u1.slicer.slicing.GcodeBaselineDiffTest#snapshot_prusa_korokMask',
        'com.u1.slicer.slicing.MarlinFlavorTest#authoredMarlinProfile_emitsMarlinTemperatureCommandsOnly',
        'com.u1.slicer.slicing.SingleExtruderMachineTest#genericOneExtruderProfile_emitsSingleToolGcode'
    )
    if (Compare-Object ($expectedMethods | Sort-Object) ($failureMethods | Sort-Object)) { throw 'Failed methods differ from the diagnosed test setup failures.' }
    if (@($failureStacks | Where-Object { $_ -match '^java.io.FileNotFoundException: /sdcard/Download/u1-slicer-baselines-uid\d+/.+\.snapshot\.txt: open failed: EACCES' }).Count -ne 5 -or
        @($failureStacks | Where-Object { $_ -match '^java.lang.AssertionError: Marlin slice must succeed: Error: Selected machine print validation failed: Relative extruder addressing requires resetting' }).Count -ne 1 -or
        @($failureStacks | Where-Object { $_ -eq 'java.lang.AssertionError: Generic one-extruder slice must succeed: Error: Selected independent-extruder machine profile is missing or inconsistent' }).Count -ne 1) {
        throw 'Failure signatures differ from the diagnosed test setup failures.'
    }
}
if ($ResumeAfterIndependentTests) {
    $passedInputs = Get-Content (Join-Path $PriorFocusedResultsDir 'passed-inputs.json') -Raw | ConvertFrom-Json
    $focused = Get-Content (Join-Path $PriorFocusedResultsDir "focused/shard-0-$Device.json") -Raw | ConvertFrom-Json
    if ($passedInputs.sha256 -ne (Get-FileHash -LiteralPath $CandidateApk).Hash -or
        $passedInputs.testSha256 -ne (Get-FileHash -LiteralPath $TestApk).Hash -or
        $focused.passed -ne 32 -or $focused.failed -ne 0 -or $focused.skipped -ne 0 -or
        $focused.completionCode -ne -1 -or $focused.adbExit -ne 0) {
        throw 'Fixture-only continuation requires identical APKs and the complete 32-pass corrected rerun.'
    }
    $focusedMethods = @()
    foreach ($line in Get-Content (Join-Path $PriorFocusedResultsDir "focused/shard-0-$Device.log")) {
        if ($line -match '^INSTRUMENTATION_STATUS: class=(.+)$') { $currentClass = $Matches[1] }
        if ($line -match '^INSTRUMENTATION_STATUS: test=(.+)$') { $currentMethod = $Matches[1] }
        if ($line -eq 'INSTRUMENTATION_STATUS_CODE: 0') { $focusedMethods += "$currentClass#$currentMethod" }
    }
    if (@($focusedMethods | Sort-Object -Unique).Count -ne 32 -or @($expectedMethods | Where-Object { $_ -notin $focusedMethods }).Count) {
        throw 'Corrected rerun does not contain every diagnosed failing method.'
    }
    $focusedClassCounts = @{
        'com.u1.slicer.slicing.GenericKlipperNativeConfigTest' = 13
        'com.u1.slicer.slicing.GenericIndependentExtruderNativeTest' = 3
        'com.u1.slicer.ui.printer.PrinterEditDialogInstrumentedTest' = 9
        'com.u1.slicer.slicing.GcodeBaselineDiffTest' = 5
        'com.u1.slicer.slicing.MarlinFlavorTest' = 1
        'com.u1.slicer.slicing.SingleExtruderMachineTest' = 1
    }
    foreach ($class in $focusedClassCounts.Keys) {
        if (@($focusedMethods | Where-Object { $_.StartsWith("$class#") }).Count -ne $focusedClassCounts[$class]) {
            throw "Corrected rerun does not contain the complete focused class: $class"
        }
    }
}
function Invoke-CheckedAdb {
    param([Parameter(ValueFromRemainingArguments)][string[]]$AdbArgs)
    & adb -s $Device @AdbArgs
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($AdbArgs -join ' ')" }
}
function Save-BaselineSnapshots {
    & adb -s $Device shell run-as com.u1.slicer.orca test -d files/diagnostics/gcode-baselines
    if ($LASTEXITCODE -ne 0) { return }
    $archive = Join-Path $results 'gcode-baselines.tar'
    $adbExecutable = (Get-Command adb).Source
    & cmd /d /c "`"$adbExecutable`" -s $Device exec-out run-as com.u1.slicer.orca tar -cf - -C files/diagnostics gcode-baselines > `"$archive`""
    if ($LASTEXITCODE -ne 0) { throw 'Cannot preserve G-code baseline evidence.' }
}
function Install-IfChanged([string]$Apk, [string]$Package) {
    $installed = @(& adb -s $Device shell pm path $Package)
    if ($LASTEXITCODE -eq 0 -and $installed.Count -eq 1 -and $installed[0] -match '^package:(/data/app/[A-Za-z0-9_./~+=-]+/base\.apk)$') {
        $deviceHash = @(& adb -s $Device shell sha256sum $Matches[1])
        if ($LASTEXITCODE -eq 0 -and $deviceHash.Count -eq 1 -and
            ($deviceHash[0] -split '\s+')[0] -eq (Get-FileHash -LiteralPath $Apk).Hash) {
            Write-Host "$Package already contains the exact requested APK; no reinstall required."
            return
        }
    }
    Invoke-CheckedAdb install -r (Resolve-Path -LiteralPath $Apk).Path
}
try {
    Install-IfChanged $CandidateApk 'com.u1.slicer.orca'
    Install-IfChanged $TestApk 'com.u1.slicer.orca.test'
    # Run only against disposable test state, never the user's saved hosts or jobs.
    Invoke-CheckedAdb shell am force-stop com.u1.slicer.orca
    Invoke-CheckedAdb shell pm clear com.u1.slicer.orca
    $focusedClasses = 'com.u1.slicer.slicing.GenericKlipperNativeConfigTest,com.u1.slicer.slicing.GenericIndependentExtruderNativeTest,com.u1.slicer.ui.printer.PrinterEditDialogInstrumentedTest'
    if ($ResumeAfterTestSetupFix) { $focusedClasses += ',com.u1.slicer.slicing.GcodeBaselineDiffTest,com.u1.slicer.slicing.MarlinFlavorTest,com.u1.slicer.slicing.SingleExtruderMachineTest' }
    if (!$ResumeAfterIndependentTests) {
        & "$PSScriptRoot/run-instrumented-shard.ps1" -Device $Device `
            -ClassFilter $focusedClasses `
            -ResultsDir (Join-Path $results 'focused')
        @{ sha256 = (Get-FileHash -LiteralPath $CandidateApk).Hash;
            testSha256 = (Get-FileHash -LiteralPath $TestApk).Hash } |
            ConvertTo-Json | Set-Content -LiteralPath (Join-Path $results 'passed-inputs.json')
    }
    if (!$ResumeAfterTestSetupFix) {
        & "$PSScriptRoot/run-instrumented-shard.ps1" -Device $Device -ResultsDir (Join-Path $results 'full-device')
    }
    Save-BaselineSnapshots
    $hostEvidence = Join-Path $results 'phase6-printer-host-e2e-requests.json'
    & "$PSScriptRoot/run-phase6-printer-host-e2e.ps1" -Device $Device -Backend Python -EvidencePath $hostEvidence -RequireEvidence -UseInstalledApks
    if ($LASTEXITCODE -ne 0) { throw 'Mock host fixture driver failed.' }
    # Published 404 uses schema 8. Remove only disposable test data before downgrading.
    Invoke-CheckedAdb shell am force-stop com.u1.slicer.orca
    Invoke-CheckedAdb shell pm clear com.u1.slicer.orca
    & "$PSScriptRoot/run-v4-upgrade-soak.ps1" -Device $Device -CandidateApk $CandidateApk `
        -V4Apk $Published404Apk -AndroidTestApk $TestApk -ResultsDir (Join-Path $results 'upgrade-404')
    & "$PSScriptRoot/run-v405-upgrade-soak.ps1" -Device $Device -CandidateApk $CandidateApk `
        -PublishedApk $Published405Apk -TestApk $TestApk -ResultsDir (Join-Path $results 'upgrade-405')
    & "$PSScriptRoot/run-batch-manual-e2e.ps1" -Device $Device -Suite Both -BambuTarget H2D `
        -NoInstall -IncludePrinterWizardUpload -ResultsDir (Join-Path $results 'e2e') `
        -ArtifactDir (Join-Path $results 'e2e-artifacts') -UseInstalledApksForWizard
    if ($LASTEXITCODE -ne 0) { throw 'Full U1 and Bambu E2E batch failed.' }
} finally {
    # Upgrade drivers may leave a non-debuggable published APK installed on failure.
    try {
        Install-IfChanged $CandidateApk 'com.u1.slicer.orca'
        Save-BaselineSnapshots
    } finally {
        & "$PSScriptRoot/restore-reconciliation-device-data.ps1" -Device $Device `
            -BackupPath $BackupPath -ExpectedSha256 $BackupSha256 -EvidenceDir (Join-Path $results 'restore')
    }
}
Write-Host 'All device, external-fixture, and E2E regressions passed. Original app data is byte-verified.'
