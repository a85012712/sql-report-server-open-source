@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul 2>nul
cd /d "%~dp0"

echo.
echo ========================================
echo   HIS报表查询系统 - 启动服务
echo ========================================
echo.

REM ========== 优先使用 JDK 21 ==========
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" goto :java_ok

REM 依次尝试常见的 Temurin / Oracle JDK 21 安装目录
for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-21*") do (
    if exist "%%~D\bin\java.exe" (
        set "JAVA_HOME=%%~D"
        goto :java_ok
    )
)

for /d %%D in ("C:\Program Files\Java\jdk-21*") do (
    if exist "%%~D\bin\java.exe" (
        set "JAVA_HOME=%%~D"
        goto :java_ok
    )
)

for /d %%D in ("C:\Program Files\Microsoft\jdk-21*") do (
    if exist "%%~D\bin\java.exe" (
        set "JAVA_HOME=%%~D"
        goto :java_ok
    )
)

:java_ok
if defined JAVA_HOME (
    set "PATH=%JAVA_HOME%\bin;%PATH%"
)

where java >nul 2>nul
if errorlevel 1 (
    echo [ERROR] 未找到 Java，请安装 JDK 21+
    pause
    exit /b 1
)

echo [OK] Java:
java -version 2>&1 | findstr /i "version"
if defined JAVA_HOME echo [OK] JAVA_HOME=%JAVA_HOME%

set "APP_HOME=%~dp0"
if "%APP_HOME:~-1%"=="\" set "APP_HOME=%APP_HOME:~0,-1%"

REM ========== 加载应用主密钥（用于解密 .env / data 中 enc: 密文） ==========
REM 必须早于 .env 加载：.env 中的 DB_PASSWORD / ADMIN_PASSWORD 可能是 enc: 密文
if exist "%APP_HOME%\secret.key" (
    echo [OK] 加载密钥文件: %APP_HOME%\secret.key
    for /f "usebackq tokens=1,* delims==" %%a in ("%APP_HOME%\secret.key") do (
        set "line=%%a"
        if not "!line:~0,1!"=="#" if not "!line!"=="" (
            set "%%a=%%b"
        )
    )
) else (
    echo [WARN] 未找到 secret.key；若 .env 中存在 enc: 密文将无法解密
)

REM ========== 加载 .env 环境变量文件 ==========
if exist "%APP_HOME%\.env" (
    echo [OK] 加载环境变量: %APP_HOME%\.env
    for /f "usebackq tokens=1,* delims==" %%a in ("%APP_HOME%\.env") do (
        set "line=%%a"
        if not "!line:~0,1!"=="#" if not "!line!"=="" (
            set "%%a=%%b"
        )
    )
) else (
    echo [WARN] 未找到 .env 文件
)

if not defined SERVER_PORT set "SERVER_PORT=8090"
if not exist "%APP_HOME%\logs" mkdir "%APP_HOME%\logs"
if not exist "%APP_HOME%\data" mkdir "%APP_HOME%\data"
if not exist "%APP_HOME%\scripts" mkdir "%APP_HOME%\scripts"

echo [OK] 应用目录: %APP_HOME%

REM ========== 选取待启动程序包：自动取最新的 sql-report-server-*.jar ==========
REM 按修改时间倒序取第一个，因此每次迭代新增版本包后无需修改本脚本。
set "APP_JAR="
for /f "delims=" %%F in ('dir /b /a-d /o-d "%APP_HOME%\sql-report-server-*.jar" 2^>nul') do (
    if not defined APP_JAR set "APP_JAR=%APP_HOME%\%%F"
)
if not defined APP_JAR (
    echo [ERROR] 未找到程序包 sql-report-server-*.jar，请确认部署目录完整
    pause
    exit /b 1
)
echo [OK] 程序包: %APP_JAR%
echo.
echo 管理员: admin
echo.
echo 访问地址: http://localhost:%SERVER_PORT%
echo 按 Ctrl+C 停止服务
echo.
echo ========================================
echo.

java -Xms1g -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:InitiatingHeapOccupancyPercent=35 -XX:G1ReservePercent=15 -XX:+UseStringDeduplication -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./logs/ -Xlog:gc*:file=./logs/gc.log:time,uptime,level,tags:filecount=5,filesize=10M -Dfile.encoding=UTF-8 "-DAPP_LOG_DIR=%APP_HOME%\logs" "-DAPP_HOME=%APP_HOME%" -jar "%APP_JAR%"

echo.
echo 服务已停止
pause
