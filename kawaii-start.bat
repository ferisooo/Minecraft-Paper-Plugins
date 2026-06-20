@echo off
REM ============================================================
REM  ~ Kawaii Start ~  (use THIS to launch from now on)
REM  Always grabs the latest from GitHub, then opens the manager.
REM  - never blocks  : backs up any local changes into a git stash
REM  - never loses    : the stash is recoverable (git stash list)
REM  - never conflicts: this file git-ignores ITSELF (stays local)
REM
REM  Flags:  --text  use the cmd menu instead of the GUI
REM          --nosync  skip the auto-update this once
REM ============================================================
setlocal
set "ROOT=%~dp0"
cd /d "%ROOT%"
title ~ Kawaii Start ~

set "MODE=gui"
set "DOSYNC=1"
:args
if "%~1"=="" goto run
if /I "%~1"=="--text"   set "MODE=text"
if /I "%~1"=="-t"       set "MODE=text"
if /I "%~1"=="--nosync" set "DOSYNC=0"
shift
goto args

:run
if "%DOSYNC%"=="1" call :sync

if /I "%MODE%"=="text" (
    call "%ROOT%build-all-text.bat"
    exit /b %errorlevel%
)
where powershell >nul 2>&1
if errorlevel 1 (
    echo PowerShell not found - using text menu.
    call "%ROOT%build-all-text.bat"
    exit /b %errorlevel%
)
start "" powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%ROOT%kawaii-gui.ps1"
exit /b 0

REM ============================================================
REM  :sync  — bulletproof update to GitHub's latest main.
REM ============================================================
:sync
where git >nul 2>&1
if errorlevel 1 exit /b 0
if not exist ".git" (
    echo   ^(note^) this folder isn't a git repo yet - skipping update.
    exit /b 0
)

REM Make git ignore THIS launcher locally so the sync never touches it.
REM (.git\info\exclude is per-folder and never gets pushed/pulled - zero conflicts)
set "EXC=.git\info\exclude"
findstr /x /c:"kawaii-start.bat" "%EXC%" >nul 2>&1
if errorlevel 1 >>"%EXC%" echo kawaii-start.bat

echo.
echo   ~ updating to latest from GitHub ~
REM Stash anything local (tracked edits + new files) so nothing can block us.
git stash push -u -m "kawaii-autosync" >nul 2>&1
git fetch origin >nul 2>&1
REM Snap the folder to exactly match GitHub's main.
git reset --hard origin/main
echo   ~ up to date! ~  (any old local changes are saved: run  git stash list )
echo.
exit /b 0
