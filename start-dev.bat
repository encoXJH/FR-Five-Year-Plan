@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 437 >nul
title FZSU Five-Year Plan Dev Launcher
mode con: cols=92 lines=34
cd /d "%~dp0"
if /i "%~1"=="--backend" goto backendService

for /f %%a in ('echo prompt $E ^| cmd') do set "ESC=%%a"
set "RESET=%ESC%[0m"
set "CYAN=%ESC%[96m"
set "BLUE=%ESC%[94m"
set "GREEN=%ESC%[92m"
set "YELLOW=%ESC%[93m"
set "RED=%ESC%[91m"
set "DIM=%ESC%[90m"

call :banner

echo %CYAN%[01/05]%RESET% Checking project configuration
if not exist "backend\mvnw.cmd" set "ERROR=backend\mvnw.cmd not found. Put this script in the project root." && goto fail
if not exist "frontend\package.json" set "ERROR=frontend\package.json not found. Put this script in the project root." && goto fail

if not exist ".env" call :initEnv
if errorlevel 1 goto fail
call :loadEnv
if not defined SERVER_PORT set "SERVER_PORT=8081"
if not defined DB_PORT set "DB_PORT=3306"
if not defined DB_HOST set "DB_HOST=localhost"
echo       %DIM%.env loaded. Secrets are hidden from this launcher.%RESET%

echo.
echo %CYAN%[02/05]%RESET% Checking Java, Node.js and frontend dependencies
where java >nul 2>&1 || (set "ERROR=Java not found. Install JDK 17 and configure PATH." && goto fail)
where node >nul 2>&1 || (set "ERROR=Node.js not found. Install Node.js 20+ and configure PATH." && goto fail)
where npm >nul 2>&1 || (set "ERROR=npm not found. Check your Node.js installation." && goto fail)
for /f "tokens=1" %%v in ('node -v') do echo       Node.js %%v
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do echo       Java %%~v
if not exist "frontend\node_modules" (
    echo       %YELLOW%First run: installing frontend dependencies...%RESET%
    pushd frontend
    if exist "package-lock.json" (call npm ci) else (call npm install)
    if errorlevel 1 (
        set "ERROR=Frontend dependency installation failed. Check npm network settings."
        popd
        goto fail
    )
    popd
)
echo       %GREEN%Runtime checks passed.%RESET%

echo.
echo %CYAN%[03/05]%RESET% Checking MySQL
call :portOpen %DB_PORT%
if errorlevel 1 (
    echo       %YELLOW%MySQL is not listening on %DB_PORT%. Trying Docker...%RESET%
    where docker >nul 2>&1
    if errorlevel 1 (
        set "ERROR=MySQL is stopped and Docker was not found. Start MySQL and retry."
        goto fail
    )
    docker compose up -d mysql
    if errorlevel 1 (
        set "ERROR=Docker MySQL startup failed. Check whether Docker Desktop is running."
        goto fail
    )
    call :waitPort %DB_PORT% 45
    if errorlevel 1 (
        set "ERROR=Timed out waiting for MySQL. Check the database logs."
        goto fail
    )
)
echo       %GREEN%MySQL is ready.%RESET%

echo.
echo %CYAN%[04/05]%RESET% Starting Spring Boot backend
set "BACKEND_LOG=%TEMP%\fzsu-backend-dev.log"
call :portOpen %SERVER_PORT%
if errorlevel 1 (
    if exist "%BACKEND_LOG%" del "%BACKEND_LOG%" >nul 2>&1
    start "FZSU Backend" /D "%~dp0backend" cmd /k ""%~f0" --backend"
    call :waitPort %SERVER_PORT% 90
    if errorlevel 1 (
        set "ERROR=Backend startup timed out. Check the FZSU Backend window."
        goto fail
    )
    echo       %GREEN%Backend started: http://localhost:%SERVER_PORT%%RESET%
) else (
    echo       %YELLOW%Backend is already running. Skipping.%RESET%
)

echo.
echo %CYAN%[05/05]%RESET% Starting Vue 3 frontend
call :portOpen 5173
if errorlevel 1 (
    start "FZSU Frontend" cmd /k "color 0B && title FZSU Frontend && cd /d "%~dp0frontend" && npm run dev -- --host 0.0.0.0"
    call :waitPort 5173 30
    if errorlevel 1 (
        set "ERROR=Frontend startup timed out. Check the FZSU Frontend window."
        goto fail
    )
) else (
    echo       %YELLOW%Frontend is already running. Skipping.%RESET%
)

echo.
echo %GREEN%  +============================================================+%RESET%
echo %GREEN%  ^|             DEVELOPMENT ENVIRONMENT IS READY             ^|%RESET%
echo %GREEN%  +============================================================+%RESET%
echo.
echo       %CYAN%Frontend%RESET%  http://localhost:5173
echo       %CYAN%Backend %RESET%  http://localhost:%SERVER_PORT%
echo       %CYAN%Swagger %RESET%  http://localhost:%SERVER_PORT%/swagger-ui.html
echo       %DIM%Admin: admin / Test@123456%RESET%
echo.
start "" "http://localhost:5173"
echo %DIM%Press any key to close this launcher. Services will keep running.%RESET%
pause >nul
exit /b 0

:backendService
cd /d "%~dp0backend"
color 0A
title FZSU Backend
echo FZSU Spring Boot backend - log: %BACKEND_LOG%
echo.
call mvnw.cmd spring-boot:run 2>&1 | powershell -NoProfile -Command "$input | Tee-Object -FilePath $env:BACKEND_LOG"
echo.
echo Backend stopped. See %BACKEND_LOG%
pause
exit /b 1

:initEnv
echo       %YELLOW%First run: creating local .env. It is ignored by Git.%RESET%
set "DB_PASSWORD="
set /p "DB_PASSWORD=Enter MySQL root password [Metrics@2026]: "
if not defined DB_PASSWORD set "DB_PASSWORD=Metrics@2026"
for /f %%i in ('powershell -NoProfile -Command "[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N')"') do set "JWT_SECRET=%%i"
for /f %%i in ('powershell -NoProfile -Command "[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N')"') do set "FILE_SIGN_SECRET=%%i"
(
    echo DB_HOST=localhost
    echo DB_PORT=3306
    echo DB_NAME=metrics_db
    echo DB_USER=root
    echo DB_PASSWORD=!DB_PASSWORD!
    echo JWT_SECRET=!JWT_SECRET!
    echo FILE_SIGN_SECRET=!FILE_SIGN_SECRET!
    echo FILE_STORAGE_TYPE=LOCAL
    echo FILE_STORAGE_DIR=./data/files
    echo SERVER_PORT=8081
    echo SWAGGER_ENABLED=true
) > ".env"
if errorlevel 1 (
    set "ERROR=Cannot write .env. Check project directory permissions."
    exit /b 1
)
echo       %GREEN%.env created. You will not be asked again.%RESET%
exit /b 0

:loadEnv
for /f "usebackq eol=# tokens=1,* delims==" %%A in (".env") do (
    if not "%%A"=="" set "%%A=%%B"
)
exit /b 0

:portOpen
powershell -NoProfile -Command "try { $c = New-Object System.Net.Sockets.TcpClient; $c.Connect('127.0.0.1', %1); $c.Close(); exit 0 } catch { exit 1 }" >nul 2>&1
exit /b %errorlevel%

:waitPort
set /a tries=0
:waitLoop
call :portOpen %1
if !errorlevel! equ 0 exit /b 0
set /a tries+=1
if !tries! geq %2 exit /b 1
powershell -NoProfile -Command "Start-Sleep -Seconds 1" >nul
goto waitLoop

:banner
cls
echo %CYAN%  +============================================================+%RESET%
echo %CYAN%  ^|%BLUE%              F Z S U   D E V   L A U N C H E R              %CYAN%^|%RESET%
echo %CYAN%  +============================================================+%RESET%
echo %CYAN%  ^|%RESET%       FIVE-YEAR PLAN  //  ONE-CLICK DEV STARTER          %CYAN%^|%RESET%
echo %CYAN%  +============================================================+%RESET%
echo.
exit /b 0

:fail
echo.
echo %RED%  +============================================================+%RESET%
echo %RED%  ^|                      STARTUP FAILED                       ^|%RESET%
echo %RED%  +============================================================+%RESET%
echo.
echo %RED%%ERROR%%RESET%
echo.
pause
exit /b 1
