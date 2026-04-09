@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "PS_EXE="
where pwsh >nul 2>nul && set "PS_EXE=pwsh"
if not defined PS_EXE (
  where powershell >nul 2>nul && set "PS_EXE=powershell"
)
if not defined PS_EXE (
  echo [MindOS] Neither pwsh nor powershell was found on PATH.
  exit /b 1
)

set "BASE_URL="
set "TIMEOUT_MS="
set "SKIP_POST_RESTART_CHECK="
set "DRY_RUN="

:parse_args
if "%~1"=="" goto run_script
if /I "%~1"=="-BaseUrl" (
  shift
  if "%~1"=="" (
    echo [MindOS] Missing value for -BaseUrl.
    exit /b 1
  )
  set "BASE_URL=%~1"
  shift
  goto parse_args
)
if /I "%~1"=="-TimeoutMs" (
  shift
  if "%~1"=="" (
    echo [MindOS] Missing value for -TimeoutMs.
    exit /b 1
  )
  set "TIMEOUT_MS=%~1"
  shift
  goto parse_args
)
if /I "%~1"=="-SkipPostRestartCheck" (
  set "SKIP_POST_RESTART_CHECK=-SkipPostRestartCheck"
  shift
  goto parse_args
)
if /I "%~1"=="-DryRun" (
  set "DRY_RUN=-DryRun"
  shift
  goto parse_args
)
echo [MindOS] Unsupported argument: %~1
echo [MindOS] Supported arguments: -BaseUrl ^<url^> -TimeoutMs ^<ms^> -SkipPostRestartCheck -DryRun
exit /b 1

:run_script
if defined BASE_URL (
  if defined TIMEOUT_MS (
    %PS_EXE% -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%upgrade-smooth.ps1" -BaseUrl "%BASE_URL%" -TimeoutMs %TIMEOUT_MS% %SKIP_POST_RESTART_CHECK% %DRY_RUN%
  ) else (
    %PS_EXE% -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%upgrade-smooth.ps1" -BaseUrl "%BASE_URL%" %SKIP_POST_RESTART_CHECK% %DRY_RUN%
  )
) else (
  if defined TIMEOUT_MS (
    %PS_EXE% -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%upgrade-smooth.ps1" -TimeoutMs %TIMEOUT_MS% %SKIP_POST_RESTART_CHECK% %DRY_RUN%
  ) else (
    %PS_EXE% -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%upgrade-smooth.ps1" %SKIP_POST_RESTART_CHECK% %DRY_RUN%
  )
)
set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" (
  echo [MindOS] upgrade-smooth failed with exit code %EXIT_CODE%.
)
exit /b %EXIT_CODE%

