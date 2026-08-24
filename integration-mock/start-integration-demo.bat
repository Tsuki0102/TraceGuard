@echo off
chcp 65001 >nul
powershell -ExecutionPolicy Bypass -File "%~dp0start-integration-demo.ps1"
pause
