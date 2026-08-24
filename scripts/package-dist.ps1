<#
3.7 整改：可分发安装包打包脚本（Windows PowerShell 5.1+）。
将构建产物（后端 jar + 前端 dist + init.sql + 启动脚本）打包为可分发的 zip，
便于无构建环境的 Windows 机器直接解压运行（配合 docs 部署说明使用）。

前置：先执行 deploy-windows.ps1 完成构建（或手动 mvn package / npm run build）。
用法：
  powershell -ExecutionPolicy Bypass -File scripts\package-dist.ps1
参数：
  -Version   安装包版本号（默认取 backend jar 版本，其次取 pom.xml，最后 1.0.0）
  -SkipInstaller  跳过联动生成 exe 安装包（build-installer.ps1）
输出：
  dist-release/TraceGuard-<version>.zip
#>
param(
    [string]$Version = '',
    [switch]$SkipInstaller
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot

# 3.7 整改（增强）：分发包同时联动生成「一键安装包」（installer-staging + 可选 exe）。
# 若需关闭联动，传 -SkipInstaller。
$BackendDir = Join-Path $Root 'backend'
$FrontendDir = Join-Path $Root 'frontend'
$TargetDir = Join-Path $BackendDir 'target'
$DistDir = Join-Path $Root 'frontend\dist'
$OutDir = Join-Path $Root 'dist-release'
$InitSql = Join-Path $BackendDir 'src\main\resources\sql\init.sql'
$PomFile = Join-Path $BackendDir 'pom.xml'

function Write-Step($m) { Write-Host "`n===== $m =====" -ForegroundColor Cyan }
function Write-Ok($m)   { Write-Host "[OK] $m" -ForegroundColor Green }
function Write-Err($m)  { Write-Host "[FAIL] $m" -ForegroundColor Red }

# OPS-09：从 pom.xml 读取 <version>（与后端构建版本单一来源对齐，避免硬编码 1.2.0 漂移）
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

# ---------- 校验构建产物 ----------
Write-Step '校验构建产物'
$jar = Get-ChildItem -Path $TargetDir -Filter 'traceguard-backend-*.jar' -ErrorAction SilentlyContinue |
    Where-Object { -not $_.Name.EndsWith('.jar.original') } | Select-Object -First 1
if (-not $jar) { Write-Err "未找到后端 jar（$TargetDir），请先执行 deploy-windows.ps1 构建"; exit 1 }
Write-Ok "后端产物：$($jar.Name)"

if (-not (Test-Path $DistDir)) { Write-Err "未找到前端构建产物（$DistDir），请先 npm run build"; exit 1 }
Write-Ok '前端产物：frontend/dist'

if ($Version -eq '') {
    if ($jar.Name -match 'traceguard-backend-(.+)\.jar') { $Version = $Matches[1] }
    else { $Version = Get-PomVersion $PomFile }
    if (-not $Version) { $Version = '1.0.0' }
}
Write-Ok "打包版本：$Version"

# ---------- 组装发布目录 ----------
$tmp = Join-Path $OutDir "TraceGuard-$Version"
if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$backendOut = Join-Path $tmp 'backend'
$appOut = Join-Path $backendOut 'app'
New-Item -ItemType Directory -Force -Path $appOut | Out-Null
$frontendOut = Join-Path $tmp 'frontend'
New-Item -ItemType Directory -Force -Path $frontendOut | Out-Null

Copy-Item $jar.FullName -Destination (Join-Path $appOut $jar.Name)
Copy-Item $InitSql -Destination (Join-Path $backendOut 'init.sql')
Copy-Item $DistDir -Destination $frontendOut -Recurse

# 启动脚本（若存在）
foreach ($bat in @('start-backend.bat','start-frontend.bat','start-all.bat')) {
    $src = Join-Path $Root $bat
    if (Test-Path $src) { Copy-Item $src -Destination $tmp }
}

# 发布说明（OPS-07：使用 UTF8 with BOM，避免目标机 ANSI 解析乱码）
$releaseNotes = @"
TraceGuard V$Version 可分发安装包
=====================================
内容：
  backend/app/traceguard-backend-*.jar   后端运行包
  backend/init.sql                       数据库初始化脚本（首次部署执行）
  frontend/dist/                         前端静态资源（由 nginx 或 vite preview 托管）
  start-*.bat                            启动脚本（需先执行 deploy-windows.ps1 生成；或参考部署手册自写）

运行前置（目标机器）：
  - JDK 21+、MySQL 8.0+、Node.js 20 LTS（前端若用 vite preview 托管）
  - 数据库：CREATE DATABASE traceguard; 执行 backend/init.sql 建表

启动：
  1) 配置 backend/app/application-prod.yml 数据库连接（或环境变量 MYSQL_USER/MYSQL_PASSWORD）
  2) java -jar backend/app/traceguard-backend-*.jar --spring.profiles.active=prod
  3) 前端：nginx 指向 frontend/dist，或 npx vite preview（位于 frontend 目录）
  4) 浏览器访问前端地址，默认账号 admin / admin123（首次登录请修改密码）

说明：
  本 zip 为免安装分发包，不含 JDK/MySQL/Node 运行环境，需目标机自备。
  完整部署与配置见《部署手册》与 README.md。
"@
Set-Content -Path (Join-Path $tmp 'RELEASE_NOTES.txt') -Encoding UTF8 -Value $releaseNotes

# ---------- 打包 zip ----------
Write-Step '打包为 zip'
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Force -Path $OutDir | Out-Null }
$zip = Join-Path $OutDir "TraceGuard-$Version.zip"
if (Test-Path $zip) { Remove-Item -Force $zip }
Compress-Archive -Path $tmp -DestinationPath $zip -Force
Remove-Item -Recurse -Force $tmp
Write-Ok "安装包已生成：$zip"
Write-Host "`n可分发安装包打包完成。建议配合《部署手册》与 RELEASE_NOTES.txt 使用。" -ForegroundColor Green

# ---------- 4. 联动生成一键安装包（installer-staging + 可选 exe） ----------
if (-not $SkipInstaller) {
    Write-Step '联动生成一键安装包 (build-installer.ps1)'
    & powershell -ExecutionPolicy Bypass -NoProfile -File (Join-Path $PSScriptRoot 'build-installer.ps1') -Version $Version
}
