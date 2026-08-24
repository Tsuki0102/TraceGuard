@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo 正在启动 TraceGuard 安装向导...
powershell -ExecutionPolicy Bypass -NoProfile -File "%~dp0install.ps1"
