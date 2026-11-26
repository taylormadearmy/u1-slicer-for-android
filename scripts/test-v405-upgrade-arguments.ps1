param([switch]$ReproduceOldBinding)
$ErrorActionPreference = 'Stop'
$parseErrors = $null
$parseTokens = $null
$source = Join-Path $PSScriptRoot 'run-v405-upgrade-soak.ps1'
$ast = [System.Management.Automation.Language.Parser]::ParseFile($source, [ref]$parseTokens, [ref]$parseErrors)
if ($parseErrors) { throw $parseErrors }
$orchestrator = Join-Path $PSScriptRoot 'run-v5-reconciliation-regression.ps1'
$orchestratorText = Get-Content -Raw -LiteralPath $orchestrator
$v404Call = $orchestratorText.IndexOf("run-v4-upgrade-soak.ps1", [StringComparison]::Ordinal)
$v405Call = $orchestratorText.IndexOf("run-v405-upgrade-soak.ps1", [StringComparison]::Ordinal)
if ($v404Call -lt 0 -or $v405Call -le $v404Call) { throw 'Cannot find ordered v4.0.4 and v4.0.5 upgrade phases.' }
$betweenUpgradePhases = $orchestratorText.Substring($v404Call, $v405Call - $v404Call)
if ($betweenUpgradePhases -notmatch 'am force-stop com\.u1\.slicer\.orca[\s\S]*?pm clear com\.u1\.slicer\.orca') {
    throw 'The v4.0.5 schema-9 seed must start after clearing the previous candidate schema-10 fixture.'
}
$wrapper = $ast.Find({ param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-CheckedAdb'
}, $true)
$commands = @($ast.FindAll({ param($node)
    $node -is [System.Management.Automation.Language.CommandAst] -and
    $node.GetCommandName() -eq 'Invoke-CheckedAdb'
}, $true))
if (!$wrapper -or $commands.Count -ne 5) { throw 'Cannot find the actual ADB wrapper and five workflow commands.' }

# Execute the real wrapper and command with in-process stubs. No ADB or files are touched.
$Device = '5A101JEBF24790'
$PublishedApk = 'published405.apk'
$CandidateApk = 'candidate500.apk'
$TestApk = 'instrumentation.apk'
$script:v405CapturedArguments = @()
function adb {
    $script:v405CapturedArguments = @($args)
    $global:LASTEXITCODE = 0
}
function Resolve-Path {
    param($LiteralPath)
    [pscustomobject]@{ Path = $LiteralPath }
}
. ([scriptblock]::Create($wrapper.Extent.Text))
if ($ReproduceOldBinding) {
    $commandTexts = @('Invoke-CheckedAdb install -r -d (Resolve-Path -LiteralPath $PublishedApk).Path')
} else { $commandTexts = @($commands | ForEach-Object { $_.Extent.Text }) }
$expectedCommands = @(
    @('-s', $Device, 'install', '-r', '-d', $PublishedApk),
    @('-s', $Device, 'install', '-r', $TestApk),
    @('-s', $Device, 'install', '-r', $CandidateApk),
    @('-s', $Device, 'shell', 'am', 'start', '-W', '-n', 'com.u1.slicer.orca/com.u1.slicer.MainActivity'),
    @('-s', $Device, 'install', '-r', $CandidateApk)
)
for ($index = 0; $index -lt $commandTexts.Count; $index++) {
    & ([scriptblock]::Create($commandTexts[$index]))
    $expected = $expectedCommands[$index]
    if (($expected -join "`0") -cne ($script:v405CapturedArguments -join "`0")) {
        $expectedJson = ConvertTo-Json -InputObject $expected -Compress
        $actualJson = ConvertTo-Json -InputObject $script:v405CapturedArguments -Compress
        throw "Downgrade command differs: command $index expected $expectedJson, actual $actualJson"
    }
}
Write-Host 'Published 405 workflow argument capture: PASS; all five commands preserve every flag.'
