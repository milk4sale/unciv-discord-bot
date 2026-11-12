@echo off
rem Runs CivBot in the background, starting when you log in to Windows.
rem   install-service.cmd           install and start
rem   install-service.cmd -Remove   stop and uninstall
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-service.ps1" %*
set CODE=%ERRORLEVEL%
if not defined CI pause
exit /b %CODE%
