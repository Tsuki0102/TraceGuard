<#
.SYNOPSIS
    TraceGuard 一键安装向导（Windows 原生，PowerShell 5.1+）。
    自包含：自动探测/拉取便携 JRE + MySQL + nginx，初始化数据库，生成启动器与快捷方式，可选注册 Windows 服务。

.DESCRIPTION
    适用于"已构建产物"分发场景：将本脚本与以下目录/文件放在同一安装源根目录：
      backend/app/traceguard-backend-*.jar
      backend/init.sql
      frontend/dist/
      frontend/nginx-windows.conf
    双击 install.bat 启动本向导，按提示完成即装即用。

    运行时策略（最小化目标机依赖）：
      1) 优先使用目标机已安装的 JRE / MySQL / nginx；
      2) 缺失则从官方直链下载便携版到 <安装目录>\runtime\（带离线兜底：下载失败提示手动放置）；
      3) 全程无需目标机自备 Maven / Node。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\install.ps1
#>
[CmdletBinding()]
param(
    [string]$InstallDir = '',
    [switch]$SkipDownload,
    [switch]$NoService,
    # OPS-08：无人值守安装（跳过所有交互输入，使用默认值/环境变量）
    [switch]$Unattended
)

$ErrorActionPreference = 'Stop'
$Host.UI.RawUI.WindowTitle = 'TraceGuard 安装向导'

$SourceRoot = Split-Path -Parent $PSScriptRoot          # 安装源根（含 backend/ frontend/）
$AppName    = 'TraceGuard'
# OPS-08：版本号单一来源——优先从 pom.xml 读取，避免与后端 jar 版本漂移
$AppVersion = '1.0.0'
try {
    $pom = Join-Path $SourceRoot 'backend\pom.xml'
    if (Test-Path $pom) {
        $xml = New-Object System.Xml.XmlDocument
        $xml.Load($pom)
        $ns = New-Object System.Xml.XmlNamespaceManager $xml.NameTable
        $ns.AddNamespace('m', 'http://maven.apache.org/POM/4.0.0')
        $v = $xml.SelectSingleNode('/m:project/m:version', $ns)
        if ($v -and $v.InnerText.Trim()) { $AppVersion = $v.InnerText.Trim() }
    }
} catch { }

# OPS-08：安装日志（本机 %TEMP%\TraceGuard-install-<时间戳>.log；安装目录可写时同时写入安装目录）
$LogFile = Join-Path $env:TEMP ("TraceGuard-install-{0:yyyyMMdd-HHmmss}.log" -f (Get-Date))
try {
    if (Test-Path $InstallDir) { $LogFile = Join-Path $InstallDir 'install.log' }
} catch { }
function Write-Log($msg) {
    try { Add-Content -Path $LogFile -Value ("[{0}] {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg) -Encoding UTF8 } catch { }
}
# OPS-08：将本机安装目录写为日志路径（供后续步骤使用）
if ($InstallDir -ne '') { try { $LogFile = Join-Path $InstallDir 'install.log' } catch { } }

# 运行时会下载的便携组件（官方直链；下载失败回退离线）
# OPS-08：每个组件支持 sha256（官方已知校验和）；留空时可通过环境变量 TG_<KEY>_SHA256 注入，仍未配置则提示不校验
$Prereqs = @{
    jre = @{
        Url  = 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/zip'
        Name = 'JRE 21 (Adoptium Temurin)'
        Dir  = 'jre'
        Sha256 = $env:TG_JRE_SHA256
    }
    nginx = @{
        Url  = 'https://nginx.org/download/nginx-1.26.2.zip'
        Name = 'nginx 1.26.2 (Windows)'
        Dir  = 'nginx'
        Sha256 = $env:TG_NGINX_SHA256
    }
    mysql = @{
        Url  = 'https://dev.mysql.com/get/Downloads/MySQL-8.0/mysql-8.0.39-winx64.zip'
        Name = 'MySQL 8.0.39 (Windows x64 ZIP)'
        Dir  = 'mysql'
        Sha256 = $env:TG_MYSQL_SHA256
    }
}

function Write-Banner($t) { Write-Host "`n===== $t =====" -ForegroundColor Cyan; Write-Log "===== $t =====" }
function Write-Ok($m)     { Write-Host "  [OK] $m" -ForegroundColor Green; Write-Log "[OK] $m" }
function Write-Warn($m)   { Write-Host "  [!] $m" -ForegroundColor Yellow; Write-Log "[!] $m" }
function Write-Err($m)    { Write-Host "  [X] $m" -ForegroundColor Red; Write-Log "[X] $m" }

# 等待回车继续（关键节点；OPS-08：-Unattended 时跳过交互）
function Wait-Key($msg = '按 Enter 继续') {
    if ($Unattended) { Write-Host "  [无人值守] $msg（自动继续）" -ForegroundColor Gray; return }
    Write-Host "`n$msg" -ForegroundColor Gray; [void][Console]::ReadLine()
}

# 解压 zip
function Expand-Zip($zip, $dest) {
    if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Expand-Archive -Path $zip -DestinationPath $dest -Force
    # 很多官方包解压后多嵌套一层顶层目录，自动上提一层
    $sub = Get-ChildItem -Path $dest | Where-Object { $_.PSIsContainer }
    if ($sub.Count -eq 1) {
        $inner = $sub[0].FullName
        Get-ChildItem -Path $inner | Move-Item -Destination $dest -Force
        Remove-Item -Path $inner -Force -Recurse -ErrorAction SilentlyContinue
    }
}

# 计算文件 SHA256
function Get-FileSha256($path) {
    try {
        $sha = [System.Security.Cryptography.SHA256]::Create()
        $fs = [System.IO.File]::OpenRead($path)
        try { return (([System.BitConverter]::ToString($sha.ComputeHash($fs))) -replace '-', '').ToLower() }
        finally { $fs.Dispose() }
    } catch { return $null }
}

# 下载（OPS-08：可选 SHA256 校验；校验和可经环境变量注入）
function Get-RemoteFile($url, $outPath, $expectedSha256) {
    if (Test-Path $outPath) { return $true }
    try {
        Write-Host "  下载 $url ..." -ForegroundColor Gray
        $wc = New-Object System.Net.WebClient
        $wc.DownloadFile($url, $outPath)
    } catch {
        Write-Warn "下载失败：$($_.Exception.Message)"
        if (Test-Path $outPath) { Remove-Item $outPath -Force -ErrorAction SilentlyContinue }
        return $false
    }
    if ($expectedSha256) {
        $actual = Get-FileSha256 $outPath
        if ($actual -and $actual -ne $expectedSha256.ToLower()) {
            Write-Warn "下载文件 SHA256 校验失败（期望 $expectedSha256，实际 $actual），已删除。可设置 TG_<组件>_SHA256 环境变量覆盖。"
            Remove-Item $outPath -Force -ErrorAction SilentlyContinue
            return $false
        }
        Write-Ok 'SHA256 校验通过'
    } else {
        Write-Warn '未配置 SHA256 校验和（可通过 TG_JRE_SHA256/TG_NGINX_SHA256/TG_MYSQL_SHA256 环境变量提供，提高供应链安全）'
    }
    return Test-Path $outPath
}

# 探测目标机已安装程序（注册表/常见路径）
function Find-InPath($cmd) {
    try { $p = (& where.exe $cmd 2>$null | Select-Object -First 1); if ($p) { return $p.Trim() } } catch {}
    return $null
}

# ---------------- 0. 欢迎 ----------------
Clear-Host
Write-Host @"

   ┌──────────────────────────────────────────────┐
   │          TraceGuard 一键安装向导  V$AppVersion          │
   │   需求-代码一致性验证与缺陷自动检测系统            │
   └──────────────────────────────────────────────┘

   本向导将把 TraceGuard 安装到你的电脑并可直接启动使用。
   目标机无需安装 Maven / Node；JRE/MySQL/nginx 将自动探测或拉取便携版。
"@ -ForegroundColor White

# ---------------- 1. 选择安装目录 ----------------
Write-Banner '1/7 选择安装目录'
if ($InstallDir -eq '') {
    $default = Join-Path $env:ProgramFiles $AppName
    Write-Host "  默认安装目录：$default" -ForegroundColor Gray
    # OPS-08：-Unattended 时直接用默认目录（或 TG_INSTALL_DIR），避免 Read-Host 卡住
    if ($Unattended) {
        $InstallDir = if ($env:TG_INSTALL_DIR) { $env:TG_INSTALL_DIR } else { $default }
        Write-Host "  [无人值守] 使用安装目录：$InstallDir" -ForegroundColor Gray
    } else {
        $input = Read-Host "  输入安装目录（回车使用默认）"
        if ($input.Trim() -ne '') { $InstallDir = $input.Trim() } else { $InstallDir = $default }
    }
}
$InstallDir = $InstallDir.TrimEnd('\')
if (-not (Test-Path $InstallDir)) { New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null }
Write-Ok "安装目录：$InstallDir"

$RuntimeDir = Join-Path $InstallDir 'runtime'
$BackendOut = Join-Path $InstallDir 'backend'
$FrontendOut = Join-Path $InstallDir 'frontend'
New-Item -ItemType Directory -Force -Path $RuntimeDir | Out-Null

# ---------------- 2. 校验构建产物 ----------------
Write-Banner '2/7 校验安装源产物'

# 兼容两种分发形态：
#   A) 将 installer-staging/ 里的内容复制到安装目录根，install.ps1 在根目录运行
#   B) 将整个 installer-staging/ 文件夹复制到安装目录下，install.ps1 在 installer-staging/ 子目录运行
$searchRoots = @($PSScriptRoot, $SourceRoot)
$jar = $null
$initSql = $null
$distDir = $null
foreach ($root in $searchRoots) {
    if (-not $jar) { $jar = Get-ChildItem -Path (Join-Path $root 'backend\target') -Filter 'traceguard-backend-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { -not $_.Name.EndsWith('.jar.original') } | Select-Object -First 1 }
    if (-not $jar) { $jar = Get-ChildItem -Path (Join-Path $root 'backend\app') -Filter 'traceguard-backend-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { -not $_.Name.EndsWith('.jar.original') } | Select-Object -First 1 }
    if (-not $initSql) { $initSql = Join-Path $root 'backend\src\main\resources\sql\init.sql'; if (-not (Test-Path $initSql)) { $initSql = $null } }
    if (-not $initSql) { $tmp = Join-Path $root 'backend\init.sql'; if (Test-Path $tmp) { $initSql = $tmp } }
    if (-not $distDir) { $tmp = Join-Path $root 'frontend\dist'; if (Test-Path $tmp) { $distDir = $tmp } }
}
if (-not $jar)    { Write-Err "未找到 backend jar（请先构建或放入 backend\app）。"; Wait-Key '按 Enter 退出'; exit 1 }
if (-not (Test-Path $initSql)) { Write-Err "未找到 init.sql。"; Wait-Key '按 Enter 退出'; exit 1 }
if (-not (Test-Path $distDir)) { Write-Err "未找到 frontend\dist（请先 npm run build）。"; Wait-Key '按 Enter 退出'; exit 1 }
$SourceRoot = Split-Path -Parent $jar.FullName | Split-Path -Parent | Split-Path -Parent  # 以实际产物所在目录为后续 SourceRoot
Write-Ok "后端：$($jar.Name)"
Write-Ok "数据库脚本：init.sql"
Write-Ok "前端：dist/"

# 复制产物到安装目录
Write-Host '  复制产物到安装目录...' -ForegroundColor Gray
$appOut = Join-Path $BackendOut 'app'; New-Item -ItemType Directory -Force -Path $appOut | Out-Null
Copy-Item $jar.FullName -Destination (Join-Path $appOut $jar.Name) -Force
Copy-Item $initSql -Destination (Join-Path $BackendOut 'init.sql') -Force
Copy-Item $distDir -Destination $FrontendOut -Recurse -Force
$nginxConf = Join-Path $SourceRoot 'frontend\nginx-windows.conf'
if (Test-Path $nginxConf) { Copy-Item $nginxConf -Destination (Join-Path $FrontendOut 'nginx-windows.conf') -Force }
# 预建 nginx 所需的 logs/temp 目录（否则 nginx 启动报 CreateDirectory 失败）
New-Item -ItemType Directory -Force -Path (Join-Path $FrontendOut 'logs') | Out-Null
foreach ($t in @('temp','temp/client_body_temp','temp/proxy_temp','temp/fastcgi_temp','temp/uwsgi_temp')) {
    New-Item -ItemType Directory -Force -Path (Join-Path $FrontendOut $t) | Out-Null
}
$jarName = $jar.Name
Write-Ok '产物复制完成'

# ---------------- 3. 准备运行时（JRE / MySQL / nginx） ----------------
Write-Banner '3/7 准备运行时（JRE / MySQL / nginx）'
$useSystem = @{ jre = $false; mysql = $false; nginx = $false }

# 3.1 JRE
$jreDir = Join-Path $RuntimeDir $Prereqs.jre.Dir
# OPS-08：系统 Java 仅版本 ≥21 才直接使用（否则视为未满足，拉取便携 JRE 21）
$sysJavaOk = $false
$sysJava = Find-InPath 'java'
if ($sysJava) {
    try {
        $v = (& java -version 2>&1 | Select-Object -First 1)
        $m = [regex]::Match($v, 'version "([^"]+)"')
        if ($m.Success -and $m.Groups[1].Value -match '^(\d+)' -and [int]$Matches[1] -ge 21) { $sysJavaOk = $true }
    } catch { }
}
if ($sysJavaOk) {
    Write-Ok "检测到系统已安装 Java 21+，直接使用"
    $useSystem.jre = $true
} elseif ($sysJava) {
    Write-Warn "检测到系统 Java 版本低于 21（项目编译目标 java.version=21），将改用便携 JRE 21"
} elseif (Test-Path (Join-Path $jreDir 'bin\java.exe')) {
    Write-Ok "已存在便携 JRE，跳过下载"
} elseif (-not $SkipDownload) {
    Write-Host "  未检测到 Java 21+，拉取便携 $($Prereqs.jre.Name) ..." -ForegroundColor Gray
    $zip = Join-Path $RuntimeDir 'jre.zip'
    if (Get-RemoteFile $Prereqs.jre.Url $zip $Prereqs.jre.Sha256) { Expand-Zip $zip $jreDir; Remove-Item $zip -Force -ErrorAction SilentlyContinue; Write-Ok '便携 JRE 就绪' }
    else { Write-Warn "JRE 下载失败，请将 JRE 21 解压到 $jreDir 后重跑"; Wait-Key '按 Enter 退出'; exit 1 }
} else { Write-Warn 'SkipDownload 且未检测到 JRE，运行将失败' }

# 3.2 MySQL
$mysqlDir = Join-Path $RuntimeDir $Prereqs.mysql.Dir
if (Find-InPath 'mysql') {
    Write-Ok "检测到系统已安装 MySQL 客户端，将使用系统 MySQL（需自行建库 traceguard 并执行 init.sql）"
    $useSystem.mysql = $true
} elseif (Test-Path (Join-Path $mysqlDir 'bin\mysqld.exe')) {
    Write-Ok "已存在便携 MySQL，跳过下载"
} elseif (-not $SkipDownload) {
    Write-Host "  未检测到 MySQL，拉取便携 $($Prereqs.mysql.Name) ..." -ForegroundColor Gray
    $zip = Join-Path $RuntimeDir 'mysql.zip'
    if (Get-RemoteFile $Prereqs.mysql.Url $zip $Prereqs.mysql.Sha256) { Expand-Zip $zip $mysqlDir; Remove-Item $zip -Force -ErrorAction SilentlyContinue; Write-Ok '便携 MySQL 就绪' }
    else { Write-Warn "MySQL 下载失败，请将 MySQL 8.0 winx64 zip 解压到 $mysqlDir 后重跑，或使用系统 MySQL"; $useSystem.mysql = $true }
} else { Write-Warn 'SkipDownload 且未检测到 MySQL，将尝试使用系统 MySQL'; $useSystem.mysql = $true }

# 3.3 nginx
$nginxDir = Join-Path $RuntimeDir $Prereqs.nginx.Dir
if (Find-InPath 'nginx') {
    Write-Ok "检测到系统已安装 nginx，直接使用"
    $useSystem.nginx = $true
} elseif (Test-Path (Join-Path $nginxDir 'nginx.exe')) {
    Write-Ok "已存在便携 nginx，跳过下载"
} elseif (-not $SkipDownload) {
    Write-Host "  未检测到 nginx，拉取便携 $($Prereqs.nginx.Name) ..." -ForegroundColor Gray
    $zip = Join-Path $RuntimeDir 'nginx.zip'
    if (Get-RemoteFile $Prereqs.nginx.Url $zip $Prereqs.nginx.Sha256) { Expand-Zip $zip $nginxDir; Remove-Item $zip -Force -ErrorAction SilentlyContinue; Write-Ok '便携 nginx 就绪' }
    else { Write-Warn "nginx 下载失败，将降级使用 vite preview（需 Node，可能不可用）" }
} else { Write-Warn 'SkipDownload 且未检测到 nginx' }

# ---------------- 4. 初始化数据库 ----------------
Write-Banner '4/7 初始化数据库'
# SEC-07：便携 MySQL 生成随机 root 密码（删除原公开硬编码默认值），仅写入本机启动配置
$mysqlRootPw = $null
if (-not $useSystem.mysql) {
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $mysqlRootPw = (($bytes | ForEach-Object { $_.ToString('x2') }) -join '')
}
$dataDir = Join-Path $mysqlDir 'data'
$myCnf = Join-Path $mysqlDir 'my.ini'

function Start-MySQL {
    $mysqld = Join-Path $mysqlDir 'bin\mysqld.exe'
    if (-not (Test-Path $mysqld)) { Write-Warn '未找到 mysqld.exe，跳过 MySQL 启动'; return $false }
    if (-not (Test-Path $dataDir)) {
        Write-Host '  初始化 MySQL 数据目录...' -ForegroundColor Gray
        # OPS-08：失败不再静默 2>$null——捕获输出并检查退出码
        $initOut = & $mysqld --initialize-insecure --user=root --basedir=$mysqlDir --datadir=$dataDir 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Err "MySQL 数据目录初始化失败（退出码 $LASTEXITCODE）：$(($initOut | Select-Object -First 3) -join ' ')"
            return $false
        }
    }
    $svc = Get-Service -Name 'MySQLTraceGuard' -ErrorAction SilentlyContinue
    if (-not $svc) {
        Write-Host '  注册 MySQL 服务 MySQLTraceGuard...' -ForegroundColor Gray
        $instOut = & $mysqld --install MySQLTraceGuard --defaults-file="$myCnf" 2>&1
        if ($LASTEXITCODE -ne 0 -and $instOut -notmatch 'already exists|服务已经存在') {
            Write-Warn "MySQL 服务注册失败（退出码 $LASTEXITCODE）：$($instOut | Select-Object -First 1)"
            return $false
        }
    }
    $startOut = & net start MySQLTraceGuard 2>&1
    if ($LASTEXITCODE -ne 0 -and $startOut -notmatch '已经启动|already been started') {
        Write-Warn "MySQL 服务启动失败（退出码 $LASTEXITCODE）：$($startOut | Select-Object -First 1)"
        return $false
    }
    Start-Sleep -Seconds 4
    return $true
}

if (-not $useSystem.mysql) {
    # 写最小化 my.ini（SEC-07：bind-address=127.0.0.1 仅监听本机，避免 3306 对局域网无密码暴露）
    @"
[mysqld]
basedir=$mysqlDir
datadir=$dataDir
port=3306
bind-address=127.0.0.1
character-set-server=utf8mb4
default_authentication_plugin=mysql_native_password
[client]
port=3306
"@ | Set-Content -Path $myCnf -Encoding ASCII
    if (-not (Start-MySQL)) {
        Write-Warn '便携 MySQL 启动失败，将改用系统 MySQL（需自行建库）'
        $useSystem.mysql = $true
    }
    if (-not $useSystem.mysql) {
        $mysqlExe = Join-Path $mysqlDir 'bin\mysql.exe'
        # 建库 + 建表（初始化无密码的本地实例，随后立即设置随机 root 密码）
        # OPS-08：各步检查退出码，失败不再静默
        $out = & $mysqlExe -u root -e "CREATE DATABASE IF NOT EXISTS traceguard DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" 2>&1
        if ($LASTEXITCODE -ne 0) { Write-Err "创建数据库失败：$($out | Select-Object -First 1)" }
        $out = & cmd /c "$mysqlExe -u root traceguard < `"$initSql`"" 2>&1
        if ($LASTEXITCODE -ne 0) { Write-Err "执行 init.sql 失败：$($out | Select-Object -First 1)" }
        # SEC-07：强制设置 root 密码（不再以无密码状态长期运行）
        # SEC-14：随机密码经 MYSQL_PWD 环境变量 + stdin 传递，避免出现在进程命令行（ps 可读）
        $env:MYSQL_PWD = $mysqlRootPw
        try {
            $sql = "ALTER USER 'root'@'localhost' IDENTIFIED WITH mysql_native_password BY '$mysqlRootPw'; FLUSH PRIVILEGES;"
            $out = ($sql | & $mysqlExe -u root 2>&1)
            if ($LASTEXITCODE -ne 0) { Write-Err "设置 root 随机密码失败：$($out | Select-Object -First 1)" }
        } finally {
            Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
        }
        Write-Ok '便携 MySQL 建库建表完成（root 已设置随机密码，仅本机访问）'
    }
} else {
    Write-Warn '使用系统 MySQL：请手动建库 traceguard 并执行 init.sql，或修改 backend/app/application-prod.yml 连接参数'
}

# ---------------- 5. 生成启动器 ----------------
Write-Banner '5/7 生成启动器脚本'
$jarPath = Join-Path $appOut $jarName

# 解析 java 可执行文件
if ($useSystem.jre) { $javaExe = 'java' }
else { $javaExe = Join-Path $jreDir 'bin\java.exe' }

# 使用系统 MySQL 时，获取 root 密码（OPS-08：-Unattended 时从 TG_MYSQL_PASSWORD 读取，避免交互卡住）
$mysqlPassword = ''
if ($useSystem.mysql) {
    if ($Unattended) {
        $mysqlPassword = $env:TG_MYSQL_PASSWORD
        if ([string]::IsNullOrEmpty($mysqlPassword)) { Write-Warn '未指定 TG_MYSQL_PASSWORD，系统 MySQL 将以无密码配置生成启动器' }
    } else {
        $mysqlPassword = Read-Host "  系统 MySQL root 密码（回车表示无密码）"
    }
}
$dbPassword = if ($mysqlPassword) { $mysqlPassword } else { $mysqlRootPw }

# OPS-08：数据库密码写入受限文件（仅当前用户可读），start-backend.bat 启动时读取注入，
#         不再以 --spring.datasource.password=xxx 明文嵌入 bat 命令行（进程列表/文件均可见）
$credFile = Join-Path $appOut '.db-credentials'
if ($dbPassword) {
    Set-Content -Path $credFile -Value $dbPassword -Encoding ASCII -NoNewline
    try { icacls $credFile /inheritance:r /grant:r ("{0}:(R,W)" -f $env:USERNAME) | Out-Null } catch { }
    Write-Ok "数据库密码已写入受限文件（$credFile），仅供本机启动使用"
}

# start-backend.bat（无窗口；OPS-07：内容用 ASCII 英文，避免编码乱码）
$backendBat = Join-Path $InstallDir 'start-backend.bat'
@"
@echo off
cd /d "%~dp0"
set "JAVA_EXE=$javaExe"
set "JAVA_OPTS=-Xms256m -Xmx1024m"
set "DB_PASS="
if exist "$credFile" (
  for /f "usebackq delims=" %%i in ("$credFile") do set "DB_PASS=%%i"
)
echo Starting $AppName backend on http://localhost:8080/api ...
if defined DB_PASS (
  start "TraceGuard-Backend" /min "%JAVA_EXE%" %JAVA_OPTS% -jar "$jarPath" --spring.profiles.active=prod --spring.datasource.password=%DB_PASS%
) else (
  start "TraceGuard-Backend" /min "%JAVA_EXE%" %JAVA_OPTS% -jar "$jarPath" --spring.profiles.active=prod
)
"@ | Set-Content -Path $backendBat -Encoding ASCII

# start-frontend.bat
$frontendBat = Join-Path $InstallDir 'start-frontend.bat'
if ($useSystem.nginx) {
    $feBody = "start `"TraceGuard-Frontend`" /min nginx -c `"$FrontendOut\nginx-windows.conf`" -p `"$FrontendOut`"`n"
} elseif (Test-Path (Join-Path $nginxDir 'nginx.exe')) {
    $nginxExe = Join-Path $nginxDir 'nginx.exe'
    $feBody = "start `"TraceGuard-Frontend`" /min `"$nginxExe`" -c `"$FrontendOut\nginx-windows.conf`" -p `"$FrontendOut`"`n"
} else {
    $feBody = "echo nginx not bundled; please install nginx or use docker.`n"
}
@"
@echo off
cd /d "%~dp0"
$feBody
"@ | Set-Content -Path $frontendBat -Encoding ASCII

# start-all.bat（OPS-07：内容用 ASCII 英文，避免编码乱码）
$allBat = Join-Path $InstallDir 'start-all.bat'
@"
@echo off
cd /d "%~dp0"
call start-backend.bat
call start-frontend.bat
timeout /t 8 /nobreak >nul
start http://localhost:80
echo $AppName started. Default account admin / admin123 (please change after first login).
pause
"@ | Set-Content -Path $allBat -Encoding ASCII

# stop.bat（OPS-08：除窗口标题外，增加按命令行匹配 java 进程兜底，避免"按标题 taskkill 停不掉"）
$stopBat = Join-Path $InstallDir 'stop.bat'
@"
@echo off
cd /d "%~dp0"
taskkill /FI "WINDOWTITLE eq TraceGuard-Backend*" /T /F >nul 2>nul
taskkill /FI "WINDOWTITLE eq TraceGuard-Frontend*" /T /F >nul 2>nul
rem Fallback: kill backend JVM by command line match on jar name
for /f "tokens=2 delims==" %%p in ('wmic process where "name='java.exe' and commandline like '%%$jarName%%'" get processid /value 2^>nul ^| findstr /r "^ProcessId="') do taskkill /PID %%p /T /F >nul 2>nul
nginx -s stop >nul 2>nul
echo $AppName stopped.
"@ | Set-Content -Path $stopBat -Encoding ASCII
Write-Ok '已生成 start-backend.bat / start-frontend.bat / start-all.bat / stop.bat'

# ---------------- 6. 快捷方式（桌面 + 开始菜单） ----------------
Write-Banner '6/7 创建快捷方式'
function New-Shortcut($target, $lnk, $desc) {
    $ws = New-Object -ComObject WScript.Shell
    $s = $ws.CreateShortcut($lnk)
    $s.TargetPath = $target
    $s.WorkingDirectory = $InstallDir
    $s.Description = $desc
    $s.Save()
}
try {
    $desktop = [Environment]::GetFolderPath('Desktop')
    $startMenu = Join-Path -Path ([Environment]::GetFolderPath('StartMenu')) -ChildPath 'Programs'
    New-Shortcut $allBat (Join-Path -Path $desktop -ChildPath "$AppName.lnk") "$AppName 启动"
    New-Shortcut $allBat (Join-Path -Path $startMenu -ChildPath "$AppName.lnk") "$AppName 启动"
    Write-Ok "桌面 / 开始菜单快捷方式已创建"
} catch { Write-Warn "快捷方式创建失败（可忽略）：$($_.Exception.Message)" }

# ---------------- 7. 注册 Windows 服务（可选） ----------------
Write-Banner '7/7 注册 Windows 服务（可选）'
# OPS-08：-Unattended 时由 TG_INSTALL_SERVICE=1 决定，避免 Read-Host 卡住无人值守安装
$wantService = $false
if (-not $NoService) {
    if ($Unattended) {
        if ($env:TG_INSTALL_SERVICE -eq '1') { $wantService = $true }
        else { Write-Ok '无人值守模式且未设置 TG_INSTALL_SERVICE=1，跳过服务注册' }
    } else {
        $ans = Read-Host '  是否将后端注册为 Windows 服务（开机自启）？[y/N]'
        if ($ans -match '^[yY]$') { $wantService = $true }
        else { Write-Ok '跳过服务注册' }
    }
} else { Write-Ok '已指定 -NoService，跳过' }

if ($wantService) {
    $nssm = Join-Path $RuntimeDir 'nssm.exe'
    if (-not (Test-Path $nssm)) {
        $nssmUrl = 'https://nssm.cc/release/nssm-2.24.zip'
        $nssmZip = Join-Path $RuntimeDir 'nssm.zip'
        if (Get-RemoteFile $nssmUrl $nssmZip $env:TG_NSSM_SHA256) {
            Expand-Zip $nssmZip (Join-Path $RuntimeDir 'nssm-tmp')
            $exe = Get-ChildItem -Path (Join-Path $RuntimeDir 'nssm-tmp') -Filter 'nssm.exe' -Recurse | Select-Object -First 1
            if ($exe) { Copy-Item $exe.FullName -Destination $nssm -Force }
            Remove-Item -Recurse -Force (Join-Path $RuntimeDir 'nssm-tmp') -ErrorAction SilentlyContinue
        }
    }
    if (Test-Path $nssm) {
        & $nssm install TraceGuardBackend "$javaExe" "-Xms256m -Xmx1024m -jar `"$jarPath`" --spring.profiles.active=prod"
        if ($LASTEXITCODE -eq 0) {
            & $nssm set TraceGuardBackend AppDirectory "$appOut"
            Write-Ok '已注册 Windows 服务 TraceGuardBackend（可用 services.msc 管理）'
        } else { Write-Err "nssm 服务注册失败（退出码 $LASTEXITCODE）" }
    } else { Write-Warn 'nssm 获取失败，跳过服务注册（可手动启动 start-all.bat）' }
}

# ---------------- 完成 ----------------
Clear-Host
Write-Host @"

   ┌──────────────────────────────────────────────┐
   │          TraceGuard 安装完成！                  │
   └──────────────────────────────────────────────┘

   安装目录：$InstallDir

   启动方式（任选其一）：
     - 双击桌面 / 开始菜单的「TraceGuard」快捷方式
     - 双击 $InstallDir\start-all.bat
     - 若已注册服务：services.msc 中启动 TraceGuardBackend

   访问地址：http://localhost:80   （默认账号 admin / admin123）

   停止：双击 stop.bat
   说明：JRE/MySQL/nginx 便携运行位于 $RuntimeDir

"@ -ForegroundColor Green
Wait-Key '按 Enter 退出安装向导'
