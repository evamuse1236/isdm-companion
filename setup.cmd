@echo off
title ISDM Companion - Setup
cd /d "%~dp0"

where node >nul 2>&1
if errorlevel 1 (
  echo.
  echo   Node.js is not installed.
  echo.
  echo   Install it from https://nodejs.org  ^(pick the LTS version^),
  echo   then run setup.cmd again.
  echo.
  start "" https://nodejs.org
  pause
  exit /b 1
)

node tools/setup.js

echo.
pause
