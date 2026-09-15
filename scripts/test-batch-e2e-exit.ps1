param(
    [string]$BatchRevision = ""
)

$ErrorActionPreference = "Stop"
$sourceScript = Join-Path $PSScriptRoot "run-batch-manual-e2e.ps1"
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("batch-e2e-exit-" + [guid]::NewGuid())
$harnessScript = Join-Path $tempRoot "run-batch-manual-e2e.ps1"
$originalPath = $env:PATH

function Invoke-Harness([int]$RunnerExit, [string]$CaseName) {
    $results = Join-Path $tempRoot "$CaseName-results"
    $artifacts = Join-Path $tempRoot "$CaseName-artifacts"
    $env:BATCH_E2E_STUB_EXIT = "$RunnerExit"
    $output = & powershell.exe -NoProfile -File $harnessScript `
        -Device "stub-device" `
        -StartAt 28 `
        -EndAt 27 `
        -Suite U1 `
        -IncludePrinterWizardUpload `
        -NoInstall `
        -ResultsDir $results `
        -ArtifactDir $artifacts 2>&1
    $exitCode = $LASTEXITCODE
    $summary = Get-ChildItem $results -Filter "batch-manual-e2e-*.txt" | Select-Object -First 1
    if ($null -eq $summary) { throw "$CaseName run did not write a batch summary: $($output -join "`n")" }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = ($output -join "`n")
        Summary = Get-Content -Raw $summary.FullName
    }
}

try {
    New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
    if ([string]::IsNullOrWhiteSpace($BatchRevision)) {
        $content = Get-Content -Raw $sourceScript
    } else {
        $content = (& git show "$BatchRevision`:scripts/run-batch-manual-e2e.ps1") -join "`n"
        if ($LASTEXITCODE -ne 0) { throw "Could not read batch script from $BatchRevision." }
    }
    $content = $content.Replace(
        '& adb -s $Device',
        '& (Join-Path $PSScriptRoot "adb.cmd") -s $Device'
    )
    Set-Content -LiteralPath $harnessScript -Value $content
    Set-Content -LiteralPath (Join-Path $tempRoot "adb.cmd") -Value "@echo off`r`nexit /b 0`r`n"
    Set-Content -LiteralPath (Join-Path $tempRoot "run-connected-with-progress.ps1") -Value @'
exit [int]$env:BATCH_E2E_STUB_EXIT
'@
    $env:PATH = "$tempRoot;$originalPath"

    $passing = Invoke-Harness -RunnerExit 0 -CaseName "passing"
    if ($passing.ExitCode -ne 0) { throw "Passing batch returned exit code $($passing.ExitCode)." }
    if ($passing.Output -notmatch 'Batch complete: passed=1 failed=0 summary=') {
        throw "Passing batch omitted its final counts: $($passing.Output)"
    }
    if ($passing.Summary -notmatch 'OVERALL: PASS') {
        throw "Passing batch summary omitted the wizard result: $($passing.Summary)"
    }

    $failing = Invoke-Harness -RunnerExit 9 -CaseName "failing"
    if ($failing.ExitCode -eq 0) { throw "Failing wizard batch returned exit code 0." }
    if ($failing.Output -notmatch 'Batch complete: passed=0 failed=1 summary=') {
        throw "Failing batch omitted its final counts: $($failing.Output)"
    }
    if ($failing.Summary -notmatch 'OVERALL: FAIL' -or $failing.Summary -notmatch 'runner exit: 9') {
        throw "Failing batch summary omitted the wizard failure: $($failing.Summary)"
    }

    Write-Host "Batch E2E script exit propagation: PASS"
} finally {
    $env:PATH = $originalPath
    Remove-Item Env:BATCH_E2E_STUB_EXIT -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}
