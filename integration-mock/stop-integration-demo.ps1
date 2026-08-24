# Stop integration demo: kill mock node servers and backend java.
taskkill /f /im java.exe 2>$null
# Kill node processes running mock-server.js
Get-CimInstance Win32_Process -Filter "Name='node.exe'" | Where-Object { $_.CommandLine -like '*mock-server.js*' } | ForEach-Object { taskkill /f /pid $_.ProcessId 2>$null }
Write-Host 'Integration demo stopped.'
