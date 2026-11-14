param(
    [Parameter(Mandatory)][string]$Device,
    [ValidateRange(1, 4)][int]$NumShards = 1,
    [ValidateRange(0, 3)][int]$ShardIndex = 0,
    [ValidatePattern('^[A-Za-z0-9_.$#,]*$')][string]$ClassFilter = '',
    [Parameter(Mandatory)][string]$ResultsDir,
    [switch]$IncludeExternalFixtures
)

$ErrorActionPreference = 'Stop'
if ($ShardIndex -ge $NumShards) { throw 'ShardIndex must be smaller than NumShards' }
if ($IncludeExternalFixtures -and !$ClassFilter) { throw 'External fixtures require an explicit class filter and their fixture driver.' }
$serial = (& adb -s $Device shell getprop ro.serialno).Trim()
if ($LASTEXITCODE -ne 0 -or !$serial) { throw "Cannot identify device $Device" }
if ($serial -eq 'NE12442001324') { throw 'The NF22E1 is off-limits for automated tests' }
foreach ($package in @('com.u1.slicer.orca', 'com.u1.slicer.orca.test', 'androidx.test.services', 'androidx.test.orchestrator')) {
    if (!(& adb -s $Device shell pm path $package)) { throw "Install $package on $Device before running the shard" }
}

New-Item -ItemType Directory -Force $ResultsDir | Out-Null
$log = Join-Path $ResultsDir "shard-$ShardIndex-$Device.log"
if (Test-Path $log) { throw "Results already exist: $log" }
$passed = 0
$failed = 0
$skipped = 0
$className = ''
$testName = ''
$completionCode = $null

# Forward runner arguments explicitly: raw Orchestrator runs bypass Gradle's defaults.
$command = 'CLASSPATH=$(pm path androidx.test.services) app_process / androidx.test.services.shellexecutor.ShellMain am instrument -w -r' +
    ' -e targetInstrumentation com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner' +
    ' -e listener com.u1.slicer.TestWorkspaceCleanupListener' +
    $(if (!$IncludeExternalFixtures) { ' -e notAnnotation com.u1.slicer.RequiresExternalFixture' } else { '' }) +
    " -e numShards $NumShards -e shardIndex $ShardIndex" +
    $(if ($ClassFilter) { " -e class $ClassFilter" } else { '' }) +
    ' androidx.test.orchestrator/.AndroidTestOrchestrator'
Write-Host "Starting shard $ShardIndex/$NumShards on $Device"
& adb -s $Device shell $command 2>&1 | ForEach-Object {
    $line = $_.ToString()
    Add-Content $log $line
    if ($line -match '^INSTRUMENTATION_STATUS: class=(.+)$') { $className = $Matches[1] }
    elseif ($line -match '^INSTRUMENTATION_STATUS: test=(.+)$') { $testName = $Matches[1] }
    elseif ($line -eq 'INSTRUMENTATION_STATUS_CODE: 1') {
        Write-Host "$(Get-Date -Format HH:mm:ss) START $className#$testName"
    }
    elseif ($line -eq 'INSTRUMENTATION_STATUS_CODE: 0') {
        $passed++
        Write-Host "$(Get-Date -Format HH:mm:ss) PASS [$passed passed, $failed failed, $skipped skipped]"
    }
    elseif ($line -eq 'INSTRUMENTATION_STATUS_CODE: -2' -or $line -eq 'INSTRUMENTATION_STATUS_CODE: -1') {
        $failed++
        Write-Host "$(Get-Date -Format HH:mm:ss) FAIL $className#$testName"
    }
    elseif ($line -eq 'INSTRUMENTATION_STATUS_CODE: -3' -or $line -eq 'INSTRUMENTATION_STATUS_CODE: -4') {
        $skipped++
        Write-Host "$(Get-Date -Format HH:mm:ss) SKIP $className#$testName"
    }
    elseif ($line -match '^INSTRUMENTATION_CODE: (-?\d+)$') { $completionCode = [int]$Matches[1] }
}
$adbExit = $LASTEXITCODE
@{ device=$Device; shardIndex=$ShardIndex; numShards=$NumShards; passed=$passed;
    failed=$failed; skipped=$skipped; completionCode=$completionCode; adbExit=$adbExit } |
    ConvertTo-Json | Set-Content (Join-Path $ResultsDir "shard-$ShardIndex-$Device.json")
if ($adbExit -ne 0 -or $completionCode -ne -1 -or $passed -eq 0 -or $failed -gt 0 -or $skipped -gt 0) {
    throw "Shard $ShardIndex did not pass completely. See $log"
}
Write-Host "Shard $ShardIndex complete: $passed passed, no failures or skips"
