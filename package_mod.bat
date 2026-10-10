@echo off
rem Builds the jar and packages a release: LemonLib.zip
rem The file list lives in packageMod (build.gradle.kts).
cd /d "%~dp0"
call "%~dp0gradlew.bat" packageMod
pause
exit /b %errorlevel%
