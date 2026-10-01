<#
YunX Desktop 打包：把 app-image 变成两个交付物
  1) 便携版 —— 7z 自解压（SFX + LZMA2 solid）：单个 exe，双击选目录解压，Win10 用户不需要装 7-Zip
  2) 安装版 —— Inno Setup 6 + LZMA2 solid：按用户装到 %LOCALAPPDATA%、开始菜单、可从「应用和功能」卸载
为什么不用 jpackage 的 exe：它内部是 WiX/MSI 的 mszip（比 deflate 还弱）。同样内容 LZMA 实测小 17%。
用法（本地与 CI 共用）：
  pwsh packaging/build-package.ps1 -Version 0.4.8 -AppImage <app-image 目录> -OutDir <输出目录>
#>
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$AppImage,
    [Parameter(Mandatory = $true)][string]$OutDir,
    [string]$SevenZip = '',
    [string]$Iscc = '',
    [string]$SignJdk = '',
    [switch]$SkipPortable,
    [switch]$SkipInstaller
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$appImage = (Resolve-Path $AppImage).Path
if (-not (Test-Path $appImage)) { throw "app-image 不存在: $appImage" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
# 输出目录必须绝对化：Inno 的 OutputDir 用相对路径时是相对 .iss 所在目录解析，而脚本按当前
# 工作目录建目录，两者会分叉（CI 传 "dist" 时安装包曾落到 packaging/inno/dist/，Release 缺附件）。
$OutDir = (Get-Item -LiteralPath $OutDir).FullName
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("yunx-pack-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

function Step($m) { Write-Host "=== $m ===" }
function FirstExisting($paths) {
    foreach ($p in $paths) { if ($p -and (Test-Path $p)) { return $p } }
    return ''
}

if (-not $SevenZip) {
    $SevenZip = FirstExisting @("$env:ProgramFiles\7-Zip\7z.exe", "${env:ProgramFiles(x86)}\7-Zip\7z.exe")
}
if (-not $Iscc) {
    $Iscc = FirstExisting @(
        "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe",
        "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
        "$env:ProgramFiles\Inno Setup 6\ISCC.exe"
    )
}

try {
    # ---------- 1. 注入文档 ----------
    Step '1/4 注入文档（README/LICENSE/THIRD_PARTY_NOTICES/SOURCE）'
    Copy-Item (Join-Path $repoRoot 'packaging\README.txt') (Join-Path $appImage 'README.txt') -Force
    Copy-Item (Join-Path $repoRoot 'LICENSE') (Join-Path $appImage 'LICENSE.txt') -Force
    Copy-Item (Join-Path $repoRoot 'THIRD_PARTY_NOTICES.md') (Join-Path $appImage 'THIRD_PARTY_NOTICES.md') -Force
    $src = Get-Content (Join-Path $repoRoot 'packaging\SOURCE.txt') -Raw -Encoding UTF8
    Set-Content (Join-Path $appImage 'SOURCE.txt') ($src.Replace('<VERSION>', $Version)) -Encoding UTF8

    # ---------- 2. 厂商签名启动器 + Start-YunX.bat（智能应用控制绕过方案） ----------
    Step '2/4 厂商签名启动器 + Start-YunX.bat'
    if (-not $SignJdk) {
        $javac = (Get-Command java.exe -ErrorAction SilentlyContinue).Source
        if ($javac) { $SignJdk = Split-Path -Parent $javac }
    }
    if ($SignJdk -and (Test-Path (Join-Path $SignJdk 'java.exe'))) {
        Copy-Item (Join-Path $SignJdk 'java.exe') (Join-Path $appImage 'runtime\bin\java.exe') -Force
        Copy-Item (Join-Path $SignJdk 'javaw.exe') (Join-Path $appImage 'runtime\bin\javaw.exe') -Force
    } else {
        Write-Warning '未找到签名 JDK，跳过启动器注入（Start-YunX.bat 将不可用）'
    }
    Copy-Item (Join-Path $repoRoot 'packaging\Start-YunX.bat') (Join-Path $appImage 'Start-YunX.bat') -Force

    # ---------- 3. 便携版：7z 自解压 ----------
    if (-not $SkipPortable) {
        Step '3/4 便携版（7z SFX + LZMA2 solid）'
        if (-not $SevenZip) { throw '需要 7z.exe（winget install 7zip.7zip）' }
        $payload = Join-Path $tmp 'payload.7z'
        $parent = Split-Path -Parent $appImage
        $leaf = Split-Path -Leaf $appImage
        & $SevenZip a -t7z -m0=lzma2 -mx=9 -ms=on -mmt=on $payload (Join-Path $parent $leaf) | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "7z 打包失败: $LASTEXITCODE" }
        $sfx = Join-Path (Split-Path -Parent $SevenZip) '7z.sfx'
        if (-not (Test-Path $sfx)) { throw "缺少 7z.sfx: $sfx" }
        $portable = Join-Path $OutDir "YunX-Desktop-$Version-win64-portable.exe"
        Remove-Item $portable -Force -ErrorAction SilentlyContinue
        # 二进制拼接：SFX 头 + 7z 数据 = 自解压 exe
        $out = [System.IO.File]::Create($portable)
        try {
            foreach ($part in @($sfx, $payload)) {
                $bytes = [System.IO.File]::ReadAllBytes($part)
                $out.Write($bytes, 0, $bytes.Length)
            }
        } finally { $out.Dispose() }
        Write-Host ("  便携版: {0:N2} MB" -f ((Get-Item $portable).Length / 1MB))
    }

    # ---------- 4. 安装版：Inno Setup ----------
    if (-not $SkipInstaller) {
        Step '4/4 安装版（Inno Setup + LZMA2 solid）'
        if (-not $Iscc) { throw '需要 ISCC.exe（winget install JRSoftware.InnoSetup）' }
        # 简体中文向导语言文件不是 Inno 自带的；缺失时自动补（CI 上没有预置）
        $islDir = Join-Path (Split-Path -Parent $Iscc) 'Languages'
        $isl = Join-Path $islDir 'ChineseSimplified.isl'
        if (-not (Test-Path $isl)) {
            $url = 'https://raw.githubusercontent.com/kira-96/Inno-Setup-Chinese-Simplified-Translation/master/ChineseSimplified.isl'
            try {
                New-Item -ItemType Directory -Force -Path $islDir | Out-Null
                Invoke-WebRequest -Uri $url -OutFile $isl -TimeoutSec 60 -UseBasicParsing
                Write-Host "  已补齐中文向导语言文件: $isl"
            } catch {
                Write-Warning "未能获取 ChineseSimplified.isl，安装向导将使用英文（不影响功能）: $($_.Exception.Message)"
            }
        }
        $iss = Join-Path $repoRoot 'packaging\inno\YunX-Desktop.iss'
        $icon = Join-Path $repoRoot 'packaging\icon.ico'
        & $Iscc "/DAppVersion=$Version" "/DAppImage=$appImage" "/DOutDir=$OutDir" "/DIconFile=$icon" $iss |
            Select-String -Pattern 'Successful|Error|Warning' | ForEach-Object { '  ' + $_.Line.Trim() }
        if ($LASTEXITCODE -ne 0) { throw "ISCC 失败: $LASTEXITCODE" }
        $produced = Join-Path $OutDir "YunX-Desktop-$Version-win64-installer.exe"
        if (-not (Test-Path $produced)) { throw "ISCC 未在 $OutDir 生成安装包" }
    }

    # ---------- 校验和 ----------
    $files = Get-ChildItem $OutDir -File | Where-Object { $_.Name -like "*$Version*" } | Sort-Object Name
    $lines = $files | ForEach-Object { (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower() + '  ' + $_.Name }
    $lines | Set-Content (Join-Path $OutDir 'SHA256.txt') -Encoding ascii
    Write-Host '--- 交付物 ---'
    $lines | ForEach-Object { Write-Host ('  ' + $_) }
} finally {
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}
