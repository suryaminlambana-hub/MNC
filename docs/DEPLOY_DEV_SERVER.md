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
# AUSY_EXPORT_DIR = salinan lokal file ACCTXT di mesin aplikasi. Kosong = bawaan data/txt
# (di server ini /opt/ausy/data/txt). Path Windows seperti C:/inetpub/ftproot TIDAK berlaku di
# Linux: diabaikan dan jatuh ke bawaan (BUG_AUSY_00187).
AUSY_EXPORT_DIR=
# FTP MNC tujuan file ACCTXT — ada di server DB (10.5.99.23), bukan di server aplikasi ini.
FTP_HOST=10.5.99.23
FTP_USER=ftpuser
FTP_PASS=<password ftpuser>
FTP_DIR=
```

> **File ACCTXT dikirim ke FTP MNC** (BUG_AUSY_00189). Generate File ACCTXT menulis file ke
> `AUSY_EXPORT_DIR`, lalu langsung mengunggahnya ke `FTP_HOST`. Kalau unggahan gagal, layar
> menyebut alasannya dan export tetap menunggu, sehingga tombolnya bisa ditekan lagi. Server
> aplikasi juga punya vsftpd dan user `ftpuser` sendiri. Itu **bukan** FTP MNC, jadi jangan
> arahkan `AUSY_EXPORT_DIR` ke `/home/ftpuser` di mesin ini. Log saat start menyebut tujuannya:
> `File ACCTXT dikirim ke /home/ftpuser di server FTP 10.5.99.23 setelah ditulis`.

### "Hasil export tidak muncul di inetpub/ftproot" (BUG_AUSY_00151)

Urutannya begini:

1. Log saat start menyebut folder yang **sebenarnya** dipakai: `File hasil export ditulis ke <path>`.
   Itu sumber kebenarannya — kalau isinya bukan folder yang Anda pantau, `AUSY_EXPORT_DIR`-nya yang salah.
2. `WARN ... TIDAK BISA DITULIS` → izin folder. Di Windows `inetpub` default terkunci: jalankan
   `run.bat` sebagai Administrator atau beri izin Modify ke user yang menjalankan aplikasi.
3. `WARN AUSY_EXPORT_DIR menunjuk ... yang tidak bisa dipakai` → setelan itu diabaikan dan dipakai
   folder bawaan. Ini muncul kalau foldernya **dan** folder induknya tidak ada, yang praktis selalu
   berarti salah tulis. Dulu folder salah itu dibuat begitu saja dan hasil export terbit di tempat
   yang tidak dipantau siapa pun.
4. `WARN AUSY_EXPORT_DIR menyebut folder "inethub" ...` → salah tulis satu huruf untuk `inetpub`.
   Aplikasi memakai `inetpub`-nya sendiri, jadi hasil export tetap mendarat di tempat yang benar
   walaupun `C:\inethub\` terlanjur ada (dibuat versi lama, sehingga aturan nomor 3 tidak
   menangkapnya). `run.bat` sekalian memperbaiki baris itu di `.env`; folder `C:\inethub\` yang
   lama boleh dihapus.
5. `AUSY_EXPORT_DIR` yang ditulis tapi dibiarkan **kosong** dianggap tidak diisi → folder bawaan.
6. Pesan setelah checker approve menyebut nama file yang dibuat.

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
- [ ] `AUSY_EXPORT_DIR` menunjuk folder yang benar-benar dipantau core (root FTP lokal), dan user
      yang menjalankan aplikasi punya izin tulis ke folder itu. Cek baris `File hasil export
      ditulis ke ...` di log saat start.
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
