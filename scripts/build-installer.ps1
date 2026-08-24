<#
.SYNOPSIS
    TraceGuard 安装包构建脚本：聚合产物 + 便携运行时，生成标准 exe 安装包（Inno Setup）。

.DESCRIPTION
    流程：
      1) 校验后端 jar / 前端 dist / init.sql 构建产物；
      2) 组装 installer-staging\（backend/ frontend/ runtime占位 / install.ps1 / install.bat）；
      3) 若本机已安装 Inno Setup（iscc.exe），自动编译 scripts\TraceGuard.iss -> installer-out\TraceGuard-Setup-<ver>.exe；
      4) 未安装 IS 时，仅输出可 zip 分发的 installer-staging\，并提示安装 IS 的方式。

    运行时二进制（JRE/MySQL/nginx）不预下载进包，由 install.ps1 在目标机首次安装时按需拉取便携版（带离线兜底），
    以保持安装包体积可控；如需离线全量包，可将对应 zip 预置 installer-staging\runtime\ 对应目录。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\build-installer.ps1
#>
param(
    [string]$Version = ''
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$BackendDir = Join-Path $Root 'backend'
$FrontendDir = Join-Path $Root 'frontend'
$TargetDir = Join-Path $BackendDir 'target'
$DistDir = Join-Path $FrontendDir 'dist'
$InitSql = Join-Path $BackendDir 'src\main\resources\sql\init.sql'
$Staging = Join-Path $Root 'installer-staging'

function Write-Step($m) { Write-Host "`n===== $m =====" -ForegroundColor Cyan }
function Write-Ok($m)   { Write-Host "  [OK] $m" -ForegroundColor Green }
function Write-Warn($m) { Write-Host "  [!] $m" -ForegroundColor Yellow }
function Write-Err($m)  { Write-Host "  [X] $m" -ForegroundColor Red }

# OPS-09：从 pom.xml 读取 <version>（单一版本来源，避免与后端 jar 版本漂移）
function Get-PomVersion($pom) {
    try {
        $xml = New-Object System.Xml.XmlDocument
        $xml.Load($pom)
        $ns = New-Object System.Xml.XmlNamespaceManager $xml.NameTable
        $ns.AddNamespace('m', 'http://maven.apache.org/POM/4.0.0')
        $node = $xml.SelectSingleNode('/m:project/m:version', $ns)
        if ($node) { return $node.InnerText.Trim() }
    } catch { }
    return $null
}

# ---------- 1. 校验产物 ----------
Write-Step '1/4 校验构建产物'
$jar = Get-ChildItem -Path $TargetDir -Filter 'traceguard-backend-*.jar' -ErrorAction SilentlyContinue |
    Where-Object { -not $_.Name.EndsWith('.jar.original') } | Select-Object -First 1
if (-not $jar) { Write-Err "未找到后端 jar（$TargetDir），请先 deploy-windows.ps1 构建"; exit 1 }
if (-not (Test-Path $DistDir)) { Write-Err "未找到前端 dist（$DistDir），请先 npm run build"; exit 1 }
if (-not (Test-Path $InitSql)) { Write-Err "未找到 init.sql"; exit 1 }
if ($Version -eq '') {
    if ($jar.Name -match 'traceguard-backend-(.+)\.jar') { $Version = $Matches[1] }
    else { $Version = Get-PomVersion (Join-Path $BackendDir 'pom.xml') }
    if (-not $Version) { $Version = '1.0.0' }
}
Write-Ok "产物就绪：$($jar.Name) + frontend/dist + init.sql"
Write-Ok "打包版本：$Version"

# ---------- 2. 组装 staging ----------
Write-Step '2/4 组装 installer-staging'
if (Test-Path $Staging) {
    # 若旧 staging 被占用（如 install.bat 仍在运行），先尝试关闭相关进程再删除
    # OPS-09：Get-Process 的 CommandLine 属性恒为 null（需权限/非本进程），改用 CIM 查询命令行匹配
    try {
        Get-CimInstance Win32_Process -Filter "Name='powershell.exe' or Name='cmd.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -like '*TraceGuard*' -or $_.CommandLine -like '*installer-staging*' } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    } catch { }
    Start-Sleep -Milliseconds 500
    try { Remove-Item -Recurse -Force $Staging -ErrorAction Stop }
    catch {
        Write-Err "无法删除旧的 installer-staging（可能正被安装向导占用）。请先关闭所有相关窗口，再重跑本脚本。"
        exit 1
    }
}
$appOut = Join-Path $Staging 'backend\app'; New-Item -ItemType Directory -Force -Path $appOut | Out-Null
$feOut = Join-Path $Staging 'frontend'; New-Item -ItemType Directory -Force -Path $feOut | Out-Null
$runtimeOut = Join-Path $Staging 'runtime'; New-Item -ItemType Directory -Force -Path $runtimeOut | Out-Null

Copy-Item $jar.FullName -Destination (Join-Path $appOut $jar.Name) -Force
Copy-Item $InitSql -Destination (Join-Path $Staging 'backend\init.sql') -Force
Copy-Item $DistDir -Destination $feOut -Recurse -Force
$nginxConf = Join-Path $FrontendDir 'nginx-windows.conf'
if (Test-Path $nginxConf) { Copy-Item $nginxConf -Destination (Join-Path $feOut 'nginx-windows.conf') -Force }
Copy-Item (Join-Path $Root 'scripts\install.ps1') -Destination $Staging -Force
Copy-Item (Join-Path $Root 'scripts\install.bat') -Destination $Staging -Force

# 放置说明：离线全量包可将运行时 zip 预置到 runtime\
@"
TraceGuard 安装包（staging）
===========================
本目录为安装包暂存区。最终用户有两种使用方式：

方式一（推荐，联网安装）：
  双击 install.bat -> 跟随向导。首次安装时 install.ps1 会自动从官方源
  拉取便携 JRE 21 / MySQL 8.0 / nginx 到 runtime\，初始化数据库并生成启动器。

方式二（离线全量包）：
  将以下 zip 解压后的内容放入对应 runtime 子目录，可跳过下载：
    runtime\jre\     <- Adoptium Temurin JRE 21 (windows x64 zip)
    runtime\mysql\   <- MySQL 8.0 winx64 zip
    runtime\nginx\   <- nginx 1.26.x windows zip

生成 exe 安装包：在本机安装 Inno Setup 6+ 后执行
  iscc.exe scripts\TraceGuard.iss
产物位于 installer-out\TraceGuard-Setup-$Version.exe
"@ | Set-Content -Path (Join-Path $Staging 'README.txt') -Encoding UTF8
Write-Ok "staging 已生成：$Staging"

# ---------- 3. 调用 Inno Setup ----------
Write-Step '3/4 编译 exe 安装包（Inno Setup）'
$iscc = $null
try { $iscc = (& where.exe iscc.exe 2>$null | Select-Object -First 1) } catch {}
if (-not $iscc) {
    $candidates = @(
        "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
        "${env:ProgramFiles}\Inno Setup 6\ISCC.exe",
        "${env:ProgramFiles(x86)}\Inno Setup 7\ISCC.exe",
        "${env:ProgramFiles}\Inno Setup 7\ISCC.exe",
        "D:\Develop\Inno Setup 7\ISCC.exe"
    )
    foreach ($c in $candidates) { if (Test-Path $c) { $iscc = $c; break } }
}
if ($iscc) {
    Write-Ok "检测到 Inno Setup：$iscc"
    # OPS-09：经 /D 把版本号传入 TraceGuard.iss（AppVersion/OutputBaseFilename 与后端版本保持一致）
    & $iscc "/DMyAppVersion=$Version" (Join-Path $Root 'scripts\TraceGuard.iss')
    if ($LASTEXITCODE -eq 0) {
        $exe = Join-Path $Root "installer-out\TraceGuard-Setup-$Version.exe"
        if (Test-Path $exe) { Write-Ok "安装包已生成：$exe" }
        else { Write-Err 'Inno Setup 编译成功但未找到安装包产物'; exit 1 }
    } else {
        Write-Err "Inno Setup 编译失败（退出码 $LASTEXITCODE）"
        exit 1
    }
} else {
    Write-Warn '未检测到 Inno Setup，跳过 exe 生成。'
    Write-Host '  安装 IS：https://jrsoftware.org/isdl.php （或 winget install JRSoftware.InnoSetup）' -ForegroundColor Gray
    Write-Host '  装后执行：iscc.exe scripts\TraceGuard.iss' -ForegroundColor Gray
}

# ---------- 4. 收尾 ----------
Write-Step '4/4 完成'
Write-Host "`n可分发内容：" -ForegroundColor Green
Write-Host "  - installer-staging\   —— 可直接 zip 分发，目标机双击 install.bat" -ForegroundColor Gray
Write-Host "  - installer-out\       —— 若已装 IS，含 TraceGuard-Setup-$Version.exe 向导安装包" -ForegroundColor Gray
Write-Host "`n默认账号 admin / admin123（首次登录请修改密码）。" -ForegroundColor Yellow
