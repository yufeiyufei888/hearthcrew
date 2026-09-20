@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop-user-play.ps1" %*
if errorlevel 1 (
  echo.
  echo HearthCrew stop failed. Read the message above. No unrelated process was touched.
  pause
)
endlocal
