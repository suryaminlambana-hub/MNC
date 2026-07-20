============================================================
 AUSY — MNC Bank Accounting Upload System
 Paket deploy plug-and-play (Linux)
============================================================

ISI PAKET
  ausy-<versi>.jar   Aplikasi lengkap (backend + UI sudah menyatu di 1 file)
  setup-db.sql       Bikin database + user (jalankan sekali)
  .env.example       Template konfigurasi
  start.sh           Menjalankan aplikasi (auto-setup DB kalau belum ada)
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
  Login     :  admin / admin123   (atau spv/spv123, user/user123)

CATATAN
  - Langkah 2 cukup SEKALI. Kalau dilewati, start.sh akan otomatis
    mencoba menjalankan setup-db.sql (perlu akses superuser).
  - Ganti port atau kredensial DB lewat file .env. Kalau DB_* di .env
    diubah, sesuaikan juga setup-db.sql (nama db/user/password).
  - Password & nama DB default ada di .env.example dan setup-db.sql
    (db: ausy, user: ausy_user, pass: ausy_pass_2026).
  - Skema tabel & data awal dibuat OTOMATIS oleh aplikasi saat start
    pertama (Flyway). Tidak perlu import SQL skema manual.

TROUBLESHOOTING
  - "Java tidak ditemukan"  -> install OpenJDK 21 (lihat Prasyarat).
  - "DB tidak bisa diakses" -> pastikan PostgreSQL jalan & sudah
                               jalankan langkah 2. Cek .env.
  - Port sudah dipakai      -> ubah SERVER_PORT di .env.
  - Reset total            -> jalankan ulang setup-db.sql (selalu fresh).
============================================================
