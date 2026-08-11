param(
    [string]$Device = "43211JEKB16931",
    [string]$AppId = "com.u1.slicer.orca",
    [string]$AssetsDir = "app/src/androidTest/assets",
    [string]$ResultsDir = "C:/tmp/e2e-results",
    [string]$ArtifactDir = "",
    [int]$StartAt = 1,
    [int]$EndAt = 0,
    [ValidateSet('U1', 'Bambu', 'Both')]
    [string]$Suite = 'U1',
    [ValidateSet('A1_MINI', 'A1', 'P1P', 'P1S', 'X1C', 'X1E', 'H2D')]
    [string]$BambuTarget = 'H2D',
    [switch]$KeepExistingResults,
    [switch]$NoInstall
)

$ErrorActionPreference = "Stop"

function Write-Step($Message) {
    $stamp = Get-Date -Format "HH:mm:ss"
    Write-Host "[$stamp] $Message"
}

function Invoke-Adb {
    param([Parameter(Position = 0, ValueFromRemainingArguments = $true)][string[]]$Args)
    & adb -s $Device @Args
}

function Get-SafeName([string]$Name) {
    return ($Name -replace '[^A-Za-z0-9._-]+', '_').Trim("_")
}

function Capture-Screenshot([string]$Path) {
    Invoke-Adb shell screencap -p /sdcard/ss.png | Out-Null
    Invoke-Adb pull /sdcard/ss.png $Path | Out-Null
}

function Get-TestCmdLog([int]$Last = 160) {
    return & adb -s $Device logcat -s TestCmd -d | Select-Object -Last $Last
}

function Broadcast([string]$Action, [string[]]$ExtraArgs = @()) {
    $args = @("shell", "am", "broadcast", "-a", "$($AppId).$Action") + $ExtraArgs + @("-p", $AppId)
    Invoke-Adb @args | Out-Null
}

function Dump-State {
    Broadcast "DUMP_STATE"
    Start-Sleep -Milliseconds 600
    return Get-TestCmdLog
}

function Wait-State {
    param(
        [string]$Pattern,
        [int]$TimeoutSeconds,
        [string]$Label
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $log = Dump-State
        $joined = $log -join "`n"
        $lastDumpMarker = $joined.LastIndexOf("=== DUMP_STATE ===")
        $latestState = if ($lastDumpMarker -ge 0) { $joined.Substring($lastDumpMarker) } else { $joined }
        if ($latestState -match $Pattern) {
            return $joined
        }
        if ($latestState -match "State: Error") {
            return $joined
        }
        Write-Step "$Label still pending..."
        Start-Sleep -Seconds 5
    }
    return ((Dump-State) -join "`n")
}

function Wait-LogPattern {
    param(
        [string]$Pattern,
        [int]$TimeoutSeconds,
        [string]$Label
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $log = Dump-State
        $joined = $log -join "`n"
        if ($joined -match $Pattern) {
            return $joined
        }
        Write-Step "$Label still pending..."
        Start-Sleep -Seconds 5
    }
    return ((Dump-State) -join "`n")
}

function Wait-LoadReady {
    param(
        $Scenario
    )

    if ($Scenario.Plate -ne $null) {
        return Wait-State "State: ModelLoaded|State: SliceComplete|isMultiPlate: true|Multi-plate: .*showing selector" $Scenario.LoadTimeoutSeconds "load $($Scenario.Name)"
    }

    return Wait-State "State: ModelLoaded|State: SliceComplete" $Scenario.LoadTimeoutSeconds "load $($Scenario.Name)"
}

function Get-GcodeChecks([string]$GcodePath) {
    $checks = [ordered]@{}
    $checks["gcodePath"] = $GcodePath
    $checks["T4_T9"] = (Invoke-Adb shell run-as $AppId grep -cE '^T[4-9]' $GcodePath | Select-Object -First 1).Trim()
    for ($i = 0; $i -le 9; $i++) {
        $count = (Invoke-Adb shell run-as $AppId grep -cE "^T$i" $GcodePath | Select-Object -First 1).Trim()
        $checks["T$i"] = $count
    }
    $checks["filament_type"] = ((Invoke-Adb shell run-as $AppId awk /filament_type/ $GcodePath) -join " | ")
    $checks["filament_used"] = ((Invoke-Adb shell run-as $AppId grep filament.used $GcodePath) -join " | ")
    $checks["printer_model"] = ((Invoke-Adb shell run-as $AppId grep printer_model $GcodePath) -join " | ")
    $foreignMacros = 0
    foreach ($pattern in @('__U1_SLICER', 'Snapmaker.*U1', 'PRINT_START', 'PRINT_END')) {
        $countOutput = @(Invoke-Adb shell run-as $AppId grep -c $pattern $GcodePath)
        $count = if ($countOutput.Count -gt 0) { ([string]$countOutput[0]).Trim() } else { "0" }
        if ($count -match '^\d+$') { $foreignMacros += [int]$count }
    }
    $checks["foreign_machine_macros"] = "$foreignMacros"
    return $checks
}

$bambuPrinterModels = @{
    A1_MINI = 'Bambu Lab A1 mini'
    A1      = 'Bambu Lab A1'
    P1P     = 'Bambu Lab P1P'
    P1S     = 'Bambu Lab P1S'
    X1C     = 'Bambu Lab X1 Carbon'
    X1E     = 'Bambu Lab X1E'
    H2D     = 'Bambu Lab H2D'
}

function Run-Scenario($Scenario, [int]$Index, [int]$Total) {
    $file = $Scenario.File
    $safe = "{0:00}-{1}" -f $Index, (Get-SafeName $Scenario.Name)
    $extension = [System.IO.Path]::GetExtension($file)
    $deviceFile = "$safe$extension"
    $resultFile = Join-Path $ResultsDir "$safe.txt"
    $logFile = Join-Path $ArtifactDir "$safe-TestCmd.log"
    $prepareShot = Join-Path $ArtifactDir "$safe-prepare.png"
    $summaryShot = Join-Path $ArtifactDir "$safe-after-slice.png"
    $hostPath = Join-Path $AssetsDir $file

    Write-Step "[$Index/$Total] START $($Scenario.Name)"
    if (!(Test-Path $hostPath)) {
        "SCENARIO: $($Scenario.Name)`nOVERALL: FAIL`nmissing asset: $hostPath" | Set-Content $resultFile
        Write-Step "[$Index/$Total] FAIL missing asset"
        return $false
    }

    Invoke-Adb logcat -c | Out-Null
    Invoke-Adb shell am force-stop $AppId | Out-Null
    Invoke-Adb shell am start -n "$AppId/com.u1.slicer.MainActivity" | Out-Null
    Start-Sleep -Seconds 8

    if ($Scenario.Suite -eq 'Bambu') {
        Broadcast "SET_BAMBU_TARGET" @("--es", "model", $Scenario.Target)
        Start-Sleep -Seconds 2
    } elseif ($Scenario.Suite -eq 'U1') {
        Broadcast "SET_U1_TARGET"
        Start-Sleep -Seconds 2
    }

    Invoke-Adb shell "run-as $AppId mkdir -p files" | Out-Null
    Invoke-Adb shell "run-as $AppId sh -c 'rm -rf files/transient files/embedded_*.3mf files/sanitized_*.3mf files/output.gcode'" | Out-Null
    Invoke-Adb push $hostPath "/data/local/tmp/$deviceFile" | Out-Null
    Invoke-Adb shell "run-as $AppId cp /data/local/tmp/$deviceFile files/$deviceFile" | Out-Null
    $copied = (Invoke-Adb shell "run-as $AppId ls -l files/$deviceFile" 2>&1) -join "`n"
    if ($copied -notmatch [regex]::Escape($deviceFile)) {
        "SCENARIO: $($Scenario.Name)`nOVERALL: FAIL`ncopy to app-private storage failed: $copied" | Set-Content $resultFile
        Write-Step "[$Index/$Total] FAIL app-private copy"
        return $false
    }

    Broadcast "LOAD_FILE" @("--es", "path", $deviceFile)
    $loadLog = Wait-LoadReady $Scenario
    Capture-Screenshot $prepareShot

    $plateSelectorReady = $loadLog -match "isMultiPlate: true|Multi-plate: .*showing selector"
    $loadOk = $loadLog -match "State: ModelLoaded|State: SliceComplete" -or ($Scenario.Plate -ne $null -and $plateSelectorReady)
    $plateOk = $Scenario.Plate -eq $null
    if ($loadOk -and $Scenario.Plate -ne $null) {
        Write-Step "[$Index/$Total] selecting plate $($Scenario.Plate)"
        Broadcast "SELECT_PLATE" @("--ei", "plate", "$($Scenario.Plate)")
        $loadLog = Wait-State "State: ModelLoaded|State: SliceComplete" $Scenario.LoadTimeoutSeconds "plate $($Scenario.Plate) $($Scenario.Name)"
        $plateOk = $loadLog -match "State: ModelLoaded|State: SliceComplete"
        $loadOk = $plateOk
        Capture-Screenshot (Join-Path $ArtifactDir "$safe-plate-$($Scenario.Plate).png")
    }

    $gcodeChecks = $null
    $rawGcodeChecks = $null
    $sliceOk = $false
    $gcodePath = ""
    $exportPath = ""
    if ($loadOk -and $Scenario.Kind -eq "slice") {
        Write-Step "[$Index/$Total] slicing $($Scenario.Name)"
        Broadcast "SLICE"
        $sliceLog = Wait-State "State: SliceComplete" $Scenario.SliceTimeoutSeconds "slice $($Scenario.Name)"
        Capture-Screenshot $summaryShot
        $sliceOk = $sliceLog -match "State: SliceComplete"
        if ($sliceLog -match "gcodePath=([^,\)]+)") {
            $gcodePath = $Matches[1]
            $rawGcodeChecks = Get-GcodeChecks $gcodePath
            Broadcast "EXPORT_GCODE"
            $exportLog = Wait-LogPattern "EXPORT_GCODE: success\s+path=" 180 "export $($Scenario.Name)"
            if ($exportLog -match "EXPORT_GCODE: success\s+path=([^ \r\n]+)") {
                $exportPath = $Matches[1]
                $gcodeChecks = Get-GcodeChecks $exportPath
            }
        }
    } else {
        $sliceLog = $loadLog
        Capture-Screenshot $summaryShot
    }

    (Get-TestCmdLog 260) | Set-Content $logFile

    $targetOk = $true
    $machineMacrosOk = $true
    if ($Scenario.Suite -eq 'Bambu' -and $gcodeChecks -ne $null) {
        $targetOk = $gcodeChecks["printer_model"] -match [regex]::Escape($bambuPrinterModels[$Scenario.Target])
        $machineMacrosOk = [int]$gcodeChecks["foreign_machine_macros"] -eq 0
    }
    $toolRangeOk = if ($Scenario.Suite -eq 'Bambu') {
        # Bambu H2D and multi-material profiles legitimately emit extended
        # tool slots; the U1 smoke corpus requires the legacy T0..T3 range.
        $true
    } else {
        $gcodeChecks -ne $null -and $gcodeChecks["T4_T9"] -eq "0"
    }
    $sliceChecksOk = $sliceOk -and $gcodeChecks -ne $null -and
        $toolRangeOk -and $targetOk -and $machineMacrosOk
    $status = if ($loadOk -and ($Scenario.Kind -ne "slice" -or $sliceChecksOk)) { "PASS" } else { "FAIL" }
    $lines = @(
        "SCENARIO: $($Scenario.Name)",
        "DATE: $(Get-Date -Format s)",
        "FILE: $file",
        "SUITE: $($Scenario.Suite)",
        "BAMBU_TARGET: $($Scenario.Target)",
        "PLATE: $($Scenario.Plate)",
        "KIND: $($Scenario.Kind)",
        "PREPARE_SCREENSHOT: $prepareShot",
        "AFTER_SLICE_SCREENSHOT: $summaryShot",
        "LOG: $logFile",
        "---",
        "load: $(if ($loadOk) { 'PASS' } else { 'FAIL' })",
        "plateSelect: $(if ($Scenario.Plate -eq $null) { 'N/A' } elseif ($plateOk) { 'PASS' } else { 'FAIL' })",
        "slice: $(if ($Scenario.Kind -ne 'slice') { 'N/A' } elseif ($sliceOk) { 'PASS' } else { 'FAIL' })",
        "targetIdentity: $(if ($Scenario.Suite -ne 'Bambu' -or $targetOk) { 'PASS' } else { 'FAIL' })",
        "foreignMachineMacros: $(if ($Scenario.Suite -ne 'Bambu' -or $machineMacrosOk) { 'PASS' } else { 'FAIL' })",
        "rawGcodePath: $gcodePath",
        "exportGcodePath: $exportPath"
    )
    if ($rawGcodeChecks -ne $null) {
        foreach ($key in $rawGcodeChecks.Keys) {
            $lines += "raw_${key}: $($rawGcodeChecks[$key])"
        }
    }
    if ($gcodeChecks -ne $null) {
        foreach ($key in $gcodeChecks.Keys) {
            $lines += "export_${key}: $($gcodeChecks[$key])"
        }
    }
    $lines += "OVERALL: $status"
    $lines | Set-Content $resultFile

    Write-Step "[$Index/$Total] $status $($Scenario.Name)"
    return $status -eq "PASS"
}

if (!(Test-Path $ResultsDir)) {
    New-Item -ItemType Directory -Path $ResultsDir -Force | Out-Null
}
if (!$KeepExistingResults) {
    Get-ChildItem $ResultsDir -Filter "??-*.txt" -ErrorAction SilentlyContinue | Remove-Item -Force
}

if ([string]::IsNullOrWhiteSpace($ArtifactDir)) {
    $ArtifactDir = Join-Path (Resolve-Path ".") ("e2e-artifacts/batch-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
}
New-Item -ItemType Directory -Path $ArtifactDir -Force | Out-Null
Set-Content (Join-Path $ResultsDir "current-artifact-dir.txt") $ArtifactDir

$scenarios = @(
    @{ Name="3DBenchy STL"; File="3DBenchy.stl"; Plate=$null; Kind="slice"; LoadTimeoutSeconds=120; SliceTimeoutSeconds=900 },
    @{ Name="tetrahedron STL"; File="tetrahedron.stl"; Plate=$null; Kind="slice"; LoadTimeoutSeconds=60; SliceTimeoutSeconds=300 },
    @{ Name="calib cube dual colour"; File="calib-cube-10-dual-colour-merged.3mf"; Plate=1; Kind="slice"; LoadTimeoutSeconds=120; SliceTimeoutSeconds=900 },
    @{ Name="colored 3DBenchy"; File="colored_3DBenchy (1).3mf"; Plate=1; Kind="slice"; LoadTimeoutSeconds=180; SliceTimeoutSeconds=1800 },
    @{ Name="H2C multi color Benchy"; File="3DBenchy-H2C-Multi-Color.3mf"; Plate=1; Kind="slice"; LoadTimeoutSeconds=180; SliceTimeoutSeconds=1800 },
    @{ Name="Dragon Scale infinity plate 3"; File="Dragon Scale infinity.3mf"; Plate=3; Kind="slice"; LoadTimeoutSeconds=180; SliceTimeoutSeconds=1800 },
    @{ Name="Dragon Scale 1 plate 2 colours"; File="Dragon Scale infinity-1-plate-2-colours.3mf"; Plate=1; Kind="slice"; LoadTimeoutSeconds=180; SliceTimeoutSeconds=1800 },
    @{ Name="Dragon Scale new plate"; File="Dragon Scale infinity-1-plate-2-colours-new-plate.3mf"; Plate=1; Kind="slice"; LoadTimeoutSeconds=180; SliceTimeoutSeconds=1800 },
    @{ Name="flippy flappy mini plate 4"; File="flippy+flappy+mini.3mf"; Plate=4; Kind="slice"; LoadTimeoutSeconds=180; SliceTimeoutSeconds=1800 },
    @{ Name="flippy flappy mini painted"; File="flippy+flappy+mini-with-plate-painted.3mf"; Plate=1; Kind="slice"; LoadTimeoutSeconds=180; SliceTimeoutSeconds=1800 },
    @{ Name="Shashibo plate 5"; File="Shashibo-h2s-textured.3mf"; Plate=5; Kind="slice"; LoadTimeoutSeconds=180; SliceTimeoutSeconds=1800 },
    @{ Name="Button for S trousers"; File="Button-for-S-trousers.3mf"; Plate=1; Kind="slice"; LoadTimeoutSeconds=180; SliceTimeoutSeconds=1800 },
    @{ Name="Buzz multipart"; File="Buzz_Multipart_3MF_Bambu.3mf"; Plate=9; Kind="slice"; LoadTimeoutSeconds=600; SliceTimeoutSeconds=3600 },
    @{ Name="Korok mask 4 colour"; File="PrusaSlicer-printables-Korok_mask_4colour.3mf"; Plate=1; Kind="slice"; LoadTimeoutSeconds=240; SliceTimeoutSeconds=1800 },
    @{ Name="Flarewing dragon 4 filament"; File="Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf"; Plate=1; Kind="slice"; LoadTimeoutSeconds=600; SliceTimeoutSeconds=10800 },
    @{ Name="foldy coaster"; File="foldy+coaster (1).3mf"; Plate=1; Kind="slice"; LoadTimeoutSeconds=240; SliceTimeoutSeconds=1800 },
    @{ Name="slip slide spin fidget plate 3"; File="slip slide spin fidget.3mf"; Plate=3; Kind="slice"; LoadTimeoutSeconds=240; SliceTimeoutSeconds=1800 },
    @{ Name="u1 auxiliary fan cover"; File="u1-auxiliary-fan-cover-hex_mw.3mf"; Plate=1; Kind="slice"; LoadTimeoutSeconds=180; SliceTimeoutSeconds=1200 },
    @{ Name="old legacy 3mf"; File="old.3mf"; Plate=1; Kind="slice"; LoadTimeoutSeconds=180; SliceTimeoutSeconds=1800 },
    @{ Name="die single colour"; File="die-single-colour.3mf"; Plate=1; Kind="slice"; LoadTimeoutSeconds=180; SliceTimeoutSeconds=900 },
    @{ Name="Goat gray"; File="Goat ( Gray ).3mf"; Plate=1; Kind="slice"; LoadTimeoutSeconds=180; SliceTimeoutSeconds=1200 },
    @{ Name="Leo supports"; File="Leo_test_Supports.3mf"; Plate=1; Kind="slice"; LoadTimeoutSeconds=180; SliceTimeoutSeconds=1800 },
    @{ Name="Sensory twist ball"; File="SENSORY+TWIST+BALL+FIDGETS+optimised.3mf"; Plate=1; Kind="slice"; LoadTimeoutSeconds=300; SliceTimeoutSeconds=2400 },
    @{ Name="hanging pre cut colour"; File="hanging+pre+cut+colour+3mf.3mf"; Plate=1; Kind="slice"; LoadTimeoutSeconds=300; SliceTimeoutSeconds=2400 },
    @{ Name="spiderman hanging pre cut"; File="spiderman-hanging-pre-cut.3mf"; Plate=1; Kind="slice"; LoadTimeoutSeconds=300; SliceTimeoutSeconds=2400 },
    @{ Name="skywing seawing silkwing"; File="skywing-seawing-silkwing.3mf"; Plate=1; Kind="slice"; LoadTimeoutSeconds=300; SliceTimeoutSeconds=2400 },
    @{ Name="F1 calendar"; File="2026+F1+CALENDAR+-+DATES+&+TRACK+NAMES+(P_X+SERIES).3mf"; Plate=1; Kind="slice"; LoadTimeoutSeconds=900; SliceTimeoutSeconds=7200 }
)

$scenarios | ForEach-Object { $_.Suite = 'U1'; $_.Target = '' }
$bambuScenarios = @($scenarios[0..6] | ForEach-Object {
    $copy = $_.Clone()
    $copy.Name = "Bambu $BambuTarget - $($_.Name)"
    $copy.Suite = 'Bambu'
    $copy.Target = $BambuTarget
    $copy
})
$runScenarios = switch ($Suite) {
    'U1'    { $scenarios }
    'Bambu' { $bambuScenarios }
    'Both'  { @($scenarios + $bambuScenarios) }
}

Write-Step "Batch manual E2E starting: suite=$Suite target=$BambuTarget scenarios=$($runScenarios.Count), artifacts=$ArtifactDir"
Invoke-Adb get-state | Out-Host
Invoke-Adb shell getprop ro.product.model | Out-Host

if (!$NoInstall) {
    Write-Step "Building debug APK via Gradle"
    & .\gradlew.bat --no-daemon :app:assembleDebug
    Write-Step "Installing debug APK on $Device"
    Invoke-Adb install -r "app/build/outputs/apk/debug/app-debug.apk" | Out-Host
}

Invoke-Adb shell pm grant $AppId android.permission.POST_NOTIFICATIONS 2>$null

$passed = 0
$failed = 0
$last = if ($EndAt -gt 0) { [Math]::Min($EndAt, $runScenarios.Count) } else { $runScenarios.Count }
for ($i = $StartAt; $i -le $last; $i++) {
    $ok = Run-Scenario $runScenarios[$i - 1] $i $runScenarios.Count
    if ($ok) { $passed++ } else { $failed++ }
}

$summary = Join-Path $ResultsDir ("batch-manual-e2e-" + (Get-Date -Format "yyyy-MM-dd-HHmmss") + ".txt")
Get-ChildItem $ResultsDir -Filter "*.txt" |
    Where-Object { $_.Name -notmatch "current-artifact-dir|batch-manual-e2e" } |
    Sort-Object Name |
    ForEach-Object { Get-Content $_.FullName; ""; } |
    Set-Content $summary

Write-Step "Batch complete: passed=$passed failed=$failed summary=$summary"

