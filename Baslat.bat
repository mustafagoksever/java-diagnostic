@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Start-Collector.ps1" %*
if errorlevel 1 (
    echo.
    echo Baslatilamadi. Yukaridaki hatayi kontrol edin.
    pause
    exit /b 1
)
