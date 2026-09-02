$p = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($p) {
    Stop-Process -Id $p.OwningProcess -Force -ErrorAction SilentlyContinue
    Write-Host "STOPPED"
} else {
    Write-Host "NO_BACKEND"
}
Start-Sleep -Seconds 2
New-Item -ItemType Directory -Force -Path 'd:\Develop\TraceGuard\backend\logs' | Out-Null
Start-Process -FilePath 'cmd' -ArgumentList '/c','mvn spring-boot:run -Dspring-boot.run.profiles=dev > d:\Develop\TraceGuard\backend\logs\boot.log 2>&1' -WindowStyle Hidden -WorkingDirectory 'd:\Develop\TraceGuard\backend'
Write-Host "STARTED"
