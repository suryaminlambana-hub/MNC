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
  >> .env echo.
  >> .env echo # Kirim ke server FTP di mesin LAIN: isi tiga baris di bawah. Kosong = push FTP mati.
  >> .env echo FTP_HOST=
  >> .env echo FTP_USER=
  >> .env echo FTP_PASS=
  echo.
)

echo === Setelan yang dipakai (.env) ===
type .env | findstr /v "^#" | findstr /v "PASS="
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
