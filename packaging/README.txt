============================================================
 AUSY — MNC Bank Accounting Upload System
 Paket deploy plug-and-play (Linux)
============================================================

ISI PAKET
  ausy-<versi>.jar   Aplikasi lengkap (backend + UI sudah menyatu di 1 file)
  setup-db.sql       Bikin database + user (jalankan sekali)
  .env.example       Template konfigurasi
  start.sh           Menjalankan aplikasi (auto-setup DB kalau belum ada)
  ausy-trigger.jar   Pemicu tombol Generate File ACCTXT, dijadwalkan sendiri (lihat bawah)
  README.txt         Berkas ini

PRASYARAT (sekali saja)
  - Java 21+        Debian/Ubuntu : sudo apt install -y openjdk-21-jre
                    RHEL/Fedora   : sudo dnf install -y java-21-openjdk
  - PostgreSQL 14+  Debian/Ubuntu : sudo apt install -y postgresql
                    (pastikan service-nya jalan: sudo systemctl start postgresql)

LANGKAH DEPLOY (4 langkah)
  1. unzip app-dist.zip -d app && cd app
  2. sudo -u postgres psql -f setup-db.sql
  3. cp .env.example .env
  4. chmod +x start.sh && ./start.sh

  Lalu buka:  http://localhost:8080/ausy
  Login     :  admin(config)/admin123 · adminapv/admin123 · user(maker)/user123 · spv(approver)/spv123

CATATAN
  - Langkah 2 cukup SEKALI. Kalau dilewati, start.sh akan otomatis
    mencoba menjalankan setup-db.sql (perlu akses superuser).
  - Ganti port atau kredensial DB lewat file .env. Kalau DB_* di .env
    diubah, sesuaikan juga setup-db.sql (nama db/user/password).
  - Password & nama DB default ada di .env.example dan setup-db.sql
    (db: ausy, user: ausy_user, pass: ausy_pass_2026).
  - Skema tabel & data awal dibuat OTOMATIS oleh aplikasi saat start
    pertama (Flyway). Tidak perlu import SQL skema manual.

GENERATE FILE ACCTXT TERJADWAL (ausy-trigger.jar)
  ausy-trigger.jar "menekan" tombol Generate File ACCTXT Hari Ini di
  aplikasi yang sedang jalan: export yang disetujui HARI INI dan belum digenerate
  dibuatkan file ACCTXT, dikirim ke FTP, dan dicatat di Riwayat serta
  audit trail (pelaku: jar-pemicu). Jam jalannya diatur sendiri.

  1. Isi token di .env (acak & panjang), lalu restart aplikasi:
       AUSY_TRIGGER_TOKEN=<hasil: openssl rand -hex 24>
  2. Coba sekali:
       java -jar /opt/ausy/ausy-trigger.jar --env=/opt/ausy/.env
  3. Jadwalkan di crontab user ausy (.env hanya bisa dibaca user itu),
     contoh tiap hari jam 23:00:
       sudo crontab -u ausy -e
       0 23 * * * java -jar /opt/ausy/ausy-trigger.jar --env=/opt/ausy/.env >> /opt/ausy/logs/trigger.log 2>&1

  - Windows (mis. laptop QA, dari folder proyek): double-click
    ausy-trigger.bat, atau  java -jar target\ausy-2.0.0-trigger.jar
    .env dan port aplikasinya ditemukan sendiri — tidak perlu --env.
    Path /opt/ausy/... di atas hanya untuk server Linux.
  - Aplikasi web harus sedang jalan saat JAR dipanggil.
  - Bila JAR dijalankan di mesin lain, isi juga AUSY_TRIGGER_URL
    (mis. http://10.5.99.24:8080/ausy) — lewat .env, variabel
    lingkungan, atau argumen --url=... (token: --token=...).
  - Kode keluar: 0 = file terbit / penampungan memang kosong,
    1 = penyusunan gagal (lihat Riwayat di halaman Generate File
    ACCTXT), 2 = aplikasi tidak bisa dihubungi / token salah / .env
    tidak ditemukan.

TROUBLESHOOTING
  - "Java tidak ditemukan"  -> install OpenJDK 21 (lihat Prasyarat).
  - "DB tidak bisa diakses" -> pastikan PostgreSQL jalan & sudah
                               jalankan langkah 2. Cek .env.
  - Port sudah dipakai      -> ubah SERVER_PORT di .env.
  - Reset total            -> jalankan ulang setup-db.sql (selalu fresh).
============================================================
