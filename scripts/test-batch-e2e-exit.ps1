param(
    [string]$ResultScript = (Join-Path $PSScriptRoot "batch-e2e-result.ps1")
)

$ErrorActionPreference = "Stop"

function Invoke-ResultScript([int]$Passed, [int]$Failed, [string]$Summary) {
    $output = & powershell.exe -NoProfile -File $ResultScript `
        -Passed $Passed -Failed $Failed -Summary $Summary 2>&1
    return [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output = ($output -join "`n")
    }
}

$passing = Invoke-ResultScript -Passed 8 -Failed 0 -Summary "pass-summary.txt"
if ($passing.ExitCode -ne 0) {
    throw "Passing batch returned exit code $($passing.ExitCode)."
}
if ($passing.Output -notmatch 'passed=8 failed=0 summary=pass-summary\.txt') {
    throw "Passing batch omitted its summary: $($passing.Output)"
}

$failing = Invoke-ResultScript -Passed 7 -Failed 1 -Summary "failure-summary.txt"
if ($failing.ExitCode -eq 0) {
    throw "Failing batch returned exit code 0."
}
if ($failing.Output -notmatch 'passed=7 failed=1 summary=failure-summary\.txt') {
    throw "Failing batch omitted its summary: $($failing.Output)"
}

Write-Host "Batch E2E exit propagation: PASS"
