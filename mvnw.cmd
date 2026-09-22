@REM ----------------------------------------------------------------------------
@REM Maven Start Up Batch script
@REM ----------------------------------------------------------------------------
@echo off
setlocal

set "DIRNAME=%~dp0"
if "%DIRNAME%" == "" set "DIRNAME=."
set "APP_BASE_NAME=%~nx0"
set "APP_HOME=%DIRNAME%"

@REM Resolve JAVA_HOME
if defined JAVA_HOME goto findJavaFromJavaHome
set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if "%ERRORLEVEL%" == "0" goto init
echo.
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
goto fail

:findJavaFromJavaHome
set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if exist "%JAVA_EXE%" goto init
echo.
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
goto fail

:init
set "MAVEN_USER_HOME=%USERPROFILE%\.m2"
set "MAVEN_HOME=%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.6"

if exist "%MAVEN_HOME%\bin\mvn.cmd" goto runMvn

echo Downloading Apache Maven 3.9.6 for wrapper...
powershell -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; $dest = '%TEMP%\apache-maven-3.9.6-bin.zip'; Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.6/apache-maven-3.9.6-bin.zip' -OutFile $dest; New-Item -ItemType Directory -Force -Path '%USERPROFILE%\.m2\wrapper\dists' | Out-Null; Expand-Archive -Path $dest -DestinationPath '%USERPROFILE%\.m2\wrapper\dists' -Force; Remove-Item $dest -Force; Move-Item -Path '%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.6*' -Destination '%MAVEN_HOME%' -Force -ErrorAction SilentlyContinue"

:runMvn
if exist "%MAVEN_HOME%\bin\mvn.cmd" (
    call "%MAVEN_HOME%\bin\mvn.cmd" %*
) else (
    echo ERROR: Could not find or download Maven.
    goto fail
)
goto end

:fail
exit /b 1

:end
exit /b 0
