@echo off
rem Releases LemonLib: release.bat vX.Y.Z [-DryRun]
rem Bumps every version file, rolls CHANGELOG.md "Unreleased" into the new version, builds the
rem zip, commits, tags, pushes main and creates the GitHub release. See scripts\release.ps1.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\release.ps1" %*
exit /b %errorlevel%
