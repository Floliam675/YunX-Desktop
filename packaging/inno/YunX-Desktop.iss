; YunX Desktop 安装脚本（Inno Setup 6）—— 替代 jpackage 的 WiX/MSI
; 用 LZMA2 solid 压缩，安装体验与之前一致：按用户装到 %LOCALAPPDATA%、开始菜单、可从「应用和功能」卸载
; 由 packaging/build-package.ps1 调用：/DAppVersion=.. /DAppImage=.. /DOutDir=.. /DIcon=..

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef AppImage
  #define AppImage "."
#endif
#ifndef OutDir
  #define OutDir "."
#endif
#ifndef IconFile
  #define IconFile "icon.ico"
#endif

#define AppName "YunX Desktop"
#define AppExe "YunX Desktop.exe"
#define AppPublisher "Floliam675 (github.com/Floliam675/YunX-Desktop)"
#define AppId "9f1c7a52-6f4b-4e2a-9c31-8b0d5e7a2f64"

[Setup]
AppId={{9f1c7a52-6f4b-4e2a-9c31-8b0d5e7a2f64}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher={#AppPublisher}
AppCopyright=Copyright (C) 2026 Floliam675 - AGPL-3.0 - upstream CYQawa/YunX
DefaultDirName={localappdata}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
DisableDirPage=no
PrivilegesRequired=lowest
AllowNoIcons=yes
OutputDir={#OutDir}
OutputBaseFilename=YunX-Desktop-{#AppVersion}-win64-installer
SetupIconFile={#IconFile}
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}
Compression=lzma2/max
SolidCompression=yes
LZMANumBlockThreads=4
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=yes
RestartApplications=no
MinVersion=10.0
; 安装包本身不做数字签名（与之前一致，SmartScreen / 智能应用控制 的处理见包内 README）
VersionInfoDescription=YunX Desktop - network drive share parser and downloader (AGPL-3.0)
VersionInfoCompany={#AppPublisher}
VersionInfoProductName={#AppName}
VersionInfoProductVersion={#AppVersion}

[Languages]
; 简体中文语言文件不是 Inno 自带的（来自社区翻译仓库），缺失时自动降级为英文向导，
; 避免在没预置该文件的构建机（如 CI）上编译失败。build-package.ps1 会尝试自动下载补齐。
#if FileExists(AddBackslash(CompilerPath) + "Languages\ChineseSimplified.isl")
Name: "chinese"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"
#endif
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#AppImage}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"; WorkingDir: "{app}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; Description: "{cm:LaunchProgram,{#StringChange(AppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent
