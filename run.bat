@echo off
REM ============================================================
REM  AUSY — jalankan di Windows (dev / demo).
REM
REM  Padanan packaging\start.sh untuk Windows. Ada karena langkah run manual menuntut orang
REM  mengingat sederet "set ..." setiap kali, dan yang paling mudah terlupa adalah
REM  AUSY_EXPORT_DIR — akibatnya file ACCTXT mendarat di data\txt, bukan di root FTP
REM  (BUG_AUSY_00151). Semua setelan sekarang tinggal di .env, dibaca aplikasi sendiri.
REM
REM  Pakai:  run.bat
REM ============================================================
setlocal
cd /d "%~dp0"

if not exist ".env" (
  echo .env belum ada — dibuatkan dengan setelan dev standar.
  >  .env echo # Setelan AUSY untuk mesin ini. Dibaca otomatis saat aplikasi start.
  >> .env echo DB_NAME=ausy
  >> .env echo DB_USER=ausy_user
  >> .env echo DB_PASS=ausy_pass_2026
  >> .env echo.
  >> .env echo # Folder tempat file ACCTXT hasil export dibuat. Kalau server FTP-nya di mesin ini,
  >> .env echo # arahkan ke root FTP-nya. Pakai garis miring / walau di Windows.
  >> .env echo AUSY_EXPORT_DIR=C:/inetpub/ftproot
  echo.
)

REM --- folder hasil export: pastikan masuk akal sebelum aplikasi jalan ---------------
REM Salah tulis satu huruf (C:\inethub\ftproot untuk C:\inetpub\ftproot) dulu tidak terlihat:
REM folder itu dibuat begitu saja dan hasil export terbit di tempat yang tidak dipantau siapa pun.
set "EXPDIR="
for /f "usebackq tokens=1,* delims==" %%a in (".env") do (
  if /i "%%a"=="AUSY_EXPORT_DIR" set "EXPDIR=%%b"
)

if not defined EXPDIR (
  echo .env belum punya AUSY_EXPORT_DIR — ditambahkan: C:/inetpub/ftproot
  >> .env echo AUSY_EXPORT_DIR=C:/inetpub/ftproot
  set "EXPDIR=C:/inetpub/ftproot"
)

set "EXPDIR=%EXPDIR: =%"

REM inethub -> inetpub: sekali diperbaiki di .env, tidak perlu diingat lagi. Folder salahnya
REM sering sudah terlanjur ada (dibuat versi lama), jadi pengecekan "folder induknya ada" di
REM bawah tidak menangkapnya.
set "EXPFIX=%EXPDIR:inethub=inetpub%"
if /i not "%EXPFIX%"=="%EXPDIR%" (
  echo *** AUSY_EXPORT_DIR salah tulis: %EXPDIR%
  echo     Diperbaiki di .env menjadi %EXPFIX%. Folder inethub yang lama boleh dihapus.
  echo.
  > .env.tmp findstr /v /i /b /c:"AUSY_EXPORT_DIR=" .env
  >> .env.tmp echo AUSY_EXPORT_DIR=%EXPFIX%
  move /y .env.tmp .env >nul
  set "EXPDIR=%EXPFIX%"
)

set "EXPCHK=%EXPFIX:/=\%"
for %%i in ("%EXPCHK%") do set "EXPPARENT=%%~dpi"
if not exist "%EXPCHK%" if not exist "%EXPPARENT%" (
  echo.
  echo *** PERHATIAN: folder hasil export di .env ^-^> %EXPDIR%
  echo     Folder itu belum ada, dan folder induknya ^(%EXPPARENT%^) juga tidak ada.
  echo     Biasanya ini salah tulis.
  if exist "C:\inetpub\" (
    echo     Untuk run ini dipakai C:/inetpub/ftproot. Perbaiki baris AUSY_EXPORT_DIR di .env
    echo     supaya tidak perlu diperbaiki lagi tiap kali.
    set "AUSY_EXPORT_DIR=C:/inetpub/ftproot"
    set "EXPDIR=C:/inetpub/ftproot"
  ) else (
    echo     Perbaiki baris AUSY_EXPORT_DIR di .env lebih dulu.
  )
  echo.
)

echo === Setelan yang dipakai (.env) ===
type .env | findstr /v "^#" | findstr /v "PASS="
echo Folder hasil export : %EXPDIR%
echo.

where java >nul 2>nul
if errorlevel 1 (
  echo ERROR: Java tidak ditemukan. Install JDK 21:  winget install EclipseAdoptium.Temurin.21.JDK
  exit /b 1
)

REM JAR dipakai hanya kalau ada DI FOLDER INI (hasil unzip paket deploy). Sengaja tidak melihat
REM target\: di folder repo, JAR lama di sana membuat perubahan yang baru di-pull tidak kelihatan.
for %%f in (ausy-*.jar) do set "JAR=%%f"
if defined JAR (
  echo ^>^> Menjalankan %JAR%
  java -jar "%JAR%"
) else (
  echo ^>^> JAR belum ada, jalan lewat Maven ^(mode dev^)
  call mvnw.cmd spring-boot:run
)
endlocal
