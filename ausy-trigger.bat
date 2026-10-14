@echo off
REM ==========================================================================
REM  AUSY - pemicu Generate File ACCTXT (REQ_AUSY_00058), untuk Windows.
REM  Cukup di-double-click. Menjalankan target\ausy-2.0.0-trigger.jar; setelan
REM  (AUSY_TRIGGER_TOKEN, port) dibaca otomatis dari .env di folder ini.
REM  Aplikasi AUSY harus sedang jalan.
REM  Dari Task Scheduler: ausy-trigger.bat /nopause
REM ==========================================================================
setlocal
set "DIR=%~dp0"
set "JAR="
for %%f in ("%DIR%target\ausy-*-trigger.jar") do set "JAR=%%~f"
if not defined JAR if exist "%DIR%ausy-trigger.jar" set "JAR=%DIR%ausy-trigger.jar"
if not defined JAR (
  echo File ausy-trigger.jar tidak ditemukan.
  echo Build dulu di folder ini:  mvnw.cmd clean package
  set "KODE=2"
  goto selesai
)
pushd "%DIR%"
java -jar "%JAR%" %*
set "KODE=%ERRORLEVEL%"
popd

:selesai
echo.
echo Kode keluar: %KODE%   (0 = berhasil / penampungan kosong, 1 = generate gagal, 2 = aplikasi tidak terhubung / token salah)
if /i not "%~1"=="/nopause" pause
exit /b %KODE%
