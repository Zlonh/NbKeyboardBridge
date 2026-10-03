@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

REM ===============================================================
REM  One-click build for app-debug.apk
REM
REM  ASCII-only on purpose: cmd.exe parses .cmd files using the OEM
REM  code page, so non-ASCII text in a .cmd breaks the script on some
REM  machines. See README.md (Chinese) for the full instructions.
REM
REM  Requires JDK 17 + Android SDK (Platform 35, Build-Tools,
REM  Platform-Tools). The script reports whatever is missing.
REM
REM  Usage: double-click this file, or run  build-apk.cmd
REM ===============================================================

echo === BLE Key Bridge - build script ===
echo.

REM ================= 1. locate JDK =================
set "JAVA_OK="
if defined JAVA_HOME call :check_javac "%JAVA_HOME%"
if defined JAVA_OK goto jdk_done

for /f "delims=" %%i in ('where javac 2^>nul') do call :check_javac "%%~dpi.."
if defined JAVA_OK goto jdk_done

call :check_javac "%LOCALAPPDATA%\Programs\Android Studio\jbr"
if defined JAVA_OK goto jdk_done

call :check_javac "%ProgramFiles%\Android\Android Studio\jbr"
if defined JAVA_OK goto jdk_done

echo [X] JDK 17 not found. Install one of these, then re-run:
echo.
echo       winget install EclipseAdoptium.Temurin.17.JDK
echo       winget install Google.AndroidStudio     ^(bundles a JBR^)
echo.
pause
exit /b 1

:jdk_done
echo [OK] JDK = %JAVA_HOME%

REM ================= 2. locate Android SDK =================
set "SDK_OK="
call :check_sdk "%ANDROID_HOME%"
call :check_sdk "%ANDROID_SDK_ROOT%"
call :check_sdk "%LOCALAPPDATA%\Android\Sdk"
if defined SDK_OK goto sdk_done

echo [X] Android SDK not found.
echo.
echo     Install Android Studio, then in SDK Manager select:
echo       - Android SDK Platform 35
echo       - Android SDK Build-Tools 35.x
echo       - Android SDK Platform-Tools
echo.
pause
exit /b 1

:sdk_done
echo [OK] Android SDK = %ANDROID_HOME%

if not exist "local.properties" (
  > local.properties echo sdk.dir=!ANDROID_HOME:\=\\!
  echo [OK] wrote local.properties
)

REM ================= 3. Gradle wrapper =================
if exist "gradlew.bat" goto build

echo [..] first run, generating the Gradle wrapper ...
where gradle >nul 2>nul
if errorlevel 1 goto no_gradle
call gradle wrapper --gradle-version 8.9
if errorlevel 1 goto no_gradle
goto build

:no_gradle
echo [X] No Gradle on this machine, cannot generate the wrapper.
echo     Open this folder in Android Studio and let it sync once,
echo     or run:  gradle wrapper --gradle-version 8.9
pause
exit /b 1

REM ================= 4. build =================
:build
echo.
echo === Building app-debug.apk ===
call gradlew.bat assembleDebug --no-daemon
if errorlevel 1 goto build_failed

echo.
echo === BUILD SUCCEEDED ===
if exist "app\build\outputs\apk\debug" (
  for %%f in ("app\build\outputs\apk\debug\*.apk") do echo   %%~ff
)
echo.
echo Install on the phone (enable USB debugging first):
echo   adb install -r app\build\outputs\apk\debug\app-debug.apk
echo.
pause
exit /b 0

:build_failed
echo.
echo [X] Build failed. Copy the compiler output above when asking for help.
pause
exit /b 1

REM ================= helpers =================
:check_javac
if "%~1"=="" exit /b 0
if not exist "%~1\bin\javac.exe" exit /b 0
set "JAVA_HOME=%~1"
set "JAVA_OK=1"
exit /b 0

:check_sdk
if "%~1"=="" exit /b 0
if not exist "%~1\platforms" exit /b 0
set "ANDROID_HOME=%~1"
set "SDK_OK=1"
exit /b 0
