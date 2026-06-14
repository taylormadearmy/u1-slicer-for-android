param(
    [string]$Device = $env:ANDROID_SERIAL,
    [string]$ClassFilter = "",
    [switch]$NoDaemon = $true
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $scriptDir "..")
Set-Location $repoRoot

if ([string]::IsNullOrWhiteSpace($Device)) {
    $Device = "43211JEKB16931"
}
$env:ANDROID_SERIAL = $Device

$resultRoot = Join-Path $repoRoot "app\build\outputs\androidTest-results\connected\debug"
$progressDir = Join-Path $repoRoot "app\build\test-progress"
New-Item -ItemType Directory -Force -Path $progressDir | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$gradleLog = Join-Path $progressDir "connected-$stamp.log"

$gradleArgs = @()
if ($NoDaemon) {
    $gradleArgs += "--no-daemon"
}
$gradleArgs += "-Pkotlin.incremental=false"
if (-not [string]::IsNullOrWhiteSpace($ClassFilter)) {
    $gradleArgs += "-Pandroid.testInstrumentationRunnerArguments.class=$ClassFilter"
}
$gradleArgs += "connectedDebugAndroidTest"

Write-Host "Device: $Device"
if (-not [string]::IsNullOrWhiteSpace($ClassFilter)) {
    Write-Host "Class filter: $ClassFilter"
}
Write-Host "Gradle log: $gradleLog"
Write-Host ""

if (Test-Path $resultRoot) {
    Get-ChildItem -Path $resultRoot -Recurse -Filter "test-results.log" -ErrorAction SilentlyContinue |
        Remove-Item -Force
}

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = Join-Path $repoRoot "gradlew.bat"
$psi.Arguments = ($gradleArgs | ForEach-Object {
    if ($_ -match '[\s,]') {
        '"' + ($_ -replace '"', '\"') + '"'
    } else {
        $_
    }
}) -join " "
$psi.WorkingDirectory = $repoRoot
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false

$process = New-Object System.Diagnostics.Process
$process.StartInfo = $psi
[void]$process.Start()

$stdoutTask = $process.StandardOutput.ReadToEndAsync()
$stderrTask = $process.StandardError.ReadToEndAsync()

$seenOffset = 0L
$seenPath = ""
$currentClass = ""
$currentTest = ""
$currentIndex = ""
$totalTests = ""
$passed = 0
$failed = 0
$skipped = 0

function Read-NewInstrumentationLines {
    param([string]$Path, [ref]$Offset)

    if ([string]::IsNullOrEmpty($Path) -or -not (Test-Path $Path)) {
        return @()
    }

    $fs = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    try {
        if ($Offset.Value -gt $fs.Length) {
            $Offset.Value = 0
        }
        $fs.Seek($Offset.Value, [System.IO.SeekOrigin]::Begin) | Out-Null
        $reader = New-Object System.IO.StreamReader($fs)
        $text = $reader.ReadToEnd()
        $Offset.Value = $fs.Position
        if ([string]::IsNullOrEmpty($text)) {
            return @()
        }
        return $text -split "`r?`n"
    } finally {
        $fs.Dispose()
    }
}

function Get-CurrentResultLog {
    if (-not (Test-Path $resultRoot)) {
        return ""
    }
    $match = Get-ChildItem -Path $resultRoot -Recurse -Filter "test-results.log" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $match) {
        return ""
    }
    return $match.FullName
}

function Show-ProgressLine {
    param(
        [string]$Status,
        [string]$Index,
        [string]$Total,
        [string]$ClassName,
        [string]$TestName
    )

    $prefix = if ($Index -and $Total) { "[$Index/$Total]" } else { "[?/??]" }
    $shortClass = if ($ClassName) { ($ClassName -replace '^com\.u1\.slicer\.', '') } else { "unknown" }
    Write-Host ("{0} {1} {2}#{3}" -f (Get-Date -Format "HH:mm:ss"), $prefix, $shortClass, $TestName)
}

while (-not $process.HasExited) {
    $resultLog = Get-CurrentResultLog
    if ($resultLog -ne $seenPath) {
        $seenPath = $resultLog
        $seenOffset = 0L
    }
    foreach ($line in (Read-NewInstrumentationLines -Path $resultLog -Offset ([ref]$seenOffset))) {
        if ($line -match '^INSTRUMENTATION_STATUS: class=(.+)$') {
            $currentClass = $matches[1]
        } elseif ($line -match '^INSTRUMENTATION_STATUS: test=(.+)$') {
            $currentTest = $matches[1]
        } elseif ($line -match '^INSTRUMENTATION_STATUS: current=(\d+)$') {
            $currentIndex = $matches[1]
        } elseif ($line -match '^INSTRUMENTATION_STATUS: numtests=(\d+)$') {
            $totalTests = $matches[1]
        } elseif ($line -match '^INSTRUMENTATION_STATUS_CODE: 1$') {
            Show-ProgressLine "START" $currentIndex $totalTests $currentClass $currentTest
        } elseif ($line -match '^INSTRUMENTATION_STATUS_CODE: 0$') {
            $passed++
            Write-Host ("{0} PASS  [{1} passed, {2} failed, {3} skipped]" -f (Get-Date -Format "HH:mm:ss"), $passed, $failed, $skipped)
        } elseif ($line -match '^INSTRUMENTATION_STATUS_CODE: -2$') {
            $failed++
            Write-Host ("{0} FAIL  {1}#{2} [{3} passed, {4} failed]" -f (Get-Date -Format "HH:mm:ss"), $currentClass, $currentTest, $passed, $failed)
        } elseif ($line -match '^INSTRUMENTATION_STATUS_CODE: -3$') {
            $skipped++
            Write-Host ("{0} SKIP  {1}#{2} [{3} skipped]" -f (Get-Date -Format "HH:mm:ss"), $currentClass, $currentTest, $skipped)
        }
    }
    Start-Sleep -Seconds 2
}

$stdout = $stdoutTask.GetAwaiter().GetResult()
$stderr = $stderrTask.GetAwaiter().GetResult()
$stdout | Set-Content -Path $gradleLog
if (-not [string]::IsNullOrWhiteSpace($stderr)) {
    Add-Content -Path $gradleLog -Value "`n--- STDERR ---`n$stderr"
}

$resultLog = Get-CurrentResultLog
if ($resultLog -ne $seenPath) {
    $seenPath = $resultLog
    $seenOffset = 0L
}
foreach ($line in (Read-NewInstrumentationLines -Path $resultLog -Offset ([ref]$seenOffset))) {
    if ($line -match '^INSTRUMENTATION_STATUS: class=(.+)$') {
        $currentClass = $matches[1]
    } elseif ($line -match '^INSTRUMENTATION_STATUS: test=(.+)$') {
        $currentTest = $matches[1]
    } elseif ($line -match '^INSTRUMENTATION_STATUS: current=(\d+)$') {
        $currentIndex = $matches[1]
    } elseif ($line -match '^INSTRUMENTATION_STATUS: numtests=(\d+)$') {
        $totalTests = $matches[1]
    } elseif ($line -match '^INSTRUMENTATION_STATUS_CODE: 1$') {
        Show-ProgressLine "START" $currentIndex $totalTests $currentClass $currentTest
    } elseif ($line -match '^INSTRUMENTATION_STATUS_CODE: 0$') {
        $passed++
        Write-Host ("{0} PASS  [{1} passed, {2} failed, {3} skipped]" -f (Get-Date -Format "HH:mm:ss"), $passed, $failed, $skipped)
    } elseif ($line -match '^INSTRUMENTATION_STATUS_CODE: -2$') {
        $failed++
        Write-Host ("{0} FAIL  {1}#{2} [{3} passed, {4} failed]" -f (Get-Date -Format "HH:mm:ss"), $currentClass, $currentTest, $passed, $failed)
    } elseif ($line -match '^INSTRUMENTATION_STATUS_CODE: -3$') {
        $skipped++
        Write-Host ("{0} SKIP  {1}#{2} [{3} skipped]" -f (Get-Date -Format "HH:mm:ss"), $currentClass, $currentTest, $skipped)
    }
}

Write-Host ""
Write-Host "Gradle exited with code $($process.ExitCode). Full Gradle output: $gradleLog"
exit $process.ExitCode
