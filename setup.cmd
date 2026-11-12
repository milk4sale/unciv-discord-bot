@echo off
rem CivBot setup for Windows: double-click this file (safe to run again to update).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\windows\setup.ps1" %*
set CODE=%ERRORLEVEL%
if not defined CI pause
exit /b %CODE%
