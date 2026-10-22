param([string]$BatchRevision = '')
$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot 'run-batch-manual-e2e.ps1'
$tokens = $null
$errors = $null
$content = if ($BatchRevision) {
    (& git show "${BatchRevision}:scripts/run-batch-manual-e2e.ps1") -join "`n"
} else {
    Get-Content -LiteralPath $source -Raw
}
$ast = [Management.Automation.Language.Parser]::ParseInput($content, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Batch script does not parse.' }
foreach ($name in @('Get-ScenarioPid', 'Test-ScenarioProcess', 'Start-ScenarioApp', 'Wait-State', 'Wait-LogPattern', 'Run-Scenario')) {
    $definition = $ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }, $true)
    if ($definition) { . ([scriptblock]::Create($definition.Extent.Text)) }
}
function Dump-State { return @('=== DUMP_STATE ===', 'State: SliceComplete', 'EXPORT_GCODE: success path=stub.gcode') }
function Invoke-Adb { if ($args -contains 'pidof') { return $script:StubPid } }
function Write-Step { param($Message) }
function Start-Sleep { param($Seconds, $Milliseconds) }
$AppId = 'com.u1.slicer.orca'
$script:ScenarioAppPid = '1234'
foreach ($script:StubPid in @('5678', '')) {
    foreach ($wait in @('Wait-State', 'Wait-LogPattern')) {
        $result = & $wait -Pattern 'State: SliceComplete|EXPORT_GCODE: success' -TimeoutSeconds 1 -Label 'stub'
        if ($result -notmatch '^PROCESS_CHANGED:' -or $result -match 'SliceComplete|EXPORT_GCODE: success') {
            throw 'Restarted or missing app must fail before accepting stale success logs.'
        }
    }
}
$script:StubPid = '1234'
$result = Wait-State -Pattern 'State: SliceComplete' -TimeoutSeconds 1 -Label 'stub'
if ($result -notmatch 'State: SliceComplete' -or $result -match 'PROCESS_CHANGED:') {
    throw 'Stable app process must retain the success result.'
}
foreach ($wait in @('Wait-State', 'Wait-LogPattern')) {
    $result = & $wait -Pattern 'SliceComplete|EXPORT_GCODE: success' -TimeoutSeconds 1 -Label 'stub'
    if ($result -notmatch 'SliceComplete|EXPORT_GCODE: success' -or $result -match 'PROCESS_CHANGED:') { throw 'Stable process must pass both wait paths.' }
    $timeout = & $wait -Pattern 'SliceComplete|EXPORT_GCODE: success' -TimeoutSeconds 0 -Label 'stub'
    if ($timeout -notmatch '^TIMEOUT:' -or $timeout -match 'SliceComplete|EXPORT_GCODE: success') { throw 'Deadline expiry must fail rather than accept stale final-dump success.' }
}
function Test-Path { return $true }
function Set-Content { param($Path, [Parameter(ValueFromPipeline)]$Value) process { $script:ScenarioFailure = $Value } }
function Broadcast { throw 'POST_STARTUP_SENTINEL' }
function Get-SafeName { return 'stub' }
$ResultsDir = $PSScriptRoot
$ArtifactDir = $PSScriptRoot
$AssetsDir = $PSScriptRoot
$scenario = @{ Name='stub'; File='stub.stl'; Suite='U1' }
$script:StubPid = ''
$script:ScenarioAppPid = 'stale'
if (Run-Scenario $scenario 1 1) { throw 'Scenario must fail when no process exists after launch/reopen.' }
if ($script:ScenarioFailure -notmatch '(?s)OVERALL: FAIL.*app process missing') { throw 'Missing process failure was not recorded.' }
$script:StubPid = '1234'
$script:ScenarioAppPid = $null
try { Run-Scenario $scenario 1 1; throw 'Expected sentinel after startup.' } catch {
    if ($_.Exception.Message -ne 'POST_STARTUP_SENTINEL' -or $script:ScenarioAppPid -ne '1234') { throw }
}
Write-Host 'Batch E2E process continuity: PASS both wait paths and actual scenario startup wiring without ADB'
