@echo off
REM ============================================================
REM  Build Landman as a Windows .exe using jpackage (JDK 21+)
REM ============================================================
REM
REM  Prerequisites:
REM    - JDK 21+ installed and JAVA_HOME set (jpackage is included)
REM    - Maven is NOT required — the included Maven wrapper (mvnw) is used
REM
REM  Usage:
REM    build-exe.bat
REM
REM  Output:
REM    dist\Landman\        — standalone app folder with Landman.exe
REM    (scraper.py and chromedriver are copied into the folder automatically)
REM ============================================================

setlocal

set APP_NAME=Landman
set APP_VERSION=0.4.0
set MAIN_CLASS=com.landman.Main
set MAIN_JAR=Landman-%APP_VERSION%.jar

REM ── Verify JAVA_HOME ──
if "%JAVA_HOME%"=="" (
    echo [ERROR] JAVA_HOME is not set. Please set it to your JDK 21+ installation.
    exit /b 1
)
echo Using JAVA_HOME: %JAVA_HOME%

echo.
echo ===== Step 1: Build fat JAR with Maven =====
call mvnw.cmd clean package -q
if errorlevel 1 (
    echo [ERROR] Maven build failed.
    exit /b 1
)
echo Fat JAR built: target\%MAIN_JAR%

echo.
echo ===== Step 2: Create app image with jpackage =====
REM Remove old output if present
if exist dist\%APP_NAME% rmdir /s /q dist\%APP_NAME%

"%JAVA_HOME%\bin\jpackage.exe" ^
  --type app-image ^
  --name %APP_NAME% ^
  --input target ^
  --main-jar %MAIN_JAR% ^
  --main-class %MAIN_CLASS% ^
  --dest dist ^
  --java-options "-Xmx512m" ^
  --win-console

if errorlevel 1 (
    echo [ERROR] jpackage failed.
    exit /b 1
)

echo App image created at: dist\%APP_NAME%\

echo.
echo ===== Step 3: Bundle scraper.py and chromedriver =====
REM Copy scraper.py next to the exe so TruePeopleSearchScraper can find it
copy /y scraper.py "dist\%APP_NAME%\scraper.py" >nul

REM Copy chromedriver directory
if exist chromedriver_win32 (
    xcopy /e /i /y chromedriver_win32 "dist\%APP_NAME%\chromedriver_win32" >nul
)

echo.
echo ===== Build complete! =====
echo.
echo Distributable folder:  dist\%APP_NAME%\
echo Executable:            dist\%APP_NAME%\%APP_NAME%.exe
echo.
echo To distribute, zip the entire dist\%APP_NAME% folder.
echo The end user does NOT need Java installed — the JRE is bundled.
echo The end user DOES need Python + undetected-chromedriver installed
echo (for scraper.py).
echo.

endlocal

