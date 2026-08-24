<#
.SYNOPSIS
    GAP-036：72h 无故障冒烟脚本（Windows / PowerShell 版，FUN-02 / OPS-10）

.DESCRIPTION
    每 TG_INTERVAL_MIN（默认 10）分钟提交一个样例分析任务（复用 samples/ecommerce-order 工程），
    采样任务状态/耗时、JVM 内存与 GC（jstat）、系统 CPU/内存，写入 scripts/smoke72h/。
    异常策略：单任务失败告警并重试一次；failed/network/timeout 均计入连续失败计数
    （连续网络故障不再被重置）；连续 FAIL_LIMIT 次失败中断并记录；系统内存使用率 > 85% 告警采样。
    数据清理：项目名/任务名均以 smoke- 前缀打标，结束后（含中断/异常）自动删除冒烟测试项目。

.PARAMETER Hours
    冒烟时长（小时），默认 72。

.EXAMPLE
    $env:TG_TOKEN = "管理员 JWT"
    powershell -ExecutionPolicy Bypass -File scripts/smoke72h.ps1 72

.NOTES
    前置：后端已启动（默认 http://localhost:8080/api）。Token 经 Cookie 或 Authorization 头传递均可。
    结果回填 docs/03-报告/可靠性测试记录.md。
#>
param(
    [int]$Hours = 72
)

$ErrorActionPreference = 'Continue'

$BASE_URL  = if ($env:TG_BASE_URL) { $env:TG_BASE_URL } else { 'http://localhost:8080/api' }
$TOKEN     = $env:TG_TOKEN
if ([string]::IsNullOrEmpty($TOKEN)) { Write-Host '请通过环境变量 TG_TOKEN 提供管理员 token'; exit 1 }
$INTERVAL_MIN = if ($env:TG_INTERVAL_MIN) { [int]$env:TG_INTERVAL_MIN } else { 10 }
$FAIL_LIMIT   = if ($env:TG_FAIL_LIMIT) { [int]$env:TG_FAIL_LIMIT } else { 3 }
$MEM_WARN_PCT = 85

$SCRIPT_DIR  = Split-Path -Parent $MyInvocation.MyCommand.Path
$PROJECT_ROOT = Split-Path -Parent $SCRIPT_DIR
$REQ_FILE    = Join-Path $PROJECT_ROOT 'samples\ecommerce-order\requirements.txt'
$CODE_DIR    = Join-Path $PROJECT_ROOT 'samples\ecommerce-order\code'
$OUT_DIR     = Join-Path $SCRIPT_DIR 'smoke72h'
if (-not (Test-Path $OUT_DIR)) { New-Item -ItemType Directory -Force -Path $OUT_DIR | Out-Null }
$LOG = Join-Path $OUT_DIR 'smoke.log'
$CSV = Join-Path $OUT_DIR 'smoke.csv'

# OPS-10：收集冒烟测试项目 id，结束/中断时统一删除
$script:SmokeProjIds = [System.Collections.ArrayList]::new()

# ---------- 工具函数 ----------
function Write-Log([string]$msg) {
    $line = "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] $msg"
    Write-Host $line
    Add-Content -Path $LOG -Value $line -Encoding UTF8
}

function Invoke-TGApi {
    param([string]$Method, [string]$Uri, [string]$Body)
    $params = @{
        Method      = $Method
        Uri         = "$BASE_URL$Uri"
        Headers     = @{ Authorization = "Bearer $TOKEN" }
        ContentType = 'application/json'
        TimeoutSec  = 30
    }
    if ($Body) { $params.Body = $Body }
    try { return Invoke-RestMethod @params } catch { return $null }
}

# JVM 内存/GC 采样（jstat，精确）；找不到进程/无 jstat 时返回 0
function Get-Resources {
    $heap = 0; $nonheap = 0; $gcCount = 0; $gcMs = 0
    $proc = Get-CimInstance Win32_Process -Filter "Name like 'java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like '*traceguard*' } | Select-Object -First 1
    if ($proc -and (Get-Command jstat -ErrorAction SilentlyContinue)) {
        try {
            $out = jstat -gc $proc.ProcessId 2>$null | Select-Object -Skip 1 | Select-Object -First 1
            $f = $out -split '\s+' | Where-Object { $_ }
            if ($f.Length -ge 8) {
                $heap = [int](([double]$f[0] + $f[1] + $f[2] + $f[3]) / 1024)
                $nonheap = [int](([double]$f[5] + $f[6] + $f[7]) / 1024)
            }
            $gc = jstat -gc $proc.ProcessId 2>$null | Select-Object -Skip 1 | Select-Object -First 1
            $g = $gc -split '\s+' | Where-Object { $_ }
            if ($g.Length -ge 20) {
                $gcCount = [int]([double]$g[16] + $g[17])
                $gcMs = [int]([double]$g[18] + $g[19])
            }
        } catch { }
    }
    # 系统 CPU/内存（百分比）
    $cpu = 0; $mem = 0
    try {
        $os = Get-CimInstance Win32_OperatingSystem -ErrorAction SilentlyContinue
        if ($os) {
            $mem = [int](100 * ($os.TotalVisibleMemorySize - $os.FreePhysicalMemory) / $os.TotalVisibleMemorySize)
        }
        $cp = Get-CimInstance Win32_Processor -ErrorAction SilentlyContinue | Measure-Object -Property LoadPercentage -Average
        if ($cp) { $cpu = [int]$cp.Average }
    } catch { }
    return @{ heap = $heap; nonheap = $nonheap; gcCount = $gcCount; gcMs = $gcMs; cpu = $cpu; mem = $mem }
}

# OPS-10：清理 smoke- 前缀的测试项目（按收集的项目 id 删除）
function Clear-SmokeProjects {
    if ($script:SmokeProjIds.Count -eq 0) { Write-Log '无冒烟测试项目需清理'; return }
    Write-Log "清理 $($script:SmokeProjIds.Count) 个冒烟测试项目..."
    foreach ($id in $script:SmokeProjIds) {
        Invoke-TGApi 'DELETE' "/project/$id" | Out-Null
        Write-Log "  已删除冒烟测试项目 id=$id"
    }
    $script:SmokeProjIds.Clear()
}

# 提交并跟踪一个任务；返回状态 completed/failed/network/timeout
function Invoke-RunOnce {
    $name = "smoke-$(Get-Date -UFormat %s)"
    $proj = Invoke-TGApi 'POST' '/project/create' (@{ projectName = $name; description = '72h smoke (auto-cleanup)'; requirementFilePath = $REQ_FILE; codeProjectPath = $CODE_DIR } | ConvertTo-Json)
    if (-not $proj -or -not $proj.data -or -not $proj.data.id) { Write-Log 'ERROR 项目创建失败（网络或接口错误）'; return 'network' }
    [void]$script:SmokeProjIds.Add([long]$proj.data.id)
    $task = Invoke-TGApi 'POST' '/analysis/task/create' (@{ projectId = [long]$proj.data.id; taskName = $name } | ConvertTo-Json)
    if (-not $task -or -not $task.data -or -not $task.data.id) { Write-Log "ERROR 任务创建失败(proj=$($proj.data.id))"; return 'failed' }
    $taskId = [long]$task.data.id
    Invoke-TGApi 'POST' "/analysis/task/run/$taskId" | Out-Null
    # 轮询任务状态（最多 5min）
    $status = ''
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Seconds 5
        $t = Invoke-TGApi 'GET' "/analysis/task/$taskId"
        if ($t -and $t.data) { $status = [string]$t.data.status }
        if ($status -eq 'completed' -or $status -eq 'failed') { break }
    }
    if ([string]::IsNullOrEmpty($status)) { return 'network' }   # 轮询期间接口无响应 = 网络故障
    if ($status -ne 'completed' -and $status -ne 'failed') { return 'timeout' }
    return $status
}

# ---------- 主循环 ----------
if (-not (Test-Path $REQ_FILE)) { Write-Host "样例需求文件不存在: $REQ_FILE"; exit 1 }

"" | Set-Content -Path $CSV -Encoding UTF8
Add-Content -Path $CSV -Value 'time,task,status,elapsed_ms,heap_mb,nonheap_mb,gc_count,gc_ms,cpu_pct,mem_pct' -Encoding UTF8
Write-Log "72h 冒烟启动：${Hours}h，每 ${INTERVAL_MIN}min 一次，样例=$REQ_FILE"

$startTs = Get-Date
$endTs = $startTs.AddHours($Hours)
$failStreak = 0

try {
    while ((Get-Date) -lt $endTs) {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $status = Invoke-RunOnce
        $sw.Stop()
        # OPS-10：单次失败/网络故障/超时时重试一次
        if ($status -ne 'completed') {
            Write-Log "WARN 任务 $status elapsed=$($sw.ElapsedMilliseconds)ms，重试一次..."
            $status = Invoke-RunOnce
        }
        $res = Get-Resources
        $taskName = "smoke-$(Get-Date -UFormat %s)"
        Add-Content -Path $CSV -Value ("{0},{1},{2},{3},{4},{5},{6},{7},{8},{9}" -f `
            (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $taskName, $status, $sw.ElapsedMilliseconds,
            $res.heap, $res.nonheap, $res.gcCount, $res.gcMs, $res.cpu, $res.mem) -Encoding UTF8

        if ($status -eq 'completed') {
            $failStreak = 0
            Write-Log "OK  任务完成 elapsed=$($sw.ElapsedMilliseconds)ms heap=$($res.heap)MB mem=$($res.mem)%"
        } else {
            # OPS-10：failed/network/timeout 一律计入连续失败（连续网络故障不再被重置）
            $failStreak++
            Write-Log "WARN 任务 $status elapsed=$($sw.ElapsedMilliseconds)ms（连续失败 ${failStreak}/${FAIL_LIMIT}）"
            if ($failStreak -ge $FAIL_LIMIT) { Write-Log "ERROR 连续 ${FAIL_LIMIT} 次失败（类型 $status），中断冒烟并记录"; exit 1 }
        }

        if ($res.mem -ge $MEM_WARN_PCT) {
            Write-Log "WARN 系统内存使用率 $($res.mem)% 超过 ${MEM_WARN_PCT}%，已采样（见 smoke.csv）"
        }
        Start-Sleep -Seconds ($INTERVAL_MIN * 60)
    }
} finally {
    # OPS-10：结束/中断时清理冒烟测试项目
    Clear-SmokeProjects
}

Write-Log "72h 冒烟完成：无未恢复故障，记录见 $CSV 与 $LOG（汇总回填 docs/03-报告/可靠性测试记录.md）"
