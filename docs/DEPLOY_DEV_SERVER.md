# Deploy AUSY ke server DEV MNC (2 server)

Aplikasi dan database dipisah:

| Peran | Host | Kebutuhan |
|---|---|---|
| Database | `MNCB-AUSY-DB-DEV` (10.5.99.23) | PostgreSQL 14+ |
| Aplikasi | `MNCB-AUSY-APP-DEV` (10.5.99.24) | Java 21 (JRE cukup), `psql` client (opsional, untuk cek koneksi) |

Kredensial SSH kedua server dipegang tim (dari Pak Boss) — **jangan** ditulis di repo,
di `.env` yang ter-commit, atau di tiket. `.env` hanya ada di server, tidak ikut git.

Paket deploy dibuat dari repo ini:

```bash
./build-dist.sh          # menghasilkan target/app-dist.zip (jar + setup-db.sql + .env.example + start.sh)
```

---

## 1. Server DB (10.5.99.23)

```bash
# a. pastikan PostgreSQL jalan
sudo systemctl enable --now postgresql
psql --version

# b. buat database + user aplikasi (sekali saja; skrip ini DROP-create, jadi selalu fresh)
#    salin dulu setup-db.sql dari paket, lalu:
sudo -u postgres psql -v ON_ERROR_STOP=1 -f setup-db.sql
```

> Ganti password `ausy_user` di `setup-db.sql` sebelum dijalankan (jangan pakai default
> `ausy_pass_2026` di lingkungan yang bisa diakses orang lain), dan samakan dengan `DB_PASS`
> di `.env` server aplikasi.

Izinkan koneksi dari server aplikasi saja:

```bash
# postgresql.conf
listen_addresses = '10.5.99.23'     # atau '*'

# pg_hba.conf  (baris paling bawah)
host    ausy    ausy_user    10.5.99.24/32    scram-sha-256

sudo systemctl reload postgresql
sudo firewall-cmd --permanent --add-rich-rule='rule family=ipv4 source address=10.5.99.24/32 port port=5432 protocol=tcp accept' && sudo firewall-cmd --reload
# (Ubuntu: sudo ufw allow from 10.5.99.24 to any port 5432 proto tcp)
```

## 2. Server aplikasi (10.5.99.24)

```bash
# a. Java 21
sudo dnf install -y java-21-openjdk        # RHEL/Fedora
# sudo apt install -y openjdk-21-jre       # Debian/Ubuntu
java -version

# b. pasang aplikasi
sudo useradd -r -m -d /opt/ausy -s /sbin/nologin ausy || true
sudo unzip app-dist.zip -d /opt/ausy && sudo chown -R ausy:ausy /opt/ausy

# c. konfigurasi
sudo -u ausy cp /opt/ausy/.env.example /opt/ausy/.env
sudo -u ausy vi /opt/ausy/.env
```

Isi `.env` untuk deploy ini:

```
SERVER_PORT=8080
CONTEXT_PATH=/ausy
DB_HOST=10.5.99.23        # <- database ada di server lain
DB_PORT=5432
DB_NAME=ausy
DB_USER=ausy_user
DB_PASS=<password yang dipakai di setup-db.sql>
# Kalau FTP server-nya ada di mesin yang sama, JANGAN pakai FTP_* — arahkan AUSY_EXPORT_DIR
# ke root FTP-nya (BUG_AUSY_00146). File ACCTXT langsung ditulis di sana dan push FTP mati sendiri
# karena FTP_HOST kosong; FTP_ENABLED tidak perlu diisi.
AUSY_EXPORT_DIR=C:/inetpub/ftproot
FTP_ENABLED=

# FTP_* hanya untuk kirim ke server FTP LAIN. Diisi DI SINI, bukan lewat website: menu
# "Konfigurasi FTP" sudah dihapus (BUG_AUSY_00141) supaya host & kredensial tidak bisa
# dilihat/diubah dari antarmuka. FTP_DIR = folder DI SERVER FTP (mis. /gl), bukan path lokal.
FTP_HOST=
FTP_PORT=21
FTP_USER=
FTP_PASS=
FTP_DIR=
```

> **Push FTP mati kalau `FTP_HOST` kosong.** Urutan sumbernya:
>
> 1. `FTP_ENABLED=false` → mati, apa pun isi yang lain.
> 2. `FTP_HOST` diisi di `.env` → itu yang dipakai.
> 3. `FTP_HOST` kosong → **mati**. Baris tabel `ftp_config` peninggalan layar Konfigurasi FTP yang
>    lama sengaja diabaikan: tidak ada satu pun layar untuk melihat/mengubahnya, jadi kalau dipakai
>    diam-diam setiap otorisasi export gagal dengan kredensial yang tidak bisa diperbaiki dari
>    website. Log start menyebutkannya sebagai `WARN Push FTP MATI: ...`.
> 4. Masih mau memakai baris `ftp_config` itu → `FTP_ENABLED=true`.
>
> Sumber yang berlaku disebut di log saat start dan di card **Export FTP Gagal / Tertunda** pada
> beranda Admin. Kalau card itu masih memuat baris `FAILED` dari sebelum FTP dimatikan, tombol
> **Kirim Ulang** akan menyelesaikannya: dengan push FTP mati, file ACCTXT di `AUSY_EXPORT_DIR`
> itulah pengirimannya, jadi barisnya ditandai `SENT` (tercatat di audit trail) selama file-nya
> masih ada di direktori itu.
>
> Membersihkan sisa baris lama (opsional): `UPDATE ftp_config SET host = '' WHERE id = 1;`

```bash
# d. uji jalan di foreground dulu (Ctrl+C untuk berhenti)
sudo -u ausy /opt/ausy/start.sh

# e. kalau sudah OK, jadikan service
sudo cp /opt/ausy/ausy.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now ausy
sudo systemctl status ausy
sudo journalctl -u ausy -f
```

Buka firewall aplikasi bila diakses dari jaringan kantor:

```bash
sudo firewall-cmd --permanent --add-port=8080/tcp && sudo firewall-cmd --reload
```

Skema tabel + data awal dibuat **otomatis** oleh Flyway saat aplikasi start pertama
(V1…V17) — tidak ada import SQL skema manual.

### Catatan dari deploy pertama (4 Sep 2026, server DEV MNC)

Dua hal yang berbeda dari asumsi paket, ditemukan saat deploy lewat portal Safous:

1. **SCP diblokir policy Safous** — `scp` ditutup gateway ("Connection closed"), padahal SSH biasa
   jalan. File dikirim lewat pipe di sesi SSH, dan ini berhasil untuk paket 76 MB:
   ```bash
   ssh <token>@ssh.cyolo.io 'cat > /tmp/app-dist.zip' < target/app-dist.zip
   # lalu cocokkan sha256sum di server dengan di lokal
   ```
   Grant akses Safous berumur pendek — kalau sesi putus di tengah, file yang sudah mendarat di
   `/tmp` tetap aman, tinggal request akses lagi.

2. **User `ausy` sudah ada di server tapi grup utamanya `users`** (bukan grup `ausy`), sehingga
   `ausy.service` gagal dengan `status=216/GROUP`. Selalu samakan `Group=` dengan grup nyata:
   ```bash
   sudo sed -i "s/^Group=.*/Group=$(id -gn ausy)/" /etc/systemd/system/ausy.service
   sudo chown -R "ausy:$(id -gn ausy)" /opt/ausy && sudo systemctl daemon-reload && sudo systemctl restart ausy
   ```

PostgreSQL di server DB dipasang dari modul Satellite: `dnf module enable -y postgresql:16` lalu
`dnf install -y postgresql-server` (server tidak punya akses internet, hanya repo internal).

## 3. Verifikasi

```bash
curl -I http://10.5.99.24:8080/ausy/login          # 200
```

Login dan cek cepat: `admin` (config) · `adminapv` (admin approval) · `user` (maker) · `spv` (checker).
Alur minimal yang layak dites setelah deploy: upload Excel → Otorisasi Upload GL →
Kelola Data GL (ajukan perubahan → approve) → Ajukan Export → Otorisasi Export.

## 4. Wajib dikerjakan sebelum dipakai orang lain

- [ ] Ganti password 4 akun seed (`admin123`/`user123`/`spv123`) lewat menu User.
- [ ] Password `ausy_user` bukan default, dan `.env` mode `600` milik user `ausy`.
- [ ] Isi `FTP_*` di `.env` kalau export ke core lewat FTP sudah mau diuji — setelan FTP tidak lagi
      bisa diubah dari website, dan `FTP_HOST` kosong berarti push FTP mati (file diambil dari
      `AUSY_EXPORT_DIR`). Kirim ulang transfer yang gagal ada di beranda Admin.
- [ ] Tentukan siapa yang boleh akses port 8080 (reverse proxy/HTTPS kalau keluar jaringan dev).
- [ ] Backup: `pg_dump ausy` terjadwal di server DB.

## Update versi berikutnya

```bash
# di lokal
./build-dist.sh
scp target/app-dist.zip <user>@10.5.99.24:/tmp/

# di server aplikasi
sudo systemctl stop ausy
sudo unzip -o /tmp/app-dist.zip -x .env -d /opt/ausy && sudo chown -R ausy:ausy /opt/ausy
sudo systemctl start ausy
```

`.env` dan data di `/opt/ausy/data` tidak ditimpa. Migrasi DB baru (V18, dst.) dijalankan
Flyway otomatis saat service start.
