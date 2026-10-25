@echo off
rem ModpackCI wrapper. Downloads Java 25 and the ModpackCI version pinned in
rem modpackw.properties, then runs it. Commit this file, like gradlew.bat.
setlocal

set "DIR=%~dp0"
set "DIR=%DIR:~0,-1%"
set "VERSION="
set "REPO="
for /f "usebackq tokens=1,* delims==" %%a in ("%DIR%\modpackw.properties") do (
    if /i "%%a"=="version" set "VERSION=%%b"
    if /i "%%a"=="repository" set "REPO=%%b"
)
if not defined VERSION (
    echo modpackw: no version in %DIR%\modpackw.properties 1>&2
    exit /b 1
)
if not defined REPO set "REPO=AngelicIndustries/ModpackCI"
if not defined MODPACKCI_HOME set "MODPACKCI_HOME=%USERPROFILE%\.modpackci"

set "ARCH=x64"
if /i "%PROCESSOR_ARCHITECTURE%"=="ARM64" set "ARCH=aarch64"

set "JRE=%MODPACKCI_HOME%\jre\zulu-25-windows-%ARCH%"
set "JAVA="
if exist "%JRE%" call :findjava
if defined JAVA goto havejava
echo modpackw: downloading Java 25 1>&2
if exist "%JRE%" rmdir /s /q "%JRE%"
mkdir "%JRE%"
curl -fsSL -o "%JRE%\jre.zip" "https://api.foojay.io/disco/v3.0/directuris?distro=zulu&version=25&package_type=jre&javafx_bundled=false&latest=available&operating_system=windows&architecture=%ARCH%&archive_type=zip" || goto fail
tar -xf "%JRE%\jre.zip" -C "%JRE%" || goto fail
del "%JRE%\jre.zip"
call :findjava
if not defined JAVA goto fail
:havejava

if defined MODPACKCI_JAR (set "JAR=%MODPACKCI_JAR%") else (set "JAR=%MODPACKCI_HOME%\dists\%VERSION%\modpackci-%VERSION%.jar")
if exist "%JAR%" goto havejar
echo modpackw: downloading ModpackCI %VERSION% 1>&2
if not exist "%MODPACKCI_HOME%\dists\%VERSION%" mkdir "%MODPACKCI_HOME%\dists\%VERSION%"
curl -fsSL -o "%JAR%.part" "https://github.com/%REPO%/releases/download/v%VERSION%/modpackci-%VERSION%.jar" || goto fail
move /y "%JAR%.part" "%JAR%" >nul
:havejar

set "MODPACKCI_PROJECT_DIR=%DIR%"
"%JAVA%" -jar "%JAR%" %*
exit /b %ERRORLEVEL%

:findjava
for /f "delims=" %%i in ('dir /b /s "%JRE%\java.exe" 2^>nul') do if not defined JAVA set "JAVA=%%i"
exit /b 0

:fail
echo modpackw: download failed 1>&2
exit /b 1
