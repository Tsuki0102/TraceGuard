# TraceGuard 集成演示：一键拉起全部 mock + 健康检查
# 拉起 Jira(18080) / 禅道(18081) / 企微(18082) / 钉钉(18083) 的本地 mock，
# 并打印端口连通性。已有实例会先停掉再重启，避免端口冲突。
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$mock = Join-Path $Root 'integration-mock\mock-server.js'

# 停掉已存在的 mock 实例（避免端口冲突）
Get-CimInstance Win32_Process -Filter "Name='node.exe'" |
  Where-Object { $_.CommandLine -like '*mock-server.js*' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force; Write-Host "killed old mock PID $($_.ProcessId)" }

# 启动新实例
Start-Process -FilePath 'node' -ArgumentList $mock -NoNewWindow -PassThru | Out-Null
Start-Sleep -Seconds 2

# 健康检查（OPS-11：任一端口未就绪即视为失败，退出码非零，不再误报"全部就绪"）
$allOk = $true
foreach ($p in 18080, 18081, 18082, 18083) {
  $conn = (Test-NetConnection -ComputerName 127.0.0.1 -Port $p -InformationLevel Quiet -WarningAction SilentlyContinue)
  Write-Host ("port {0} : {1}" -f $p, $(if ($conn) {'OK'} else {'FAIL'}))
  if (-not $conn) { $allOk = $false }
}
if ($allOk) {
  Write-Host 'Mock 全部就绪（Jira/禅道/企微/钉钉）' -ForegroundColor Green
  exit 0
} else {
  Write-Host '[FAIL] 存在端口未就绪，请检查端口占用与 mock-server.js 启动日志。' -ForegroundColor Red
  exit 1
}