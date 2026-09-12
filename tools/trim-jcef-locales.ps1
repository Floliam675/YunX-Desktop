# YunX Desktop - optional slimming script: trim JCEF (Chromium) locale packs.
# Keeps only the given locales (default zh-CN + en-US). ASCII-only on purpose:
# Windows PowerShell 5.1 reads BOM-less .ps1 files as ANSI, which breaks non-ASCII text.
#
# Usage (after building the app-image):
#   gradlew :desktop:createDistributable
#   powershell -ExecutionPolicy Bypass -File tools/trim-jcef-locales.ps1 -AppImageDir "desktop\build\compose\binaries\main\app\YunX Desktop"
#
# Effect: jcef natives jar ~134 MB -> ~123 MB (Chromium locales 55 -> 2).
param(
    [Parameter(Mandatory = $true)][string]$AppImageDir,
    [string[]]$Keep = @('zh-CN.pak', 'en-US.pak')
)
$ErrorActionPreference = 'Stop'
$appDir = (Resolve-Path $AppImageDir).Path
$jar = Get-ChildItem (Join-Path $appDir 'app') -Filter 'jcef-natives*.jar' | Select-Object -First 1
if (-not $jar) { throw "jcef-natives*.jar not found under: $appDir" }
$origLen = $jar.Length
Write-Host ("Target: {0}  ({1} MB)" -f $jar.Name, [math]::Round($origLen / 1MB, 1))

$work = Join-Path ([System.IO.Path]::GetTempPath()) ("jceftrim_" + [Guid]::NewGuid().ToString('N'))
$workJar = Join-Path $work 'natives.jar'
New-Item -ItemType Directory -Path $work -Force | Out-Null
try {
    Copy-Item $jar.FullName $workJar -Force
    Push-Location $work
    & jar xf natives.jar
    $tgz = Get-ChildItem $work -Filter *.tar.gz | Select-Object -First 1
    if (-not $tgz) { throw 'no .tar.gz inside natives jar' }
    $ext = Join-Path $work 'ext'
    New-Item -ItemType Directory -Path $ext -Force | Out-Null
    & tar.exe -xzf $tgz.FullName -C $ext
    $loc = Join-Path $ext 'locales'
    $before = (Get-ChildItem $loc -File).Count
    Get-ChildItem $loc -File | Where-Object { $_.Name -notin $Keep } | Remove-Item -Force
    Remove-Item $tgz.FullName -Force
    & tar.exe -czf $tgz.Name -C $ext .
    & jar uf natives.jar $tgz.Name
    Pop-Location
    $newLen = (Get-Item $workJar).Length
    if ($newLen -ge $origLen) { throw ("trim did not shrink the jar ({0} MB), aborting" -f [math]::Round($newLen / 1MB, 1)) }
    Copy-Item $workJar $jar.FullName -Force
    Write-Host ("locales {0} -> {1}; jar {2} MB -> {3} MB" -f $before, $Keep.Count, [math]::Round($origLen / 1MB, 1), [math]::Round((Get-Item $jar.FullName).Length / 1MB, 1))
} finally {
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}
