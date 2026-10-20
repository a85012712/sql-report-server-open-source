@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo.
echo ========================================
echo   HIS报表查询系统 - 启动前自检
echo ========================================
echo.

set "HAS_ERROR=0"

REM 1. Java 可用性
java -version >nul 2>&1
if errorlevel 1 (
    echo [FAIL] 未找到 Java，请安装 JDK 21+
    set "HAS_ERROR=1"
) else (
    echo [OK] Java 可用
)

REM 2. 主程序 jar（自动取最新的 sql-report-server-*.jar，规则与 启动服务.bat 一致）
set "APP_JAR="
for /f "delims=" %%F in ('dir /b /a-d /o-d "sql-report-server-*.jar" 2^>nul') do (
    if not defined APP_JAR set "APP_JAR=%%F"
)
if not defined APP_JAR (
    echo [FAIL] 未找到程序包 sql-report-server-*.jar
    set "HAS_ERROR=1"
) else (
    echo [OK] 主程序 jar 存在: !APP_JAR!
)

REM 3. 加载 .env，检查必需变量（与 启动服务.bat 保持一致）
if exist ".env" (
    for /f "usebackq tokens=1,* delims==" %%a in (".env") do (
        set "k=%%a"
        if not "!k:~0,1!"=="#" if not "!k!"=="" set "%%a=%%b"
    )
    echo [OK] 已加载 .env
) else (
    echo [WARN] 未找到 .env，将仅使用系统环境变量
)

REM 4. ADMIN_PASSWORD 是启动硬性要求（缺失时应用直接拒绝启动）
if not defined ADMIN_PASSWORD (
    echo [FAIL] 未配置 ADMIN_PASSWORD（管理员密码），系统将无法启动
    set "HAS_ERROR=1"
) else (
    echo [OK] ADMIN_PASSWORD 已配置
)

REM 5. 数据库连接来源：优先 data\datasources.json；缺失时才依赖 DB_URL 环境变量
if exist "data\datasources.json" (
    echo [OK] 数据源配置文件存在（data\datasources.json）
) else if not defined DB_URL (
    echo [FAIL] 既无 data\datasources.json 也未配置 DB_URL —— 没有任何可连的数据源
    set "HAS_ERROR=1"
) else (
    echo [OK] DB_URL 已配置（datasources.json 缺失时的备用连接）
)

echo.
if "!HAS_ERROR!"=="1" (
    echo ========================================
    echo   自检未通过，请先解决上面的 FAIL 项
    echo ========================================
    echo.
    pause
    exit /b 1
) else (
    echo ========================================
    echo   自检通过，可以启动服务
    echo ========================================
    echo.
    pause
    exit /b 0
)
