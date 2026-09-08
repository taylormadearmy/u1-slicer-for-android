param(
    [string]$Device = "NF12209000455"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ($Device -ne "NF12209000455") {
    throw "Phase 6 host E2E is restricted to device NF12209000455; refusing '$Device'."
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
$evidenceDir = Join-Path $repoRoot "app\build\test-progress"
$evidencePath = Join-Path $evidenceDir "phase6-printer-host-e2e-requests-$(Get-Date -Format 'yyyyMMdd-HHmmss').json"

function Invoke-Checked {
    param([string]$File, [string[]]$Arguments)
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$File failed with exit code $LASTEXITCODE" }
}

try {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "docker is required" }
    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { throw "adb is required" }
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
    foreach ($port in @(18080, 18081)) {
        Invoke-Checked adb @("-s", $Device, "reverse", "tcp:$port", "tcp:$port")
        [void]$reversedPorts.Add($port)
    }
    Push-Location $repoRoot
    try {
        & (Join-Path $repoRoot "scripts\run-connected-with-progress.ps1") -Device $Device -ClassFilter "com.u1.slicer.printer.PrinterHostUploadE2ETest"
        if ($LASTEXITCODE -ne 0) { throw "Connected E2E runner failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
} finally {
    if ($createdContainers.Count -eq $containers.Count) {
        try {
            New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
            $evidence = [ordered]@{}
            foreach ($container in $containers) {
                $response = Invoke-RestMethod -Uri "http://127.0.0.1:$($container.Port)/__requests" -TimeoutSec 2
                $evidence[$container.Mode] = $response.requests
            }
            $evidence | ConvertTo-Json -Depth 10 | Set-Content -Path $evidencePath -Encoding utf8
            Write-Host "Phase 6 request evidence: $evidencePath"
        } catch {
            Write-Warning "Could not save Phase 6 request evidence: $($_.Exception.Message)"
        }
    }
    foreach ($port in $reversedPorts) { & adb -s $Device reverse --remove "tcp:$port" 2>$null | Out-Null }
    foreach ($name in $createdContainers) { & docker rm -f $name 2>$null | Out-Null }
}
