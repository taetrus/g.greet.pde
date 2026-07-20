@echo off
setlocal enabledelayedexpansion
rem Boots the three greet bundles inside Equinox + Felix SCR, with the Gogo shell
rem and Equinox console, so you can observe bundle + DS status interactively.
rem Mirrors Deployment\launch\greet.launch (the Eclipse Equinox launch config).
rem On startup it prints the three DS lines, then drops to a console prompt:
rem   lb                list bundles            ss          short bundle status
rem   scr:list          list DS components      scr:info N  component detail
rem   close             stop the framework (answer y to confirm)
rem Windows counterpart of run-osgi.sh.
rem Expects:
rem   - scripts\build-bundles.bat has produced Deployment\build\*.jar
rem   - mvn -f obfuscation\pom.xml package has produced the obfuscated bundle jars
rem
rem Env:
rem   USE_PLAIN=1       run the un-obfuscated bundles (A/B comparison)
rem   GREET_MODE=check  non-interactive: activate DS, print, exit (for scripted checks)
rem   GREET_LANG=tr^|en  UI language; picks configs\com.kk.greet.ui\lang\messages_^<lang^>.properties
rem                     (default en; unknown values fall back to English)
rem   e.g.  set USE_PLAIN=1 && scripts\run-osgi.bat

cd /d "%~dp0.."

set "T=Deployment\target"
set "B=Deployment\build"

rem Resolve target-platform jars by symbolic-name prefix (version-independent).
rem The trailing '_' keeps 'org.osgi.service.component_' from matching
rem 'org.osgi.service.component.annotations_'.
call :pick org.eclipse.osgi_ EQUINOX
call :pick org.apache.felix.gogo.runtime_ GOGORT
call :pick org.apache.felix.gogo.command_ GOGOCMD
call :pick org.apache.felix.gogo.shell_ GOGOSH
call :pick org.eclipse.equinox.console_ CONSOLE
call :pick org.osgi.util.function_ FUNCTION
call :pick org.osgi.util.promise_ PROMISE
call :pick org.osgi.service.component_ COMPONENT
call :pick org.osgi.service.component.annotations_ ANNOTATIONS
call :pick org.apache.felix.scr_ SCR

set "RUN=%B%\run"
if exist "%RUN%" rmdir /s /q "%RUN%"
mkdir "%RUN%\storage"

call :pickbundle com.kk.greet.imp IMP
call :pickbundle com.kk.greet.app APP
call :pickbundle com.kk.greet.ui UI

echo ^>^> compiling launcher (release 8)
javac --release 8 -cp "%EQUINOX%" -d "%RUN%" scripts\Launcher.java || goto :error

rem Bundle set mirrors Deployment\launch\greet.launch: Gogo + console for
rem observability, OSGi util + DS API + SCR, then the three greet bundles.
echo ^>^> launching Equinox + Felix SCR + Gogo console
java -cp "%EQUINOX%;%RUN%" Launcher "%RUN%\storage" ^
  "%GOGORT%" ^
  "%GOGOCMD%" ^
  "%GOGOSH%" ^
  "%CONSOLE%" ^
  "%FUNCTION%" ^
  "%PROMISE%" ^
  "%COMPONENT%" ^
  "%ANNOTATIONS%" ^
  "%SCR%" ^
  "%B%\com.kk.greet.api.jar" ^
  "!IMP!" ^
  "!APP!" ^
  "!UI!"
exit /b %errorlevel%

:pickbundle
rem :pickbundle <bundle-symbolic-name> <output-var>
rem Picks the obfuscated or plain jar for one greet bundle. Falls back to the
rem plain jar (with a note) if the obfuscated one hasn't been built yet.
set "_OBF=obfuscation\target\%~1-obf.jar"
if "%USE_PLAIN%"=="1" (
  set "%~2=%B%\%~1.jar"
  echo ^>^> using PLAIN %~1: %B%\%~1.jar
) else if exist "%_OBF%" (
  set "%~2=%_OBF%"
  echo ^>^> using OBFUSCATED %~1: %_OBF%
) else (
  set "%~2=%B%\%~1.jar"
  echo ^>^> NOTE: %_OBF% not found, falling back to PLAIN %~1
)
goto :eof

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
