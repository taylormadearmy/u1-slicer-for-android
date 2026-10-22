param(
    [Parameter(Mandatory = $true)][string]$Device,
    [ValidateSet('Docker', 'Python')][string]$Backend = 'Docker',
    [string]$PythonPath = 'python',
    [string]$EvidencePath,
    [switch]$RequireEvidence,
    [switch]$UseInstalledApks
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ($Device -ne "5A101JEBF24790") {
    throw "Phase 6 host E2E is restricted to the approved Pixel 9a; refusing '$Device'."
}
$env:ANDROID_SERIAL = $Device

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptDir "..")).Path
$mockScript = (Resolve-Path (Join-Path $scriptDir "phase6-printer-host-mock.py")).Path
$containers = @(
    @{ Name = "phase6-octoprint-mock"; Mode = "octoprint"; Port = 18080 },
    @{ Name = "phase6-prusalink-mock"; Mode = "prusalink"; Port = 18081 }
)
$reversedPorts = New-Object System.Collections.Generic.List[int]
$createdContainers = New-Object System.Collections.Generic.List[string]
$startedProcesses = New-Object System.Collections.Generic.List[System.Diagnostics.Process]
if (!$EvidencePath) {
    $EvidencePath = Join-Path $repoRoot "app\build\test-progress\phase6-printer-host-e2e-requests-$(Get-Date -Format 'yyyyMMdd-HHmmss').json"
}
$evidenceDir = Split-Path -Parent $EvidencePath
if (Test-Path -LiteralPath $EvidencePath) { throw 'Request evidence already exists; refusing stale evidence.' }
$evidenceFailure = $null

function Invoke-Checked {
    param([string]$File, [string[]]$Arguments)
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$File failed with exit code $LASTEXITCODE" }
}

try {
    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { throw "adb is required" }
    if ($Backend -eq 'Docker') {
        if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "docker is required" }
        $image = (& docker image inspect python:3.11-slim 2>$null | Out-String)
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($image)) {
            throw "Local Docker image python:3.11-slim is required; refusing to pull or create an image."
        }
        foreach ($container in $containers) {
            & docker container inspect $container.Name 2>$null | Out-Null
            if ($LASTEXITCODE -eq 0) { throw "Container '$($container.Name)' already exists; refusing to modify a pre-existing container." }
        }
        foreach ($container in $containers) {
            $mount = "$mockScript`:/mock/phase6-printer-host-mock.py:ro"
            Invoke-Checked docker @("run", "-d", "--name", $container.Name, "-e", "MODE=$($container.Mode)", "-p", "127.0.0.1:$($container.Port):8080", "-v", $mount, "python:3.11-slim", "python", "/mock/phase6-printer-host-mock.py")
            [void]$createdContainers.Add($container.Name)
        }
    } else {
        foreach ($container in $containers) {
            if (Get-NetTCPConnection -State Listen -LocalPort $container.Port -ErrorAction SilentlyContinue) {
                throw "Port $($container.Port) is already in use; refusing to replace its server."
            }
            $quotedMockScript = '"' + $mockScript + '"'
            $arguments = @($quotedMockScript, '--mode', $container.Mode,
                '--host', '127.0.0.1', '--port', $container.Port)
            $process = Start-Process -FilePath $PythonPath -ArgumentList $arguments -WindowStyle Hidden -PassThru
            [void]$startedProcesses.Add($process)
        }
    }
    foreach ($container in $containers) {
        $healthy = $false
        for ($attempt = 0; $attempt -lt 30; $attempt++) {
            try {
                $result = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$($container.Port)/__health" -TimeoutSec 2
                if ($result.StatusCode -eq 200) { $healthy = $true; break }
            } catch { }
            Start-Sleep -Milliseconds 500
        }
        if (-not $healthy) { throw "Mock '$($container.Name)' did not become healthy." }
    }
    $existingMappings = @(& adb -s $Device reverse --list)
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect existing reverse port mappings' }
    foreach ($port in @(18080, 18081)) {
        if ($existingMappings -match "(^|\s)tcp:$port(\s|$)") {
            throw "Reverse port tcp:$port already exists; refusing to replace it."
        }
        Invoke-Checked adb @("-s", $Device, "reverse", "tcp:$port", "tcp:$port")
        [void]$reversedPorts.Add($port)
    }
    Push-Location $repoRoot
    try {
        if ($UseInstalledApks) {
            $fixtureResults = Join-Path $evidenceDir 'host-fixtures'
            & (Join-Path $repoRoot 'scripts/run-instrumented-shard.ps1') -Device $Device `
                -ClassFilter 'com.u1.slicer.printer.PrinterHostUploadE2ETest' -IncludeExternalFixtures -ResultsDir $fixtureResults
            $summary = Get-Content (Join-Path $fixtureResults "shard-0-$Device.json") -Raw | ConvertFrom-Json
            if ($summary.passed -ne 3 -or $summary.failed -ne 0 -or $summary.skipped -ne 0 -or
                $summary.completionCode -ne -1 -or $summary.adbExit -ne 0) { throw 'Exactly three host fixture methods must pass without failures or skips.' }
        } else {
            & (Join-Path $repoRoot "scripts\run-connected-with-progress.ps1") -Device $Device -ClassFilter "com.u1.slicer.printer.PrinterHostUploadE2ETest" -RunnerArguments @{ notAnnotation = 'org.junit.Ignore' }
            if ($LASTEXITCODE -ne 0) { throw "Connected E2E runner failed with exit code $LASTEXITCODE" }
        }
    } finally {
        Pop-Location
    }
} finally {
    if ($createdContainers.Count -eq $containers.Count -or $startedProcesses.Count -eq $containers.Count) {
        try {
            New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
            $evidence = [ordered]@{}
            foreach ($container in $containers) {
                $response = Invoke-RestMethod -Uri "http://127.0.0.1:$($container.Port)/__history" -TimeoutSec 2
                $evidence[$container.Mode] = $response.requests
            }
            $evidence | ConvertTo-Json -Depth 10 | Set-Content -Path $evidencePath -Encoding utf8
            Write-Host "Phase 6 request evidence: $evidencePath"
        } catch {
            $evidenceFailure = $_
            Write-Warning "Could not save Phase 6 request evidence: $($_.Exception.Message)"
        }
    }
    foreach ($port in $reversedPorts) { & adb -s $Device reverse --remove "tcp:$port" 2>$null | Out-Null }
    foreach ($name in $createdContainers) { & docker rm -f $name 2>$null | Out-Null }
    foreach ($process in $startedProcesses) { if (-not $process.HasExited) { Stop-Process -Id $process.Id } }
    if ($RequireEvidence -and ($evidenceFailure -or !(Test-Path -LiteralPath $EvidencePath))) {
        throw 'Fresh mock-host request evidence is required and could not be saved.'
    }
    if ($RequireEvidence) {
        $savedRequests = Get-Content -LiteralPath $EvidencePath -Raw | ConvertFrom-Json
        if ($null -eq $savedRequests.octoprint -or $null -eq $savedRequests.prusalink -or
            @($savedRequests.octoprint).Count -eq 0 -or @($savedRequests.prusalink).Count -eq 0) {
            throw 'Both mock hosts must record requests in the fresh fixture evidence.'
        }
    }
}
