@echo off
REM Flashback 3D - build script
REM NOTE: ASCII only. cmd.exe reads .bat as GBK,
REM       UTF-8 Chinese comments become mojibake and break the line.

setlocal

if not exist "libs" mkdir libs

echo Looking for Flashback jar in libs/ ...
dir /b libs\*.jar 2>nul | findstr . >nul
if errorlevel 1 (
    echo.
    echo [WARN] No jar found in libs/.
   echo        Flashback is ARR licensed and is not on any Maven repo,
    echo        so you must put it there manually.
    echo        Expected: Flashback-0.39.10-for-MC1.21.1.jar
    echo.
)

echo Building...
call gradlew.bat build
if errorlevel 1 (
    echo BUILD FAILED
    exit /b 1
)

echo.
echo Output jar:
dir /b build\libs\*.jar
echo.
echo Do NOT test with runClient - Flashback crashes under modLocalRuntime.
echo Install the jar into a real game instead.

endlocal
