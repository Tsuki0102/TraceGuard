; TraceGuard Inno Setup 安装脚本（生成标准 Windows 向导式 exe 安装包）
; 用法（需已安装 Inno Setup 6+）：
;   iscc.exe scripts\TraceGuard.iss
; 前置：先用 build-installer.ps1 生成 installer-staging\ 目录（含 backend/ frontend/ runtime/ install.ps1 等）
; 产物：installer-out\TraceGuard-Setup-<ver>.exe
; OPS-09：版本号由 build-installer.ps1 经 /DMyAppVersion=<ver> 传入（与后端 jar/pom 版本保持一致）；
;         手动编译时可用 iscc.exe /DMyAppVersion=1.0.0 scripts\TraceGuard.iss

#define MyAppName "TraceGuard"
#ifndef MyAppVersion
  #define MyAppVersion "1.0.0"
#endif
#define MyAppPublisher "TraceGuard Team"
; OPS-09：项目尚未公开托管前保留占位；发布到 GitHub 后请替换为真实仓库地址
#define MyAppURL "https://github.com/TraceGuardProject/TraceGuard"
#define MyStaging "..\installer-staging"

[Setup]
AppId={{A1B2C3D4-E5F6-7890-ABCD-1234567890AB}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
OutputDir=installer-out
OutputBaseFilename={#MyAppName}-Setup-{#MyAppVersion}
; OPS-09：安装包图标——项目暂无 .ico 素材时留空使用 Inno 默认图标；
;         如需自定义图标，将图标文件放入 scripts\assets\ 并将下方改为 SetupIconFile=assets\traceguard.ico
SetupIconFile=
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayName={#MyAppName}
UninstallDisplayIcon={app}\start-all.bat
CreateUninstallRegKey=yes
; OPS-09：安装包版本与发布页展示统一由 /DMyAppVersion 控制（build-installer.ps1 自动传入）
VersionInfoVersion={#MyAppVersion}.0

[Languages]
Name: "Chinese"; MessagesFile: "compiler:Default.isl"
; 如需英文可加：Name: "English"; MessagesFile: "compiler:English.isl"

[Files]
; 递归打包 installer-staging 下全部内容（含 backend/app jar、frontend/dist、runtime 便携运行时、install.ps1）
Source: "{#MyStaging}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\start-all.bat"; WorkingDir: "{app}"
Name: "{group}\卸载 {#MyAppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\start-all.bat"; WorkingDir: "{app}"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加任务"; Flags: unchecked

[Run]
; 安装完成后启动一键初始化向导（探测/拉取运行时、建库建表、生成启动器、创建快捷方式）
Filename: "powershell.exe"; Parameters: "-ExecutionPolicy Bypass -NoProfile -File ""{app}\install.ps1"""; Description: "运行 TraceGuard 安装向导"; Flags: postinstall nowait runascurrentuser

; OPS-09：卸载时清理安装器注册的 Windows 服务，避免卸载后残留系统服务
[UninstallRun]
Filename: "powershell.exe"; Parameters: "-ExecutionPolicy Bypass -NoProfile -Command ""& '{app}\runtime\nssm.exe' remove TraceGuardBackend confirm 2>$null; Stop-Service -Name 'TraceGuardBackend' -Force -ErrorAction SilentlyContinue; Remove-Service -Name 'TraceGuardBackend' -ErrorAction SilentlyContinue; Stop-Service -Name 'MySQLTraceGuard' -Force -ErrorAction SilentlyContinue; Remove-Service -Name 'MySQLTraceGuard' -ErrorAction SilentlyContinue; if (Test-Path '{app}\frontend\nginx.exe') { & '{app}\frontend\nginx.exe' -s stop 2>$null }; if (Test-Path '{app}\runtime\nginx\nginx.exe') { & '{app}\runtime\nginx\nginx.exe' -s stop 2>$null }"""; Flags: runhidden; StatusMsg: "清理 TraceGuard 服务与进程..."

[UninstallDelete]
Type: filesandordirs; Name: "{app}\runtime"
Type: filesandordirs; Name: "{app}\backend"
Type: filesandordirs; Name: "{app}\frontend"
