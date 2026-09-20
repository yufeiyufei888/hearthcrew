@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-user-play.ps1" %*
if errorlevel 1 (
  echo.
  echo HearthCrew start failed. Read the message above, then fix the launcher configuration.
  pause
)
endlocal
