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

REM --- Java: dicari sendiri, tidak menuntut PATH sudah benar --------------------------
REM Yang paling sering menghentikan orang di sini bukan Java-nya belum ada, tapi Java ada
REM dan tidak terdaftar di PATH. Dulu pesannya menyuruh "winget install ..." — winget
REM sendiri sering belum terpasang / rusak di Windows 10, dan orang malah sibuk
REM memperbaiki winget padahal AUSY tidak memerlukannya sama sekali.
set "JAVA_EXE="
where java >nul 2>nul && set "JAVA_EXE=java"

if not defined JAVA_EXE if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"

REM JDK portable: cukup unzip JDK ke folder "jdk" di sebelah run.bat — tanpa installer,
REM tanpa hak administrator. Ini jalan keluar paling cepat di laptop yang terkunci.
if not defined JAVA_EXE if exist "%~dp0jdk\bin\java.exe" set "JAVA_EXE=%~dp0jdk\bin\java.exe"

if not defined JAVA_EXE for %%D in (
  "%ProgramFiles%\Eclipse Adoptium"
  "%ProgramFiles%\Java"
  "%ProgramFiles%\Microsoft"
  "%ProgramFiles%\Amazon Corretto"
  "%ProgramFiles%\BellSoft"
  "%ProgramFiles%\Zulu"
) do (
  if not defined JAVA_EXE if exist %%D for /f "delims=" %%J in ('dir /b /ad /o-n %%D 2^>nul') do (
    if not defined JAVA_EXE if exist "%%~D\%%J\bin\java.exe" set "JAVA_EXE=%%~D\%%J\bin\java.exe"
  )
)

if not defined JAVA_EXE (
  echo.
  echo *** Java belum ketemu di komputer ini.
  echo.
  echo     JANGAN pakai winget — AUSY tidak membutuhkannya. Kalau winget error
  echo     ^(0x80073CF9 / Access is denied^), abaikan saja, itu masalah lain.
  echo.
  echo     Pilih SATU:
  echo     [A] Tanpa install ^(tidak perlu admin^):
  echo         1. Buka https://adoptium.net/temurin/releases/?version=21^&os=windows^&arch=x64
  echo         2. Unduh paket .zip JDK 21
  echo         3. Unzip, lalu ganti nama foldernya jadi  jdk  dan taruh di:
  echo            %~dp0
  echo            sampai file ini ada:  %~dp0jdk\bin\java.exe
  echo         4. Jalankan run.bat lagi
  echo.
  echo     [B] Installer biasa: unduh .msi JDK 21 dari alamat yang sama, install,
  echo         tutup jendela ini, buka lagi, jalankan run.bat.
  echo.
  exit /b 1
)

echo Java dipakai       : %JAVA_EXE%
for %%J in ("%JAVA_EXE%") do set "JAVA_DIR=%%~dpJ"
if /i not "%JAVA_EXE%"=="java" (
  REM Berlaku untuk jendela ini saja — PATH sistem tidak disentuh.
  set "PATH=%JAVA_DIR%;%PATH%"
  for %%K in ("%JAVA_DIR%..") do set "JAVA_HOME=%%~fK"
)

REM --- PostgreSQL: dicek sebelum aplikasi jalan, supaya errornya bisa dibaca orang ------
REM Tanpa ini, gagal konek muncul sebagai tumpukan stack trace Java di tengah log.
powershell -NoProfile -Command "exit [int](!(Test-NetConnection -ComputerName 127.0.0.1 -Port 5432 -InformationLevel Quiet))" >nul 2>nul
if errorlevel 1 (
  echo.
  echo *** PostgreSQL tidak menjawab di 127.0.0.1:5432.
  echo     - Belum terpasang?  Unduh installer: https://www.postgresql.org/download/windows/
  echo     - Sudah terpasang?  Nyalakan servicenya:  services.msc  -^>  postgresql-x64-16  -^>  Start
  echo     - Sudah jalan tapi database belum dibuat:  psql -U postgres -f packaging\setup-db.sql
  echo.
  echo     Aplikasi tetap dicoba dijalankan; kalau gagal konek, penyebabnya ini.
  echo.
)

REM JAR dipakai hanya kalau ada DI FOLDER INI (hasil unzip paket deploy). Sengaja tidak melihat
REM target\: di folder repo, JAR lama di sana membuat perubahan yang baru di-pull tidak kelihatan.
for %%f in (ausy-*.jar) do set "JAR=%%f"
if defined JAR (
  echo ^>^> Menjalankan %JAR%
  "%JAVA_EXE%" -jar "%JAR%"
) else (
  echo ^>^> JAR belum ada, jalan lewat Maven ^(mode dev^)
  call mvnw.cmd spring-boot:run
)
endlocal
