# Patch Notes AUSY

Catatan rilis per deploy ke server DEV (10.5.99.24, `http://10.5.99.24:8080/ausy`).
Entri terbaru di atas.

Versi di `pom.xml` tetap `2.0.0`, jadi rilis dikenali dari **git tag** `dev-YYYYMMDD-N`. Tag ini
menunjuk commit yang terpasang di DEV. Untuk melihat build yang sedang jalan, buka penanda
**build** di pojok kanan bawah aplikasi, atau jalankan `git describe --tags` di commit yang
dimaksud.

Setiap entri memuat:
- **Commit:** isi rilis. Untuk detail, lihat `git log <tag-sebelumnya>..<tag>`.
- **Migrasi DB:** Flyway menjalankannya otomatis saat service start.
- **Setelan server:** perubahan `/opt/ausy/.env` yang ikut dibutuhkan rilis.
- **Operasional:** tindakan langsung di server/DB di luar kode.

---

## dev-20261006-1 — 06-10-2026 18:13

Commit `90b7a73`. DB naik dari v43 ke **v45**.

### Perubahan
- **Validasi kolom upload Excel** (`1be7a04`, `26a028e`, `a6327bd`)
  - Currency (kolom L) wajib `IDR`. Kosong pun ditolak.
  - Product Code (C) dan Transaction_Account (E) hanya boleh angka 0-9, tanpa tanda petik.
  - Branch (H) dan Cost_Center (J) boleh angka dan tanda petik satu.
  - Responding_Branch (P) tidak divalidasi, karena isinya rumus yang menyalin H2.
  - Baris dengan sel galat Excel (`#N/A`, `#REF!`, …) membatalkan unggahan. Sebelumnya baris
    dengan VLOOKUP gagal tersimpan dengan Gl_Name kosong dan terbawa ke ACCTXT.
  - Penyaringan simbol pada nama/deskripsi dicabut di semua jalur (free text).
- **Role CONFIG APPROVER bisa dipilih** di Tambah/Ubah User (BUG_AUSY_00194).
- **Masa berlaku password 30 hari** (REQ_AUSY_00009, 00010, 00011)
  - Kolom baru `users.pass_changed_at`, diisi awal dari `cre_date`.
  - Pengingat H-3 di beranda Admin dan halaman User.
  - Password yang lewat masa berlaku (mulai hari ke-31) menolak login dengan pesan khusus.
  - Akun Utama dikecualikan dari penguncian.
  - Role config punya jalur reset password.

### Migrasi DB
- `V45__req_00009_masa_aktif_password.sql`

### Catatan untuk developer
- **Jangan pakai nomor V44.** `V44__req_00006_pairing_admin.sql` (`d92c060`) di-revert di
  `c6e9136` sebelum sampai ke DEV. DB DEV sudah v45, jadi file V44 baru akan dianggap
  out-of-order dan ditolak Flyway. Migrasi berikutnya: **V46**.
- **ID bug ganda:** `BUG_AUSY_00190` dipakai untuk dua masalah berbeda, yaitu Currency wajib
  IDR (`1be7a04`) dan password kedaluwarsa menolak login (`90b7a73`). Cocokkan dengan tracker
  QA sebelum menutup tiket.

---

## dev-20261005-3 — 05-10-2026 18:27

Commit `bb672c7`. DB tetap v43.

### Perubahan
- **Teks halaman Generate File ACCTXT** (`59679bb`, `bb672c7`, BUG_AUSY_00189). Path lokal
  `/opt/...` tidak lagi tampil di layar maupun di audit trail.
  - Keterangan: "Filenya terkirim ke `/home/ftpuser`".
  - Alert sukses: "File ACCTXT.`<YYMMDD>` terkirim ke FTP ftpuser@10.5.99.23 berisi …".
  - Teksnya disusun dari `FTP_USER`, `FTP_HOST`, `FTP_DIR`, bukan hardcode.

---

## dev-20261005-2 — 05-10-2026, sebelum 18:15

Commit `2d82f02`. DB tetap v43.

### Perubahan
- **File ACCTXT dikirim ke FTP MNC setelah digenerate** (BUG_AUSY_00187, BUG_AUSY_00189).
  - FTP MNC ada di **server DB (10.5.99.23)**, bukan di server aplikasi.
  - Server aplikasi (.24) juga punya vsftpd dan user `ftpuser` sendiri, tetapi itu bukan FTP MNC.
  - Gagal kirim: alasan disebut di layar, export tetap berstatus menunggu dan bisa dicoba lagi.
  - `FTP_HOST` kosong: perilaku lama, file hanya ditulis ke folder lokal.
  - Pengiriman FTP ini dibuat lagi setelah dulu dihapus di `3ed0826`.

### Setelan server (`/opt/ausy/.env`)
- Ditambah: `FTP_HOST=10.5.99.23`, `FTP_USER=ftpuser`, `FTP_PASS=…`. Opsional: `FTP_PORT`,
  `FTP_DIR`, `FTP_PASSIVE`.
- Dihapus: `AUSY_EXPORT_DIR=/home/ftpuser`. Salinan lokal kembali ke bawaan
  `/opt/ausy/data/txt`.
- Backup `.env` sebelumnya: `/opt/ausy/.env.bak-00189-ftp`.

### Operasional
- ACL tambahan di `/home/ftpuser` server .24 dicabut, kembali `rwx------`.
- `ACCTXT.261005` hasil generate sebelumnya diunggah manual ke FTP .23 (isi diverifikasi
  sama, sha256 `473a7099…`).

---

## dev-20261005-1 — 05-10-2026 16:13

Commit `a77a905`. DB naik dari v42 ke **v43**.

### Perubahan
- **Tag akun di header tertukar** (BUG_AUSY_00183). `users.level` mncadmin/mncsuperadmin
  dibetulkan, termasuk pemetaan di `UserService`.
- **Upload Excel menyala untuk setiap maker baru** (BUG_AUSY_00184), apa pun isi cetakan
  rolenya.
- **Status "Terpakai" Kode GL** (BUG_AUSY_00185). Baris GL milik user yang sudah dihapus tidak
  lagi dihitung sebagai pemakaian.
- **Kode GL dikosongkan untuk UAT** (BUG_AUSY_00186), lewat V43.
- **Riwayat User di dashboard admin** (BUG_AUSY_00188) hanya berisi pengajuan admin itu sendiri
  dan keputusan approver atas pengajuannya.

### Migrasi DB
- `V43__bug_00183_00184_00186_tag_upload_glcode.sql`. **Menghapus semua `gl_code`** dan
  membatalkan pengajuan Kode GL yang masih PENDING. Jangan dijalankan di lingkungan yang
  berisi kode GL asli.

### Setelan server
- `AUSY_EXPORT_DIR=/home/ftpuser` sempat ditambahkan (17:03). Ini keliru, karena menunjuk FTP
  milik server aplikasi, dan dicabut di dev-20261005-2.

---

## Operasional di luar rilis

- **05-10-2026 malam — audit trail dibersihkan untuk UAT.** Semua 236 baris `tlog_transaction`
  (09-09-2026 s/d 05-10-2026 18:36) dihapus atas permintaan tim.
  - Backup: `/opt/ausy/backup-tlog_transaction-20261005.csv` di server aplikasi (chmod 600).
  - `tlog_activity` (log login) tidak disentuh.

---

## Sebelum pencatatan ini

Commit hingga `62731ec` (05-10-2026) sudah terpasang di DEV lewat deploy sebelumnya. Tanggal
deploy-nya tidak tercatat. Termasuk di dalamnya:
- V41: reset user untuk UAT, hanya `mncsuperadmin` dan `mncadmin`.
- V42: menu akun config dilengkapi.
- Seri BUG_AUSY_00176–00182 (Generate ACCTXT, pembersihan karakter, upload Excel).

Riwayat lengkapnya: `git log 62731ec`.

---

## Menambah entri

Setiap selesai deploy ke DEV:
1. Tambah entri baru di atas: tanggal dan jam (dari log `Started AusyApplication`), commit, versi
   DB, perubahan, migrasi, setelan server, dan tindakan operasional.
2. Pasang tag di commit yang terpasang, lalu push:
   ```
   git tag -a dev-YYYYMMDD-N <commit> -m "Deploy DEV YYYY-MM-DD HH:MM"
   git push origin dev-YYYYMMDD-N
   ```
