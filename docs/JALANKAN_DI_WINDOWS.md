# Menjalankan AUSY di Windows

Panduan ini untuk menjalankan AUSY di laptop Windows sendiri, untuk mencoba/menguji.
Tidak perlu tahu Java atau Maven. Ikuti berurutan, sekitar 15 menit untuk pertama kali.

---

## Yang TIDAK perlu Anda lakukan

**Jangan pasang `winget`.** AUSY tidak memakainya sama sekali.

Kalau Anda pernah menemukan pesan seperti ini:

```
winget : The term 'winget' is not recognized...
Add-AppxPackage : Deployment failed with HRESULT: 0x80073CF9
Access is denied.
```

abaikan saja. Itu masalah App Installer bawaan Windows dan tidak ada hubungannya
dengan AUSY. Lewati, lanjut ke langkah di bawah.

Juga tidak perlu: Maven, Git Bash, Docker, IIS.

---

## Yang perlu dipasang (sekali saja)

### 1. Java 21

Ada dua cara. **Cara A lebih cepat dan tidak perlu hak administrator.**

**Cara A — tanpa install**

1. Buka <https://adoptium.net/temurin/releases/?version=21&os=windows&arch=x64>
2. Unduh yang **.zip** (bukan .msi)
3. Klik kanan → Extract All
4. Di dalam hasil ekstrak ada satu folder seperti `jdk-21.0.5+11`.
   Ganti namanya menjadi **`jdk`**, lalu pindahkan ke dalam folder AUSY —
   sejajar dengan `run.bat`.

   Susunannya harus jadi seperti ini:

   ```
   AUSY-MNC-JAVA\
     run.bat
     jdk\
       bin\
         java.exe      <-- file ini harus ada
   ```

**Cara B — installer biasa**

Unduh **.msi** dari alamat yang sama, jalankan, ikuti Next sampai selesai.
Setelah itu **tutup semua jendela PowerShell/Command Prompt dan buka lagi**
(kalau tidak, jendela lama belum mengenali Java).

> `run.bat` akan mencari Java sendiri: di PATH, di `JAVA_HOME`, di folder `jdk`
> di atas, dan di lokasi install umum. Anda tidak perlu mengatur PATH manual.

### 2. PostgreSQL

1. Unduh installer dari <https://www.postgresql.org/download/windows/>
2. Jalankan, ikuti Next.
3. **Saat diminta password untuk user `postgres`, catat passwordnya.**
   Dipakai satu kali di langkah berikutnya. Kalau lupa, installer harus diulang.
4. Sisanya biarkan default (port 5432).

---

## Menyiapkan AUSY

### 3. Ambil kodenya

Kalau sudah punya foldernya, lewati. Kalau belum, di Command Prompt:

```cmd
git clone https://github.com/SEAuser/AUSY-MNC-JAVA.git
cd AUSY-MNC-JAVA
```

Belum punya Git? Unduh folder ZIP dari halaman GitHub-nya
(tombol hijau **Code** → **Download ZIP**), lalu extract.

### 4. Buat database (sekali saja)

Buka **Command Prompt** di folder AUSY, jalankan:

```cmd
psql -U postgres -f packaging\setup-db.sql
```

Akan diminta password `postgres` dari langkah 2. Ketik lalu Enter
(**tulisannya tidak muncul saat diketik — itu normal**).

Berhasil kalau muncul:

```
>> setup-db.sql selesai: database "ausy" + user "ausy_user" siap.
```

Kalau muncul `'psql' is not recognized`, PostgreSQL ada tapi belum di PATH.
Pakai alamat lengkapnya:

```cmd
"C:\Program Files\PostgreSQL\16\bin\psql" -U postgres -f packaging\setup-db.sql
```

(sesuaikan `16` dengan versi yang terpasang)

### 5. Jalankan

Klik dua kali **`run.bat`**, atau dari Command Prompt:

```cmd
run.bat
```

Pertama kali agak lama (mengunduh komponen). Tunggu sampai muncul:

```
Started AusyApplication in 5.2 seconds
```

### 6. Buka di browser

<http://localhost:8080/ausy>

Login untuk mencoba:

| Peran | Username | Password |
|---|---|---|
| Maker (input data) | `user` | `user123` |
| Checker (otorisasi) | `spv` | `spv123` |
| Admin | `admin` | `admin123` |

**Menghentikan aplikasi:** tekan `Ctrl` + `C` di jendela hitamnya.

---

## Kalau tersangkut

### "Java belum ketemu di komputer ini"

`run.bat` sudah mencari di semua tempat yang wajar. Artinya Java memang belum ada,
atau folder `jdk` salah posisi. Periksa file ini benar-benar ada:

```
<folder AUSY>\jdk\bin\java.exe
```

Sering salah karena hasil unzip berlapis: `jdk\jdk-21.0.5+11\bin\java.exe`.
Kalau begitu, naikkan isinya satu tingkat.

### "PostgreSQL tidak menjawab di 127.0.0.1:5432"

PostgreSQL terpasang tapi servicenya mati. Tekan `Win` + `R`, ketik `services.msc`,
cari `postgresql-x64-16`, klik kanan → **Start**.

### Aplikasi jalan tapi browser bilang tidak bisa dibuka

Alamat lengkapnya <http://localhost:8080/ausy>. Mengetik `http://localhost:8080` saja juga
bisa — otomatis dialihkan ke sana.

Kalau yang muncul halaman abu-abu **"HTTP Status 404 — Apache Tomcat"**, berarti aplikasinya
jalan tapi versinya lama (pengalihan ini baru ada sejak 01-10-2026). Pakai alamat lengkapnya,
lalu periksa penanda `build` di pojok kanan bawah halaman.

### Port 8080 sudah dipakai program lain

Edit file `.env` di folder AUSY, tambahkan baris:

```
SERVER_PORT=9090
```

Lalu jalankan `run.bat` lagi dan buka <http://localhost:9090/ausy>.

### Ingin mengulang dari nol

Jalankan ulang langkah 4. Perintah itu menghapus database lama dan membuat yang baru,
jadi semua data percobaan hilang dan isinya kembali seperti awal.

---

## Hasil export file-nya ke mana?

Setelah checker menyetujui export, admin membuat file ACCTXT lewat menu
**Generate File ACCTXT**. File-nya muncul di `C:\inetpub\ftproot`.

Mau di folder lain? Edit `.env`, ubah barisnya:

```
AUSY_EXPORT_DIR=C:/inetpub/ftproot
```

Pakai garis miring `/` walaupun di Windows. Folder yang benar-benar dipakai selalu
dicetak saat aplikasi start:

```
File hasil export ditulis ke C:\inetpub\ftproot
```

Baris itu sumber kebenarannya — kalau isinya bukan folder yang Anda pantau, yang salah
setelan `AUSY_EXPORT_DIR`-nya.
