@echo off
setlocal enabledelayedexpansion
rem Boots the three greet bundles inside Equinox + Felix SCR to prove the
rem OBFUSCATED imp bundle still wires up via Declarative Services.
rem Windows counterpart of run-osgi.sh.
rem Expects:
rem   - scripts\build-bundles.bat has produced Deployment\build\*.jar
rem   - mvn -f obfuscation\pom.xml package has produced the obfuscated imp jar
rem
rem Set USE_PLAIN=1 to run the un-obfuscated imp bundle instead (A/B comparison):
rem   set USE_PLAIN=1 && scripts\run-osgi.bat

cd /d "%~dp0.."

set "T=Deployment\target"
set "B=Deployment\build"

rem Resolve target-platform jars by symbolic-name prefix (version-independent).
rem The trailing '_' keeps 'org.osgi.service.component_' from matching
rem 'org.osgi.service.component.annotations_'.
call :pick org.eclipse.osgi_ EQUINOX
call :pick org.osgi.util.function_ FUNCTION
call :pick org.osgi.util.promise_ PROMISE
call :pick org.osgi.service.component_ COMPONENT
call :pick org.apache.felix.scr_ SCR

set "RUN=%B%\run"
if exist "%RUN%" rmdir /s /q "%RUN%"
mkdir "%RUN%\storage"

if "%USE_PLAIN%"=="1" (
  set "IMP=%B%\com.kk.greet.imp.jar"
  echo ^>^> using PLAIN imp bundle: !IMP!
) else (
  set "IMP=obfuscation\target\com.kk.greet.imp-obf.jar"
  echo ^>^> using OBFUSCATED imp bundle: !IMP!
)

echo ^>^> compiling launcher (release 8)
javac --release 8 -cp "%EQUINOX%" -d "%RUN%" scripts\Launcher.java || goto :error

rem Bundle resolve order: OSGi util -^> DS API -^> SCR -^> api -^> imp -^> app
echo ^>^> launching Equinox + Felix SCR
java -cp "%EQUINOX%;%RUN%" Launcher "%RUN%\storage" ^
  "%FUNCTION%" ^
  "%PROMISE%" ^
  "%COMPONENT%" ^
  "%SCR%" ^
  "%B%\com.kk.greet.api.jar" ^
  "!IMP!" ^
  "%B%\com.kk.greet.app.jar"
exit /b %errorlevel%

:pick
rem :pick <symbolic-name-prefix> <output-var>
set "%~2="
for /f "delims=" %%F in ('dir /b "%T%\%~1*.jar"') do (
  if not defined %~2 set "%~2=%T%\%%F"
)
goto :eof

:error
echo RUN FAILED (errorlevel %errorlevel%)
exit /b 1
