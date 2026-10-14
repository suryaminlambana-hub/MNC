@echo off
REM ==========================================================================
REM  Build ulang AUSY di Windows - cukup di-double-click.
REM
REM  Aplikasi AUSY yang masih jalan dari folder ini dihentikan dulu: Windows
REM  mengunci file JAR yang sedang dijalankan, sehingga "mvnw clean package"
REM  gagal dengan "Failed to delete ...\target". Yang dihentikan hanya proses
REM  Java milik AUSY (ausy-*.jar / spring-boot:run AUSY), Java lain tidak.
REM
REM  Hasil: target\ausy-2.0.0.jar dan target\ausy-2.0.0-trigger.jar
REM ==========================================================================
setlocal
cd /d "%~dp0"

echo Menghentikan aplikasi AUSY yang masih jalan (bila ada)...
powershell -NoProfile -Command "$p = Get-CimInstance Win32_Process | Where-Object { $_.Name -in 'java.exe','javaw.exe' -and $_.CommandLine -match 'ausy-[0-9.]+\.jar|com\.mnc\.ausy' }; if (-not $p) { Write-Host '  tidak ada yang jalan' }; foreach ($x in $p) { Write-Host ('  dihentikan: PID ' + $x.ProcessId); Stop-Process -Id $x.ProcessId -Force -ErrorAction SilentlyContinue }"
powershell -NoProfile -Command "Start-Sleep -Seconds 3"

echo.
echo Build (sekitar 1-2 menit)...
REM maven.clean.failOnError=false: folder target yang sedang "ditempati" jendela CMD / File
REM Explorer tidak bisa dihapus foldernya, walau isinya sudah bersih. Itu tidak boleh
REM menggagalkan build - isinya dibuat ulang di bawah. JAR yang terkunci tetap ketahuan saat package.
REM Keluaran tes (mis. skenario "GAGAL" yang memang diuji) disimpan di target\surefire-reports,
REM bukan di layar, supaya tidak terbaca sebagai kegagalan build.
call "%~dp0mvnw.cmd" clean package -Dmaven.clean.failOnError=false -Dmaven.test.redirectTestOutputToFile=true
set "KODE=%ERRORLEVEL%"

echo.
if "%KODE%"=="0" (
  echo BERHASIL. File ada di folder target:
  dir /b target\ausy-*.jar
  echo Jalankan aplikasinya lagi:  java -jar target\ausy-2.0.0.jar
) else (
  echo GAGAL - lihat pesan di atas. Kalau masih "Failed to delete ...\target",
  echo tutup File Explorer / IDE yang sedang membuka folder target, lalu ulangi.
)
if /i not "%~1"=="/nopause" pause
exit /b %KODE%
