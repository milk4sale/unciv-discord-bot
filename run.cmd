@echo off
rem Starts CivBot on Windows. Keep this window open while people play; close it to stop the bot.
rem   run.cmd --selftest   plays an offline test game (no Discord needed)
title CivBot
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\windows\run.ps1" %*
set CODE=%ERRORLEVEL%
if not defined CI pause
exit /b %CODE%
