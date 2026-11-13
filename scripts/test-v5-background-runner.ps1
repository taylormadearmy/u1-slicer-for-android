$ErrorActionPreference = 'Stop'
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('v5-background-' + [guid]::NewGuid())
$originalFailure = $env:V5_STUB_FAILURE
try {
    $scripts = Join-Path $tempRoot 'scripts'
    New-Item -ItemType Directory -Path $scripts -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'run-v5-reconciliation-background.ps1') -Destination $scripts
    $inputFile = Join-Path $tempRoot 'input.bin'
    New-Item -ItemType File -Path $inputFile | Out-Null
    $inputHash = (Get-FileHash -LiteralPath $inputFile -Algorithm SHA256).Hash
    foreach ($phase in @('debug', 'release')) {
        $name = if ($phase -eq 'debug') { 'run-v5-reconciliation-regression.ps1' } else { 'run-release-native-regression.ps1' }
        $stub = "Add-Content -LiteralPath '$tempRoot/calls.txt' -Value '$phase'; if (`$env:V5_STUB_FAILURE -eq '$phase') { throw '$phase failure' }; if (`$env:V5_STUB_FAILURE -eq '$phase-exit') { exit 1 }"
        Set-Content -LiteralPath (Join-Path $scripts $name) -Value $stub
    }
    foreach ($failure in @('debug', 'release', 'debug-exit', 'release-exit', 'none')) {
        $env:V5_STUB_FAILURE = $failure
        $root = Join-Path $tempRoot $failure
        if (Test-Path -LiteralPath (Join-Path $tempRoot 'calls.txt')) { Remove-Item -LiteralPath (Join-Path $tempRoot 'calls.txt') }
        try {
        $ErrorActionPreference = 'Continue'
        $output = & powershell.exe -NoProfile -File (Join-Path $scripts 'run-v5-reconciliation-background.ps1') `
            -Device 5A101JEBF24790 -CandidateApk $inputFile -TestApk $inputFile -ReleaseApk $inputFile `
            -Published405Apk $inputFile -Published404Apk $inputFile -BackupPath $inputFile -BackupSha256 $inputHash -ResultsRoot $root 2>&1
        $exitCode = $LASTEXITCODE
        } finally { $ErrorActionPreference = 'Stop' }
        $status = Get-Content -LiteralPath (Join-Path $root 'status.json') -Raw | ConvertFrom-Json
        $calls = @(Get-Content -LiteralPath (Join-Path $tempRoot 'calls.txt'))
        if ($failure -eq 'none') {
            if ($exitCode -ne 0 -or !$status.success -or $status.phase -ne 'complete' -or ($calls -join ',') -ne 'debug,release') { throw 'Successful workflow did not record both phases and completion.' }
        } else {
            $expectedCalls = if ($failure -like 'debug*') { 'debug' } else { 'debug,release' }
            $expectedError = switch ($failure) {
                'debug-exit' { 'Debug regression driver returned exit code 1.' }
                'release-exit' { 'Release native driver returned exit code 1.' }
                default { "$failure failure" }
            }
            if ($exitCode -eq 0 -or $status.success -ne $false -or $status.error -ne $expectedError -or ($calls -join ',') -ne $expectedCalls) { throw "Failure did not stop or record $failure`: exit=$exitCode success=$($status.success) error=$($status.error) calls=$($calls -join ',') output=$($output -join ' ')" }
        }
        if (!$status.finishedAt) { throw 'Terminal status must record its finish time.' }
        $lock = [IO.File]::Open((Join-Path $tempRoot '.audit/v5-reconciliation/device-5A101JEBF24790.lock'), [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
        $lock.Dispose()
    }
    $heldRoot = Join-Path $tempRoot 'held-lock'
    $heldLock = [IO.File]::Open((Join-Path $tempRoot '.audit/v5-reconciliation/device-5A101JEBF24790.lock'), [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    try {
        $ErrorActionPreference = 'Continue'
        $output = & powershell.exe -NoProfile -File (Join-Path $scripts 'run-v5-reconciliation-background.ps1') `
            -Device 5A101JEBF24790 -CandidateApk $inputFile -TestApk $inputFile -ReleaseApk $inputFile `
            -Published405Apk $inputFile -Published404Apk $inputFile -BackupPath $inputFile -BackupSha256 $inputHash -ResultsRoot $heldRoot 2>&1
        $heldExitCode = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($heldExitCode -eq 0 -or (Test-Path -LiteralPath $heldRoot)) { throw 'Competing runner must fail before creating results or invoking a child.' }
    } finally { $ErrorActionPreference = 'Stop'; $heldLock.Dispose() }
    Write-Host 'Background workflow: PASS thrown failures, nonzero child exits, success, status, lock release, and mutual exclusion without ADB'
} finally {
    $env:V5_STUB_FAILURE = $originalFailure
    $resolvedTemp = [IO.Path]::GetFullPath($tempRoot)
    $allowedTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (!$resolvedTemp.StartsWith($allowedTemp, [StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing cleanup outside the temporary root.' }
    Remove-Item -LiteralPath $resolvedTemp -Recurse -Force -ErrorAction SilentlyContinue
}
exit 0
