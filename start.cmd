@echo off
cd /d "%~dp0"

if not exist ".env" (
  copy ".env.example" ".env" >nul
  echo.
  echo   Created .env - add your LMS email and password, save, then run start.cmd again.
  echo.
  notepad ".env"
  exit /b 1
)

node --env-file=.env src/server.js
