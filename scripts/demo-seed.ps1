# =====================================================================
# B7 一键演示脚本（scripts/demo-seed.ps1）
# 用途：面向评审/演示环境，一键预置真实演示数据——登录演示账号 -> 创建演示项目
#       -> 上传示例需求文档与代码工程 -> 创建并启动分析任务 -> 轮询至完成，
#       最终打印前端体验路径。全程调用真实 REST API 产生真实分析结果，
#       不注入任何伪造数据（与 EvalCenter「无造假数据」原则一致）。
# 前置：TraceGuard 已部署并可访问（本机 docker-compose 或 Windows 一键部署），
#       init.sql 已含演示账号 demo（密码 demo12345）。
# 用法：powershell -ExecutionPolicy Bypass -File scripts\demo-seed.ps1 `
#           -BaseUrl http://localhost:8080 -FrontendUrl http://localhost
# =====================================================================
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$FrontendUrl = "http://localhost",
    [string]$Username = "demo",
    [string]$Password = "demo12345",
    [string]$SampleDir = (Join-Path $PSScriptRoot "..\samples\ecommerce-order")
)

$ErrorActionPreference = "Stop"
$Api = "$BaseUrl/api"

function Read-ResultData($resp) {
    # 后端统一响应包 Result{code,message,data}；非 200 抛错
    $json = $resp.Content | ConvertFrom-Json
    if ($json.code -ne 200 -and $json.code -ne 0) {
        throw "API 调用失败：$($json.message)"
    }
    return $json.data
}

Write-Host "==> [1/6] 登录演示账号 $Username ..."
$loginBody = @{ username = $Username; password = $Password } | ConvertTo-Json
$login = Invoke-WebRequest -Uri "$Api/auth/login" -Method Post -Body $loginBody `
    -ContentType "application/json" -SessionVariable Web -UseBasicParsing
Read-ResultData $login | Out-Null
Write-Host "    登录成功（会话 Cookie 已建立）"

Write-Host "==> [2/6] 创建演示项目 ..."
$projBody = @{
    projectName = "电商订单演示项目"
    techStack   = "Java"
    description = "B7 一键演示预置：电商订单示例工程（samples/ecommerce-order），用于评审快速体验需求-代码一致性验证与缺陷定位全流程。"
} | ConvertTo-Json
$proj = Invoke-WebRequest -Uri "$Api/project/create" -Method Post -Body $projBody `
    -ContentType "application/json" -WebSession $Web -UseBasicParsing
$project = Read-ResultData $proj
$projectId = $project.id
Write-Host "    项目 ID = $projectId"

Write-Host "==> [3/6] 上传需求文档与代码工程 ..."
$reqFile = Join-Path $SampleDir "requirements.txt"
$zipFile = Join-Path $SampleDir "code.zip"
if (-not (Test-Path $reqFile)) { throw "缺少需求文档：$reqFile" }
if (-not (Test-Path $zipFile)) { throw "缺少代码工程：$zipFile" }
# PowerShell 7+ 支持 -Form；Windows PowerShell 5.1 用 curl.exe 兜底
$psVersion = $PSVersionTable.PSVersion.Major
if ($psVersion -ge 6) {
    Invoke-WebRequest -Uri "$Api/analysis/upload/requirement/$projectId" -Method Post `
        -Form @{ file = Get-Item $reqFile } -WebSession $Web -UseBasicParsing | Out-Null
    Invoke-WebRequest -Uri "$Api/analysis/upload/code/$projectId" -Method Post `
        -Form @{ file = Get-Item $zipFile } -WebSession $Web -UseBasicParsing | Out-Null
} else {
    # 5.1：借助会话 Cookie 交由 curl.exe 上传
    $cookies = ($Web.Cookies.GetCookies("$BaseUrl/") | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join "; "
    curl.exe -s -H "Cookie: $cookies" -F "file=@$reqFile" "$Api/analysis/upload/requirement/$projectId" | Out-Null
    curl.exe -s -H "Cookie: $cookies" -F "file=@$zipFile" "$Api/analysis/upload/code/$projectId" | Out-Null
}
Write-Host "    需求文档 + code.zip 上传完成"

Write-Host "==> [4/6] 创建并启动分析任务（规则引擎，默认阈值）..."
$taskBody = @{
    projectId   = $projectId
    taskName    = "演示任务-$(Get-Date -Format 'yyyyMMdd-HHmm')"
    weightAlpha = 0.4; weightBeta = 0.35; weightGamma = 0.25
    thresholdT1 = 0.8; thresholdT2 = 0.5
} | ConvertTo-Json
$taskResp = Invoke-WebRequest -Uri "$Api/analysis/task/create" -Method Post -Body $taskBody `
    -ContentType "application/json" -WebSession $Web -UseBasicParsing
$task = Read-ResultData $taskResp
$taskId = $task.id
Invoke-WebRequest -Uri "$Api/analysis/task/run/$taskId" -Method Post `
    -WebSession $Web -UseBasicParsing | Out-Null
Write-Host "    任务 ID = $taskId，已启动"

Write-Host "==> [5/6] 轮询任务状态 ..."
while ($true) {
    Start-Sleep -Seconds 3
    $t = Read-ResultData (Invoke-WebRequest -Uri "$Api/analysis/task/$taskId" `
        -WebSession $Web -UseBasicParsing)
    Write-Host ("    进度 {0}%  {1}  {2}" -f $t.progress, $t.status, $t.currentStep)
    if ($t.status -eq "completed") { break }
    if ($t.status -in @("failed", "terminated")) { throw "任务异常结束：$($t.errorMessage)" }
}

Write-Host "==> [6/6] 演示数据就绪！"
Write-Host ""
Write-Host "  前端体验路径（5 分钟导览）：" -ForegroundColor Green
Write-Host "    1. 登录       $FrontendUrl（账号 $Username / $Password）"
Write-Host "    2. 项目详情   $FrontendUrl/projects（打开「电商订单演示项目」）"
Write-Host "    3. 一致性结果 任务 #$taskId 查看匹配对与判定溯源"
Write-Host "    4. 缺陷列表   查看缺陷定位、风险信号分解、判定溯源面板"
Write-Host "    5. 报告中心   导出 Word/PDF/Excel 报告"
Write-Host ""
Write-Host "  提示：如需 LLM 增强口径演示，请先在「大模型配置」页启用引擎后重跑任务。"
