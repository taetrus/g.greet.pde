@echo off
rem CLI stand-in for Eclipse PDE "Export > Deployable plug-ins and fragments".
rem All logic lives in scripts\BundleBuilder.java (single-file source-launch): it
rem auto-discovers PDE bundle projects and builds each from its own metadata.
rem Args: optional project dirs; with none, every bundle project is built.
cd /d "%~dp0.."
java scripts\BundleBuilder.java %*
exit /b %errorlevel%
