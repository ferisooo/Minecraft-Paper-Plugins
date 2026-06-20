@echo off
REM ~ one-time fix: let GitHub's kawaii-gui.ps1 win, then pull ~
REM (the repo's already set up, so the local patch isn't needed anymore)
REM Your other local changes (like KawaiiSparkles) are NOT touched.
cd /d "%~dp0"

echo.
echo  Taking GitHub's version of kawaii-gui.ps1...
git checkout -- kawaii-gui.ps1

echo.
echo  Pulling latest from origin/main...
git pull origin main

echo.
echo  (^_^)/  done! your KawaiiSparkles edits were left alone.
echo.
pause
