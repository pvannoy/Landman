@echo off
setlocal

REM ============================================================
REM  build-exe.bat — Builds Landman.exe
REM
REM  Requirements:
REM    - Java 21+ on PATH
REM    - Launch4j installed (https://launch4j.sourceforge.net)
REM    - Landman.jar already built from Eclipse:
REM        Right-click project > Run As > Maven build... > clean package
REM ============================================================

set PROJECT_DIR=%~dp0
cd /d "%PROJECT_DIR%"

echo ============================================================
echo  Landman EXE Builder
echo  Project: %PROJECT_DIR%
echo ============================================================
echo.

REM ── Step 1: Verify jar exists ────────────────────────────────
echo [1/2] Checking for fat jar...

if exist "target\Landman.jar" (
    echo       Found: target\Landman.jar
    goto :step2
)

REM Jar missing — try Maven if available
echo       target\Landman.jar not found. Trying Maven...
set MVN_CMD=
where mvn >nul 2>&1 && set MVN_CMD=mvn

if "%MVN_CMD%"=="" (
    for /f "delims=" %%i in ('where eclipse 2^>nul') do set ECLIPSE_DIR=%%~dpi
    if defined ECLIPSE_DIR (
        for /r "%ECLIPSE_DIR%" %%f in (mvn.cmd) do (
            if "%MVN_CMD%"=="" set MVN_CMD=%%f
        )
    )
)

if "%MVN_CMD%"=="" (
    echo.
    echo ERROR: target\Landman.jar not found and Maven is not available.
    echo.
    echo Build the jar from Eclipse first:
    echo   Right-click Landman project
    echo   ^> Run As ^> Maven build...
    echo   ^> Goals: clean package
    echo   ^> Click Run
    echo.
    echo Then double-click build-exe.bat again.
    pause
    exit /b 1
)

call "%MVN_CMD%" clean package -q
if errorlevel 1 (
    echo ERROR: Maven build failed.
    pause
    exit /b 1
)

if not exist "target\Landman.jar" (
    echo ERROR: Jar still not found. Check pom.xml includes maven-shade-plugin.
    pause
    exit /b 1
)

:step2
REM ── Step 2: Wrap with Launch4j ───────────────────────────────
echo [2/2] Wrapping jar into EXE with Launch4j...

set L4J_CMD=
if exist "C:\Program Files\Launch4j\launch4jc.exe"     set L4J_CMD=C:\Program Files\Launch4j\launch4jc.exe
if "%L4J_CMD%"=="" if exist "C:\Program Files (x86)\Launch4j\launch4jc.exe" set L4J_CMD=C:\Program Files (x86)\Launch4j\launch4jc.exe

if "%L4J_CMD%"=="" (
    echo ERROR: Launch4j not found. Install from: https://launch4j.sourceforge.net
    pause
    exit /b 1
)

set L4J_CFG=%TEMP%\landman-launch4j.xml
(
echo ^<?xml version="1.0" encoding="UTF-8"?^>
echo ^<launch4jConfig^>
echo   ^<dontWrapJar^>false^</dontWrapJar^>
echo   ^<headerType^>console^</headerType^>
echo   ^<jar^>%PROJECT_DIR%target\Landman.jar^</jar^>
echo   ^<outfile^>%PROJECT_DIR%Landman.exe^</outfile^>
echo   ^<errTitle^>Landman Error^</errTitle^>
echo   ^<cmdLine^>^</cmdLine^>
echo   ^<chdir^>.^</chdir^>
echo   ^<priority^>normal^</priority^>
echo   ^<downloadUrl^>https://adoptium.net^</downloadUrl^>
echo   ^<supportUrl^>^</supportUrl^>
echo   ^<stayAlive^>false^</stayAlive^>
echo   ^<restartOnCrash^>false^</restartOnCrash^>
echo   ^<manifest^>^</manifest^>
echo   ^<jre^>
echo     ^<path^>^</path^>
echo     ^<bundledJre64Bit^>false^</bundledJre64Bit^>
echo     ^<bundledJreAsFallback^>false^</bundledJreAsFallback^>
echo     ^<minVersion^>21^</minVersion^>
echo     ^<maxVersion^>^</maxVersion^>
echo     ^<jdkPreference^>preferJre^</jdkPreference^>
echo     ^<runtimeBits^>64/32^</runtimeBits^>
echo   ^</jre^>
echo ^</launch4jConfig^>
) > "%L4J_CFG%"

"%L4J_CMD%" "%L4J_CFG%"
del "%L4J_CFG%"

if not exist "Landman.exe" (
    echo ERROR: Landman.exe was not created. Check Launch4j output above.
    pause
    exit /b 1
)

echo.
echo ============================================================
echo  SUCCESS: Landman.exe created in:
echo  %PROJECT_DIR%Landman.exe
echo.
echo  NOTE: scraper.py must be in the same folder as Landman.exe
echo ============================================================
echo.
pause
