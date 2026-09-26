# YunX Desktop - mirror this source tree into your git work tree.
#
# Why: publishing a partially updated tree (new build files + stale sources that were
# deleted upstream) breaks CI - e.g. the removed JavaFX WebLogin.kt. This script mirrors
# the tree and DELETES files that no longer exist here (robocopy /MIR).
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools/mirror-to-git.ps1 -GitWorkTree "D:\src\YunX-Desktop"
#   powershell -ExecutionPolicy Bypass -File tools/mirror-to-git.ps1 -GitWorkTree "D:\src\YunX-Desktop" -DryRun
#
# Notes:
#   - .git, build, .gradle, .kotlin, .idea are excluded and never deleted.
#   - Any other file you added inside the work tree but not present here WILL be removed,
#     so keep custom files here (in the source tree) instead.
#   - ASCII-only on purpose: Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI.
param(
    [Parameter(Mandatory = $true)][string]$GitWorkTree,
    [string]$Source,
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'

# Default source = repository root that contains this script.
# Computed in the body because $PSScriptRoot can be empty in param defaults.
if ([string]::IsNullOrWhiteSpace($Source)) {
    if ([string]::IsNullOrWhiteSpace($PSScriptRoot)) { $Source = (Get-Location).Path }
    else { $Source = (Split-Path -Parent $PSScriptRoot) }
}
$Source = (Resolve-Path $Source).Path
$work = (Resolve-Path $GitWorkTree).Path

if (-not (Test-Path (Join-Path $work '.git'))) {
    Write-Warning "No .git found in $work - continuing anyway (files will still be mirrored)."
}

Write-Host "Source : $Source"
Write-Host "Target : $work"

$rcArgs = @($Source, $work, '/MIR', '/XD', '.git', 'build', '.gradle', '.kotlin', '.idea', '/XF', '*.log')
if ($DryRun) { $rcArgs += '/L' }
robocopy @rcArgs /NJH /NJS /NFL /NDL /NP /R:1 /W:1
$code = $LASTEXITCODE
Write-Host "robocopy exit: $code (0-7 = success)"

if ($DryRun) {
    Write-Host "Dry run only - nothing was changed."
} else {
    Write-Host ""
    Write-Host "Next steps inside the work tree:"
    Write-Host "  git status"
    Write-Host "  git add -A"
    Write-Host '  git commit -m "sync sources with upstream tree (removes stale files)"'
    Write-Host "  git push"
}
