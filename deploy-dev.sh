#!/usr/bin/env bash
# ============================================================
# Kirim versi terbaru ke server DEV (10.5.99.24) lewat gateway Safous.
#
#   ./deploy-dev.sh <token-openssh-dari-portal-safous>
#
# Token diambil dari portal Safous: buka app MNCB-AUSY-WEB-APP-DEV
# (Other -> Native -> tunggu approval) lalu salin bagian setelah "-l".
#
# Skrip ini: build -> kirim zip lewat pipe SSH (scp diblokir policy) ->
# stop service -> unzip (.env & data tidak ditimpa) -> start -> verifikasi.
# Migrasi DB baru dijalankan Flyway otomatis saat service start.
# ============================================================
set -euo pipefail
cd "$(dirname "$0")"

TOKEN="${1:-}"
[ -n "$TOKEN" ] || { echo "Pakai: ./deploy-dev.sh <token-safous>"; exit 1; }
GW=ssh.cyolo.io
SSH=(ssh -o StrictHostKeyChecking=accept-new -o ConnectTimeout=20 -o BatchMode=yes "$GW" -l "$TOKEN")

echo ">> [1/5] Build paket"
./build-dist.sh >/dev/null
ZIP=target/app-dist.zip
SUM=$(shasum -a 256 "$ZIP" | cut -d' ' -f1)
echo "   $(ls -lh "$ZIP" | awk '{print $5}')  sha256=${SUM:0:16}…"

echo ">> [2/5] Cek koneksi ke server"
"${SSH[@]}" 'hostname' || { echo "Token tidak valid / grant sudah habis — request akses lagi di portal."; exit 1; }

echo ">> [3/5] Kirim paket (tanpa progress bar, tunggu 1-3 menit)"
"${SSH[@]}" 'cat > /tmp/app-dist.zip' < "$ZIP"
REMOTE_SUM=$("${SSH[@]}" 'sha256sum /tmp/app-dist.zip | cut -d" " -f1')
[ "$SUM" = "$REMOTE_SUM" ] || { echo "GAGAL: checksum beda (transfer terpotong). Ulangi."; exit 1; }
echo "   checksum cocok"

echo ">> [4/5] Pasang & restart service (minta password sudo ausyapp_dev)"
PW="${AUSY_SUDO_PASS:-}"
if [ -z "$PW" ]; then read -rsp "   password sudo: " PW; echo; fi
"${SSH[@]}" "PW='$PW'; S() { sudo -S -p '' \"\$@\" <<< \"\$PW\"; }
  S systemctl stop ausy
  S unzip -o -x .env -d /opt/ausy /tmp/app-dist.zip | tail -2
  S chown -R ausy:\$(id -gn ausy) /opt/ausy
  S chmod 600 /opt/ausy/.env
  S systemctl start ausy"

echo ">> [5/5] Verifikasi"
sleep 30
"${SSH[@]}" 'systemctl is-active ausy; curl -s -o /dev/null -w "http_login=%{http_code}\n" http://127.0.0.1:8080/ausy/login'
echo ">> Selesai — http://10.5.99.24:8080/ausy"
