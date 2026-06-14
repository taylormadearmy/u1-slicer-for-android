param(
    [string]$Device = $env:ANDROID_SERIAL,
    [string[]]$InstrumentedClasses = @(),
    [int]$E2EStartAt = 0,
    [int]$E2EEndAt = 0,
    [switch]$SkipUnit,
    [switch]$SkipInstrumented,
    [switch]$SkipE2E,
    [switch]$Status
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $scriptDir "..")
Set-Location $repoRoot

$manifestPath = Join-Path $scriptDir "confidence-check.psd1"
$manifest = Import-PowerShellDataFile -Path $manifestPath
if (-not $PSBoundParameters.ContainsKey('InstrumentedClasses') -or $InstrumentedClasses.Count -eq 0) {
    $InstrumentedClasses = @($manifest.InstrumentedClasses)
}
if (-not $PSBoundParameters.ContainsKey('E2EStartAt') -or $E2EStartAt -eq 0) {
    $E2EStartAt = [int]$manifest.E2EStartAt
}
if (-not $PSBoundParameters.ContainsKey('E2EEndAt') -or $E2EEndAt -eq 0) {
    $E2EEndAt = [int]$manifest.E2EEndAt
}

$progressDir = Join-Path $repoRoot "app\build\test-progress"
New-Item -ItemType Directory -Force -Path $progressDir | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$statusTxt = Join-Path $progressDir "confidence-check-status.txt"
$statusJson = Join-Path $progressDir "confidence-check-status.json"
$sessionLog = Join-Path $progressDir "confidence-check-$stamp.log"

function Write-State {
    param(
        [string]$Phase,
        [string]$Step,
        [string]$Detail = "",
        [string]$Command = ""
    )

    $now = Get-Date
    $state = [ordered]@{
        timestamp = $now.ToString("o")
        phase = $Phase
        step = $Step
        detail = $Detail
        command = $Command
        device = $Device
        instrumentedClasses = $InstrumentedClasses
        e2eStartAt = $E2EStartAt
        e2eEndAt = $E2EEndAt
        manifestPath = $manifestPath
        log = $sessionLog
    }
    $stateText = @(
        "timestamp: $($state.timestamp)"
        "phase: $Phase"
        "step: $Step"
        "detail: $Detail"
        "command: $Command"
        "device: $Device"
        "instrumentedClasses: $($InstrumentedClasses -join ', ')"
        "e2eRange: $E2EStartAt..$E2EEndAt"
        "manifest: $manifestPath"
        "log: $sessionLog"
    ) -join "`r`n"
    $state | ConvertTo-Json -Depth 4 | Set-Content -Path $statusJson
    Set-Content -Path $statusTxt -Value $stateText
}

function Show-Status {
    if (Test-Path $statusTxt) {
        Get-Content -Path $statusTxt
    } else {
        Write-Host "No confidence-check status file found."
    }
    if (Test-Path $statusJson) {
        Write-Host ""
        Write-Host "JSON: $statusJson"
    }
}

if ($Status) {
    Show-Status
    exit 0
}

if ([string]::IsNullOrWhiteSpace($Device)) {
    $Device = "43211JEKB16931"
}
$env:ANDROID_SERIAL = $Device

$null = New-Item -ItemType File -Force -Path $sessionLog
Start-Transcript -Path $sessionLog -Append | Out-Null

function Invoke-Checked {
    param(
        [scriptblock]$Command,
        [string]$Label
    )

    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Label failed with exit code $LASTEXITCODE"
    }
}

$exitCode = 0
try {
    Write-Host "Running confidence checks"
    Write-Host "Device: $Device"
    Write-Host "Instrumented smoke set: $($InstrumentedClasses.Count) classes"
    Write-Host "E2E smoke range: $E2EStartAt..$E2EEndAt"
    Write-Host "Manifest: $manifestPath"
    Write-Host "Status file: $statusTxt"
    Write-Host "Session log: $sessionLog"
    Write-Host ""

    if (-not $SkipUnit) {
        Write-State -Phase "unit" -Step "starting" -Detail "Running JVM unit suite" -Command "./gradlew.bat testDebugUnitTest --no-daemon"
        Invoke-Checked { & .\gradlew.bat testDebugUnitTest --no-daemon } "JVM unit suite"
        Write-State -Phase "unit" -Step "completed" -Detail "JVM unit suite passed"
    }

    if (-not $SkipInstrumented) {
        for ($i = 0; $i -lt $InstrumentedClasses.Count; $i++) {
            $className = $InstrumentedClasses[$i]
            $step = "instrumented [$($i + 1)/$($InstrumentedClasses.Count)]"
            Write-State -Phase "instrumented" -Step $step -Detail $className -Command "scripts\run-connected-with-progress.ps1 -Device $Device -ClassFilter $className"
            Write-Host ""
            Write-Host "=== Instrumented $($i + 1)/$($InstrumentedClasses.Count): $className ==="
            Invoke-Checked { & .\scripts\run-connected-with-progress.ps1 -Device $Device -ClassFilter $className } $className
            Write-State -Phase "instrumented" -Step $step -Detail "$className passed"
        }
    }

    if (-not $SkipE2E) {
        Write-State -Phase "e2e" -Step "starting" -Detail "Running Smoke-7 manual batch" -Command "scripts\run-batch-manual-e2e.ps1 -Device $Device -StartAt $E2EStartAt -EndAt $E2EEndAt"
        Write-Host ""
        Write-Host "=== Smoke-7 E2E batch ($E2EStartAt..$E2EEndAt) ==="
        Invoke-Checked { & .\scripts\run-batch-manual-e2e.ps1 -Device $Device -StartAt $E2EStartAt -EndAt $E2EEndAt } "Smoke-7 E2E batch"
        Write-State -Phase "e2e" -Step "completed" -Detail "Smoke-7 E2E batch passed"
    }

    Write-State -Phase "completed" -Step "done" -Detail "Selected confidence phases passed"
    Write-Host ""
    Write-Host "Confidence checks completed"
    Write-Host "Status: $statusTxt"
    Write-Host "Log: $sessionLog"
}
catch {
    $exitCode = 1
    Write-State -Phase "failed" -Step "stopped" -Detail ($_.Exception.Message)
    Write-Host ""
    Write-Host "Confidence checks failed: $($_.Exception.Message)"
}
finally {
    try { Stop-Transcript | Out-Null } catch {}
}

exit $exitCode
