@echo off
chcp 65001 >nul
title Keyviewer_awa - build
cd /d "%~dp0"

echo ============================================
echo   Keyviewer_awa  one-click build (MC 26.3)
echo ============================================
echo.
echo [1/3] Checking Java 25 ...
java -version
if errorlevel 1 (
	echo.
	echo [!] Java not found. Install JDK 25 first.
	pause
	exit /b 1
)

echo.
echo [2/3] Building with Gradle wrapper ...
call gradlew.bat build
if errorlevel 1 (
	echo.
	echo [!] Build failed. See the log above.
	pause
	exit /b 1
)

echo.
echo [3/3] Done. Your mod jar:
dir /b build\libs\*.jar
echo.
echo Copy "Keyviewer_awa-1.1.jar" into .minecraft\mods
echo (Fabric Loader 0.19.5+ and Fabric API 0.161.0+26.3 required)
echo.
pause
