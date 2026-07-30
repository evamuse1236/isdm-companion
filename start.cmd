@echo off
title ISDM Companion
cd /d "%~dp0"

if not exist ".env" (
  echo.
  echo   First run - setting you up.
  echo.
  call "%~dp0setup.cmd"
  exit /b
)

echo.
echo   Starting ISDM Companion...
echo   Dashboard: http://localhost:4321
echo   Keep this window open. Close it or press Ctrl+C to stop.
echo.

set OPEN_BROWSER=1
node --env-file=.env src/server.js

echo.
echo   Server stopped.
pause
