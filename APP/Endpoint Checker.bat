@echo off
rem Starts Endpoint Checker without a console window; the browser opens by itself.
rem Stop the program with the Quit button in the page.
setlocal

rem --- OPTIONAL: if endpoint-checker.jar is NOT in the same folder as this file,
rem --- write its full path here, e.g.  set "JAR=C:\Tools\EndpointChecker\endpoint-checker.jar"
#set "JAR=C:\Users\sub\Downloads\endpointChecker\endpointChecker-v3\JAVA\target\endpoint-checker.jar"

if not defined JAR set "JAR=%~dp0endpoint-checker.jar"
if not exist "%JAR%" (
  echo endpoint-checker.jar was not found at:
  echo     %JAR%
  echo Put the jar next to this file, or set the JAR path at the top of this file.
  pause
  exit /b 1
)

set "JAVAW=javaw"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javaw.exe" set "JAVAW=%JAVA_HOME%\bin\javaw.exe"
if "%JAVAW%"=="javaw" (
  where javaw >nul 2>nul
  if errorlevel 1 (
    echo Java was not found. Install a JRE, for example:
    echo     winget install EclipseAdoptium.Temurin.17.JRE
    pause
    exit /b 1
  )
)

rem Start in the jar's folder
for %%I in ("%JAR%") do cd /d "%%~dpI"
start "" "%JAVAW%" -jar "%JAR%"
