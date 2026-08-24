<#
AUD-12：Windows 原生一键部署脚本（PowerShell 5.1+，Windows 10/11 自带）。
按序执行：前置检查 -> 数据库初始化 -> 后端构建 -> 前端构建 -> 生成启动脚本。
幂等可重跑：重复执行时已构建产物/已初始化数据库会被跳过。
用法：
  powershell -ExecutionPolicy Bypass -File scripts\deploy-windows.ps1
可选参数：
  -MysqlUser   MySQL 用户（默认 root）
  -MysqlPass   MySQL 密码（默认空，若未提供会提示输入）
  -SkipBuild   跳过前后端构建（仅生成启动脚本）
示例：
  powershell -ExecutionPolicy Bypass -File scripts\deploy-windows.ps1 -MysqlUser root -MysqlPass 'yourpass'
#>
param(
    [string]$MysqlUser = 'root',
    [string]$MysqlPass = '',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$BackendDir = Join-Path $Root 'backend'
$FrontendDir = Join-Path $Root 'frontend'
$InitSql = Join-Path $BackendDir 'src\main\resources\sql\init.sql'
$TargetDir = Join-Path $BackendDir 'target'

function Write-Step($msg) { Write-Host "`n===== $msg =====" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "[OK] $msg" -ForegroundColor Green }
function Write-Err($msg)  { Write-Host "[FAIL] $msg" -ForegroundColor Red }

# 统一执行命令并检查退出码；失败时抛出终止（OPS-07：不再忽略退出码）
function Invoke-Checked($title, [scriptblock]$sb) {
    Write-Host "  执行：$title ..." -ForegroundColor Gray
    & $sb
    if ($LASTEXITCODE -ne 0) { throw "命令失败（退出码 $LASTEXITCODE）：$title" }
}

# ---------- 1. 前置检查 ----------
Write-Step '1/5 前置检查（Java 21+ / MySQL 8.0+ / Node 20+）'
$javaOk = $false
try {
    $javaVer = (& java -version 2>&1 | Select-Object -First 1)
    # OPS-07：版本收紧为 ≥21（原正则误放行 JDK 17，与 java.version=21 编译目标不符）
    $m = [regex]::Match($javaVer, 'version "([^"]+)"')
    if ($m.Success) {
        $v = $m.Groups[1].Value
        if ($v -match '^(\d+)') { $major = [int]$Matches[1]; if ($major -ge 21) { $javaOk = $true } }
    }
    Write-Ok "Java: $javaVer"
} catch { Write-Err '未检测到 Java，请安装 JDK 21+（https://adoptium.net）并配置 JAVA_HOME 与 Path' }
if (-not $javaOk) { Write-Err 'Java 版本需为 21+（本工程编译目标 java.version=21）' }

$mysqlOk = $false
try {
    $mysqlVer = (& mysql --version 2>&1 | Select-Object -First 1)
    $m = [regex]::Match($mysqlVer, 'Distrib (\d+)\.')
    if ($m.Success -and [int]$m.Groups[1].Value -ge 8) { $mysqlOk = $true }
    Write-Ok "MySQL: $mysqlVer"
} catch { Write-Err '未检测到 MySQL 客户端，请安装 MySQL 8.0+ 并加入 Path' }
if (-not $mysqlOk) { Write-Err 'MySQL 需为 8.0+' }

$nodeOk = $false
try {
    $nodeVer = (& node -v 2>&1 | Select-Object -First 1)
    # OPS-07：Node 版本口径统一为 20 LTS（与 CI / Dockerfile / 部署文档一致）
    if ($nodeVer -match '^v(\d+)\.') { if ([int]$Matches[1] -ge 20) { $nodeOk = $true } }
    Write-Ok "Node: $nodeVer"
} catch { Write-Err '未检测到 Node.js，请安装 Node.js 20 LTS（https://nodejs.org）' }
if (-not $nodeOk) { Write-Err 'Node 需为 20.x（LTS）或更高' }

$mavenOk = $false
try {
    $mvnVer = (& mvn -version 2>&1 | Select-Object -First 1)
    if ($mvnVer -match 'Apache Maven') { $mavenOk = $true; Write-Ok "Maven: $mvnVer" }
} catch { Write-Err '未检测到 Maven，请安装 Maven 3.8+（https://maven.apache.org）并加入 Path' }
if (-not $mavenOk) { Write-Err 'Maven 未安装' }

if (-not ($javaOk -and $mysqlOk -and $nodeOk -and $mavenOk)) {
    Write-Err '前置检查未通过，请按提示安装缺失组件后重跑本脚本。'
    exit 1
}

# ---------- 2. 数据库初始化 ----------
Write-Step '2/5 数据库初始化（幂等：库/表已存在则跳过）'
if ($MysqlPass -eq '') {
    $securePass = Read-Host "请输入 MySQL $MysqlUser 密码（无密码直接回车）" -AsSecureString
    $BSTR = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePass)
    $MysqlPass = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($BSTR)
    [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($BSTR)
}
# OPS-07：密码经 MYSQL_PWD 环境变量传递，避免出现在进程命令行（-p 参数进程列表可见）
$mysqlArgs = "-u$MysqlUser"
$env:MYSQL_PWD = $MysqlPass
try {
    $dbCount = '0'
    try {
        $dbCount = (& mysql $mysqlArgs -N -e "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='traceguard';" 2>$null | Select-Object -First 1)
    } catch { $dbCount = '0' }
    if ($dbCount -match '^\s*0\s*$') {
        Write-Host "数据库 traceguard 不存在，执行 init.sql 建库建表..."
        & mysql $mysqlArgs -e "CREATE DATABASE IF NOT EXISTS traceguard DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
        if ($LASTEXITCODE -ne 0) { throw '创建数据库失败' }
        & cmd /c "mysql $mysqlArgs traceguard < `"$InitSql`""
        if ($LASTEXITCODE -ne 0) { throw '执行 init.sql 失败' }
        Write-Ok '数据库初始化完成（init.sql）'
    } else {
        Write-Ok '数据库 traceguard 已存在，跳过建库（启动时由 spring.sql.init 幂等迁移补表）'
    }
} finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}

# ---------- 3. 后端构建 ----------
Write-Step '3/5 后端构建（mvn -B package -DskipTests）'
if (-not $SkipBuild) {
    Push-Location $BackendDir
    try {
        & mvn -B package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw '后端构建失败' }
        Write-Ok '后端 jar 构建完成'
    } finally { Pop-Location }
} else { Write-Ok '已跳过构建（-SkipBuild）' }

$jar = Get-ChildItem -Path $TargetDir -Filter 'traceguard-backend-*.jar' -ErrorAction SilentlyContinue | Where-Object { -not $_.Name.EndsWith('.jar.original') } | Select-Object -First 1
if (-not $jar) { Write-Err "未找到后端 jar（$TargetDir），请检查构建产物"; exit 1 }
Write-Ok "后端产物：$($jar.Name)"

# ---------- 4. 前端构建 ----------
Write-Step '4/5 前端构建（npm ci && npm run build）'
if (-not $SkipBuild) {
    Push-Location $FrontendDir
    try {
        & npm ci
        if ($LASTEXITCODE -ne 0) { throw 'npm ci 失败' }
        & npm run build
        if ($LASTEXITCODE -ne 0) { throw 'npm run build 失败' }
        Write-Ok '前端构建完成（dist/）'
    } finally { Pop-Location }
} else { Write-Ok '已跳过构建（-SkipBuild）' }

# ---------- 5. 生成启动脚本 ----------
Write-Step '5/5 生成启动脚本（start-backend.bat / start-frontend.bat / start-all.bat）'
$backendBat = Join-Path $Root 'start-backend.bat'
$frontendBat = Join-Path $Root 'start-frontend.bat'
$allBat = Join-Path $Root 'start-all.bat'

$jarName = $jar.Name
# OPS-07：bat 内容一律使用 ASCII 英文，避免 Windows 代码页/编码差异导致乱码丢字
@"
@echo off
cd /d "%~dp0"
set "JAVA_OPTS=-Xms256m -Xmx1024m"
echo Starting TraceGuard backend on http://localhost:8080/api ...
java %JAVA_OPTS% -jar "backend\target\$jarName" --spring.profiles.active=prod
pause
"@ | Set-Content -Path $backendBat -Encoding ASCII

@"
@echo off
cd /d "%~dp0"
where nginx >nul 2>nul
if %errorlevel%==0 (
  echo Starting frontend via nginx (static dist, port 80)...
  nginx -c "%~dp0frontend\nginx-windows.conf" -p "%~dp0frontend"
) else (
  echo nginx not found, fallback to vite preview on http://localhost:3000 ...
  cd frontend
  call npx vite preview --port 3000
)
pause
"@ | Set-Content -Path $frontendBat -Encoding ASCII

@"
@echo off
cd /d "%~dp0"
echo Starting TraceGuard (backend + frontend)...
start "TraceGuard-Backend" cmd /c "%~dp0start-backend.bat"
start "TraceGuard-Frontend" cmd /c "%~dp0start-frontend.bat"
timeout /t 8 /nobreak >nul
start http://localhost:3000
echo.
echo TraceGuard started. Default account admin/admin123 (please change after first login).
echo Backend API: http://localhost:8080/api    Frontend: http://localhost:3000
"@ | Set-Content -Path $allBat -Encoding ASCII

Write-Ok '启动脚本已生成：start-backend.bat / start-frontend.bat / start-all.bat'
Write-Host "`n部署完成！双击 start-all.bat 启动；或分别启动 start-backend.bat 与 start-frontend.bat。" -ForegroundColor Green
Write-Host '如需生产密钥加固，请设置环境变量：JWT_SECRET / STORAGE_ENCRYPT_KEY / BACKUP_ENCRYPT_KEY 后启动。' -ForegroundColor Yellow
