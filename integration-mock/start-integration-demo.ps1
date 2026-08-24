# Start integration demo: Mock servers (Jira+Zentao) + backend with integration params.
# Usage: double-click start-integration-demo.bat
$PSScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Definition
$mock = Join-Path $PSScriptRoot 'mock-server.js'
$jar  = 'D:\TraceGuard\backend\app\traceguard-backend-1.0.0.jar'

# 1. Start Mock servers (Jira :18080, Zentao :18081)
Start-Process -FilePath 'node' -ArgumentList $mock -NoNewWindow -PassThru | Out-Null
Write-Host 'Mock servers starting...'
Start-Sleep -Seconds 2

# 2. Restart backend with integration config pointing to local mocks
taskkill /f /im java.exe 2>$null
Start-Sleep -Seconds 2

$args = @(
  '-Xms256m', '-Xmx1024m', '-jar', $jar,
  '--spring.profiles.active=prod',
  '--spring.datasource.password=123456',
  '--traceguard.integration.jira.enabled=true',
  '--traceguard.integration.jira.base-url=http://localhost:18080',
  '--traceguard.integration.jira.username=admin',
  '--traceguard.integration.jira.token=mock-token',
  '--traceguard.integration.jira.project-key=DEMO',
  '--traceguard.integration.zentao.enabled=true',
  '--traceguard.integration.zentao.base-url=http://localhost:18081',
  '--traceguard.integration.zentao.token=mock-token',
  '--traceguard.integration.zentao.product-id=1'
)
Start-Process -FilePath 'java' -ArgumentList $args -WorkingDirectory 'D:\TraceGuard\backend\app' -NoNewWindow -PassThru | Out-Null
Write-Host 'Backend restarting with integration params...'
Start-Sleep -Seconds 12
Write-Host 'Integration demo ready. Open frontend > Integration Test page to test, then export/push in Requirements/Defects pages.'
