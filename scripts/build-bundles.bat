@echo off
setlocal enabledelayedexpansion
rem CLI stand-in for Eclipse PDE's "Export > Deployable plug-ins and fragments".
rem Compiles api + imp + app at Java 1.8 bytecode and assembles OSGi bundle JARs
rem (MANIFEST.MF + OSGI-INF) into Deployment\build\. Windows counterpart of build-bundles.sh.
rem
rem Java 1.8 compatibility is enforced via `javac --release 8`.

cd /d "%~dp0.."

set "OUT=Deployment\build"

rem Resolve the DS annotations jar by prefix (version-independent).
set "ANNOTATIONS="
for /f "delims=" %%F in ('dir /b "Deployment\target\org.osgi.service.component.annotations_*.jar"') do (
  if not defined ANNOTATIONS set "ANNOTATIONS=Deployment\target\%%F"
)

if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%\api-classes"
mkdir "%OUT%\imp-classes"
mkdir "%OUT%\app-classes"

echo ^>^> compiling com.kk.greet.api (release 8)
javac --release 8 -d "%OUT%\api-classes" ^
  com.kk.greet.api\src\com\kk\greet\api\IGreet.java || goto :error

echo ^>^> compiling com.kk.greet.imp (release 8)
rem cmd does not expand *.java, so list the imp sources via an argfile.
dir /b /s "com.kk.greet.imp\src\*.java" > "%OUT%\imp-sources.txt"
javac --release 8 -cp "%OUT%\api-classes;%ANNOTATIONS%" -d "%OUT%\imp-classes" ^
  "@%OUT%\imp-sources.txt" || goto :error

echo ^>^> compiling com.kk.greet.app (release 8)
javac --release 8 -cp "%OUT%\api-classes;%ANNOTATIONS%" -d "%OUT%\app-classes" ^
  com.kk.greet.app\src\com\kk\greet\app\App.java || goto :error

echo ^>^> assembling bundle jars
jar cfm "%OUT%\com.kk.greet.api.jar" com.kk.greet.api\META-INF\MANIFEST.MF ^
  -C "%OUT%\api-classes" . || goto :error

mkdir "%OUT%\app-classes\OSGI-INF"
copy /y com.kk.greet.app\OSGI-INF\com.kk.greet.app.App.xml "%OUT%\app-classes\OSGI-INF\" >nul
jar cfm "%OUT%\com.kk.greet.app.jar" com.kk.greet.app\META-INF\MANIFEST.MF ^
  -C "%OUT%\app-classes" . || goto :error

rem imp bundle must carry its DS descriptor under OSGI-INF\, exactly as PDE ships it.
mkdir "%OUT%\imp-classes\OSGI-INF"
copy /y com.kk.greet.imp\OSGI-INF\com.kk.greet.imp.Greet.xml "%OUT%\imp-classes\OSGI-INF\" >nul
jar cfm "%OUT%\com.kk.greet.imp.jar" com.kk.greet.imp\META-INF\MANIFEST.MF ^
  -C "%OUT%\imp-classes" . || goto :error

echo ^>^> done:
dir /b "%OUT%\*.jar"
exit /b 0

:error
echo BUILD FAILED (errorlevel %errorlevel%)
exit /b 1
