@echo off
rem ============================================================
rem  YunX Desktop - start without installing Java.
rem  Use this if "YunX Desktop.exe" is blocked by Windows Smart App
rem  Control (unsigned app). This script launches the bundled, code-signed
rem  Java launcher (runtime\bin\javaw.exe) - nothing to install.
rem ============================================================
setlocal
cd /d "%~dp0"
set "JAVAW=%~dp0runtime\bin\javaw.exe"
if exist "%JAVAW%" goto run
rem fallback: use a Java from PATH (older packages without bundled JRE)
where javaw >nul 2>nul
if errorlevel 1 goto nojava
set "JAVAW=javaw"
:run
start "" "%JAVAW%" -Djava.library.path="%~dp0app" -Dskiko.library.path="%~dp0app" -cp "%~dp0app\*" com.yunx.desktop.MainKt
exit /b 0
:nojava
echo.
echo Bundled Java runtime not found, and no Java on PATH.
echo Please extract the whole archive before starting (do not run from inside the zip).
echo.
pause
exit /b 1
