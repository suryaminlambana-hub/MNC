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
# FTP diisi lewat menu "Konfigurasi FTP" di aplikasi (Admin), boleh dibiarkan kosong
FTP_HOST=
```

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
- [ ] Isi Konfigurasi FTP (menu Admin) kalau export ke core sudah mau diuji.
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
