param(
    [int]$Passed,
    [int]$Failed,
    [string]$Summary
)

$stamp = Get-Date -Format "HH:mm:ss"
Write-Host "[$stamp] Batch complete: passed=$Passed failed=$Failed summary=$Summary"
if ($Failed -gt 0) {
    exit 1
}
exit 0
