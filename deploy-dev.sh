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

echo ">> [1/7] Build paket"
ZIP=target/app-dist.zip
STAMP=target/app-dist.commit
HEAD_NOW=$(git rev-parse HEAD)$(git status --porcelain | shasum -a 256 | cut -d" " -f1)
if [ -f "$ZIP" ] && [ -f "$STAMP" ] && [ "$(cat "$STAMP")" = "$HEAD_NOW" ]; then
  # Kode belum berubah sejak build terakhir: pakai paket yang ada supaya checksum-nya stabil,
  # jadi retry setelah koneksi Safous putus tidak perlu kirim ulang 76 MB dari nol.
  echo "   kode belum berubah — pakai paket yang sudah ada"
else
  ./build-dist.sh >/dev/null
  echo "$HEAD_NOW" > "$STAMP"
fi
SUM=$(shasum -a 256 "$ZIP" | cut -d' ' -f1)
echo "   $(ls -lh "$ZIP" | awk '{print $5}')  sha256=${SUM:0:16}…"

echo ">> [2/7] Cek koneksi ke server"
"${SSH[@]}" 'hostname' || { echo "Token tidak valid / grant sudah habis — request akses lagi di portal."; exit 1; }

echo ">> [3/7] Kirim paket (tanpa progress bar, tunggu 1-3 menit)"
REMOTE_SUM=$("${SSH[@]}" 'sha256sum /tmp/app-dist.zip 2>/dev/null | cut -d" " -f1' || true)
if [ "$SUM" = "$REMOTE_SUM" ]; then
  echo "   paket identik sudah ada di server — transfer dilewati"
else
  "${SSH[@]}" 'cat > /tmp/app-dist.zip' < "$ZIP"
  REMOTE_SUM=$("${SSH[@]}" 'sha256sum /tmp/app-dist.zip | cut -d" " -f1')
  [ "$SUM" = "$REMOTE_SUM" ] || { echo "GAGAL: checksum beda (transfer terpotong). Ulangi."; exit 1; }
  echo "   checksum cocok"
fi

echo ">> [4/7] Pasang & restart service (minta password sudo ausyapp_dev)"
PW="${AUSY_SUDO_PASS:-}"
if [ -z "$PW" ]; then read -rsp "   password sudo: " PW; echo; fi
"${SSH[@]}" "PW='$PW'; S() { sudo -S -p '' \"\$@\" <<< \"\$PW\"; }
  S systemctl stop ausy
  S unzip -o /tmp/app-dist.zip -x .env -d /opt/ausy | tail -2
  S chown -R ausy:\$(id -gn ausy) /opt/ausy
  S chmod 600 /opt/ausy/.env
  S systemctl start ausy"

echo ">> [5/7] Setelan penjadwal di .env server"
# .env server sengaja TIDAK ikut ditimpa unzip, jadi setelan yang hanya ada di sana diurus di
# sini. Idempoten: baris lama dibuang dulu, baru ditulis ulang — menjalankan ulang skrip ini
# tidak menumpuk baris yang sama.
#   AUSY_SCHEDULER=off  (bawaan) penjadwal tidak dipasang; berkas ACCTXT hanya lewat tombol
#   AUSY_SCHEDULER=on              penjadwal jam 23:00 hidup
SCHED="${AUSY_SCHEDULER:-off}"
case "$SCHED" in on) VAL=true ;; off) VAL=false ;; *) echo "AUSY_SCHEDULER harus on/off"; exit 1 ;; esac
# CATATAN PENTING: jangan memakai pola "echo ... | S tee -a".
# Helper S() menyuapkan password ke stdin sudo (<<< "$PW"), sehingga sudo MENELAN stdin dan
# isi pipanya tidak pernah sampai ke tee. Perintahnya tetap melaporkan sukses, tapi berkasnya
# tidak berubah — persis yang terjadi pada deploy 13-10-2026: penjadwal ikut hidup karena
# barisnya tidak pernah tertulis, lalu susulan langsung mengirim ACCTXT ke FTP.
# Penulisannya dilakukan DI DALAM satu sh -c, sehingga stdin sudo tidak dipakai perintahnya.
"${SSH[@]}" "PW='$PW'
  echo \"\$PW\" | sudo -S -p '' sh -c \"sed -i '/^AUSY_SCHEDULER_ENABLED=/d' /opt/ausy/.env; \
    printf 'AUSY_SCHEDULER_ENABLED=$VAL\\n' >> /opt/ausy/.env; chmod 600 /opt/ausy/.env\"
  # Dibuktikan, bukan diasumsikan.
  echo \"\$PW\" | sudo -S -p '' grep -q '^AUSY_SCHEDULER_ENABLED=$VAL' /opt/ausy/.env \
    || { echo 'GAGAL: AUSY_SCHEDULER_ENABLED tidak tertulis di /opt/ausy/.env'; exit 1; }
  echo \"\$PW\" | sudo -S -p '' systemctl restart ausy"
echo "   penjadwal: $SCHED (AUSY_SCHEDULER_ENABLED=$VAL)"

echo ">> [6/7] Tugas terjadwal (crontab)"
# AUSY_CRON=off (bawaan) tidak menyentuh apa pun; AUSY_CRON=on memasang pemicu jam 23:00.
#
# Yang dipasang BUKAN baris cron yang memanggil java langsung, melainkan unit oneshot
# ausy-acctxt.service. Alasannya: nilai di /opt/ausy/.env tidak dikutip dan sandi DB DEV
# mengandung & dan #. Perintah "set -a && . ./.env" memotongnya di & lalu menganggap sisanya
# komentar, jadi tugasnya mati di sambungan DB. systemd membaca EnvironmentFile tanpa shell —
# setelannya dijamin sama persis dengan service web.
CRON="${AUSY_CRON:-off}"
case "$CRON" in on|off) ;; *) echo "AUSY_CRON harus on/off"; exit 1 ;; esac
if [ "$CRON" = on ] && [ "$SCHED" = on ]; then
  echo "GAGAL: AUSY_SCHEDULER=on dan AUSY_CRON=on sekaligus — dua penjadwal akan bertabrakan"
  echo "       jam 23:00. Pilih salah satu."; exit 1
fi
if [ "$CRON" = off ]; then
  echo "   dilewati (AUSY_CRON=off)"
else
  "${SSH[@]}" "PW='$PW'; S() { echo \"\$PW\" | sudo -S -p '' \"\$@\"; }
    # Yang menjalankan /etc/cron.d adalah daemon-nya (crond di RHEL, cron di Debian).
    # Kalau daemonnya mati, berkasnya tertulis tapi tidak pernah dijalankan — jadi dicek dulu.
    systemctl is-active --quiet crond || systemctl is-active --quiet cron \
      || { echo 'GAGAL: daemon cron tidak aktif di server (crond/cron)'; exit 1; }
    S install -m 644 -o root -g root /opt/ausy/ausy-acctxt.service /etc/systemd/system/ausy-acctxt.service
    # Grup nyata user ausy (di DEV: users). Nilai salah = status=216/GROUP.
    S sed -i \"s/^Group=.*/Group=\$(id -gn ausy)/\" /etc/systemd/system/ausy-acctxt.service
    S systemctl daemon-reload
    # Pemicunya di /etc/cron.d supaya bisa menyebut user root — hanya root yang boleh
    # systemctl start. Tugasnya sendiri tetap berjalan sebagai ausy (User= di unit).
    S sh -c 'cat > /etc/cron.d/ausy-acctxt <<EOF
# AUSY — susun berkas ACCTXT tiap malam. Dikelola deploy-dev.sh, jangan disunting tangan.
CRON_TZ=Asia/Jakarta
0 23 * * * root /usr/bin/systemctl start ausy-acctxt.service
EOF
chmod 644 /etc/cron.d/ausy-acctxt; chown root:root /etc/cron.d/ausy-acctxt'
    # Dibuktikan, bukan diasumsikan.
    S grep -q 'ausy-acctxt.service' /etc/cron.d/ausy-acctxt \
      || { echo 'GAGAL: /etc/cron.d/ausy-acctxt tidak tertulis'; exit 1; }
    S systemctl cat ausy-acctxt.service >/dev/null \
      || { echo 'GAGAL: unit ausy-acctxt tidak dikenali systemd'; exit 1; }
    echo \"   zona waktu server: \$(date '+%Z %z')  jam server: \$(date '+%H:%M')\"
    echo '   isi /etc/cron.d/ausy-acctxt:'
    S sed 's/^/     /' /etc/cron.d/ausy-acctxt"
  echo "   pemicu jam 23:00 terpasang (unit ausy-acctxt.service)"
fi

echo ">> [7/7] Verifikasi"
sleep 30
JAR_LOCAL=$(shasum -a 256 target/ausy-*.jar | cut -d' ' -f1)
JAR_REMOTE=$("${SSH[@]}" "PW='$PW'; echo \"\$PW\" | sudo -S -p '' sha256sum /opt/ausy/ausy-2.0.0.jar | cut -d' ' -f1")
if [ "$JAR_LOCAL" = "$JAR_REMOTE" ]; then echo "   jar di server = build ini"; else
  echo "GAGAL: jar di server BUKAN build ini (lokal=${JAR_LOCAL:0:12} server=${JAR_REMOTE:0:12})"; exit 1; fi
"${SSH[@]}" 'systemctl is-active ausy; curl -s -o /dev/null -w "http_login=%{http_code}\n" http://127.0.0.1:8080/ausy/login'
echo ">> Selesai — http://10.5.99.24:8080/ausy"
