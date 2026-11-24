@echo off
setlocal
set "APP_DIR=%~dp0"
if exist "%APP_DIR%dist-minimal\NetPulse\NetPulse.exe" (
  start "" "%APP_DIR%dist-minimal\NetPulse\NetPulse.exe"
  exit /b 0
)
if not exist "%APP_DIR%build\input\NetPulse.jar" (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%APP_DIR%build.ps1"
  if errorlevel 1 (
    pause
    exit /b 1
  )
)
where java >nul 2>nul
if errorlevel 1 (
  echo Java was not found. Run build.ps1 with a JDK installed to create the self-contained app.
  pause
  exit /b 1
)
start "" javaw -jar "%APP_DIR%build\input\NetPulse.jar"