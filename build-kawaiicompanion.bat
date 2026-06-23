@echo off
setlocal enabledelayedexpansion
rem ====================================================================
rem  One-click builder for KawaiiCompanion.
rem  Double-click this file. It downloads a real Apache Maven the first
rem  time - the copy bundled in this repo is missing its .jar files, so
rem  it cannot run - then compiles the plugin into a .jar.
rem ====================================================================

set "ROOT=%~dp0"
set "MVNVER=3.9.16"
set "MVNDIR=%ROOT%tools\apache-maven-%MVNVER%"
set "MVNCMD=%MVNDIR%\bin\mvn.cmd"
set "MVNZIP=%ROOT%tools\maven.zip"
set "MVNURL=https://archive.apache.org/dist/maven/maven-3/%MVNVER%/binaries/apache-maven-%MVNVER%-bin.zip"

rem --- Find a JDK (prefer 21; otherwise newest Adoptium installed) ---
if not defined JAVA_HOME for /d %%J in ("C:\Program Files\Eclipse Adoptium\jdk-21*") do set "JAVA_HOME=%%J"
if not defined JAVA_HOME for /d %%J in ("C:\Program Files\Eclipse Adoptium\jdk-*") do set "JAVA_HOME=%%J"
if not defined JAVA_HOME (
  echo [!] Could not find Java. Install JDK 21 from https://adoptium.net then re-run.
  pause
  exit /b 1
)
echo [*] Using Java: %JAVA_HOME%

rem --- Get a working Maven on first run ---
if not exist "%MVNCMD%" (
  echo [*] Downloading Apache Maven %MVNVER% ... one-time setup
  if not exist "%ROOT%tools" mkdir "%ROOT%tools"
  powershell -NoProfile -Command "try { Invoke-WebRequest -Uri '%MVNURL%' -OutFile '%MVNZIP%' } catch { Write-Host $_; exit 1 }"
  if errorlevel 1 (
    echo [!] Download failed. Check your internet and re-run.
    pause
    exit /b 1
  )
  echo [*] Unzipping Maven ...
  powershell -NoProfile -Command "Expand-Archive -Force '%MVNZIP%' '%ROOT%tools'"
  del "%MVNZIP%" >nul 2>&1
)
if not exist "%MVNCMD%" (
  echo [!] Maven still missing after unzip.
  pause
  exit /b 1
)

rem --- Build the plugin ---
echo [*] Building KawaiiCompanion ... the Paper library downloads on first run
call "%MVNCMD%" -B -DskipTests -f "%ROOT%KawaiiCompanion\pom.xml" package
if errorlevel 1 (
  echo.
  echo [!] Build FAILED. Scroll up for the first red [ERROR] line.
  pause
  exit /b 1
)

echo.
echo [OK] Done! Copy this file into your server's plugins folder:
echo      %ROOT%KawaiiCompanion\target\KawaiiCompanion-1.0.0.jar
echo.
pause
