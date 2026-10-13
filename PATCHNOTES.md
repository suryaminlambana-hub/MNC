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

## Belum di-deploy

Commit `9c5b149`. DB naik dari v49 ke **v52**.

### Perubahan
- **Transaction Account dicocokkan ke master Kode GL saat upload** (REQ_AUSY_00059). Diperiksa
  per baris, nomor barisnya disebut. Kolom E kosong hanya ditolak kalau barisnya jelas sedang
  diisi — kolom itu sekaligus penanda "baris berisi data", dan template MNC datang dengan 167
  baris isian kosong.
- **Otorisasi yang menginap tidak dikembalikan ke maker** (REQ_AUSY_00060). Reject/Return atas
  otorisasi dari hari sebelumnya menghapus datanya; yang diproses pada hari yang sama tetap
  seperti biasa.
- **Role EXECUTE dirapikan** (REQ_AUSY_00018-00020) dan **Download Excel pada menu User**
  (REQ_AUSY_00021).
- **User baru dibuat tanpa satu pun hak akses** (REQ_AUSY_00017). Membatalkan BUG_AUSY_00143 dan
  BUG_AUSY_00184 — maker baru tidak lagi otomatis memegang Upload Excel.
- **Penjadwal pembuatan ACCTXT jam 23:00 WIB** (REQ_AUSY_00061).
  - Menyapu SELURUH isi penampungan yang belum pernah digenerate — otorisasi upload maupun
    Kelola Data GL, tanpa batas tanggal. Tombol manual tidak berubah.
  - Bisa dimatikan/dinyalakan dari halaman Generate File ACCTXT; statusnya, jadwal yang berlaku,
    dan jam jalan berikutnya tertulis di layar.
  - Tiap jalannya dicatat di Riwayat Penjadwal, berhasil maupun gagal.
  - Tombol **Jalankan Sekarang** untuk membuktikan tanpa menunggu tengah malam.
  - **Jadwal yang terlewat dikerjakan susulan saat aplikasi hidup lagi.**
- **Gagal kirim FTP tidak lagi membuang data** (lanjutan REQ_AUSY_00061). Dulu penandaan "sudah
  digenerate" tetap commit walau pengiriman gagal, padahal pesannya berbunyi "export tetap
  menunggu" — satu batch hilang tiap kali. Sekarang transaksinya dibatalkan dan datanya benar
  kembali ke penampungan.

### Migrasi DB
Dijalankan Flyway otomatis saat service start.
- `V50` tabel setelan penjadwal (satu baris, **aktif sejak awal**) dan tabel riwayatnya.
- `V51`, `V52` baris ACTION untuk tombol nyala/mati dan Jalankan Sekarang (role EXECUTE).

- **JAR bisa dijalankan sebagai tugas sekali-jalan** (REQ_AUSY_00058), supaya penjadwalannya
  boleh dipegang crontab milik server:
  ```
  cd /opt/ausy && set -a && . ./.env && set +a && \
    java -jar ausy-2.0.0.jar --ausy.task=generate-acctxt
  ```
  JAR yang sama, bukan aplikasi kedua — konfigurasi, aturan, dan pencatatan riwayat tetap satu
  tempat. Tomcat tidak dinyalakan pada mode ini, jadi tidak berebut port 8080 dengan aplikasi
  web yang sedang jalan. Kode keluar **0** bila berkasnya terbit, **0 juga** bila penampungan
  memang kosong, dan **1** bila penyusunannya gagal. Malam tanpa otorisasi sama sekali adalah
  hal yang wajar — kalau itu dihitung gagal, tandanya menyala hampir tiap akhir pekan dan orang
  berhenti mempercayainya.

- **Pemicu malamnya dipasang `deploy-dev.sh`** dengan `AUSY_CRON=on`:
  ```
  AUSY_CRON=on ./deploy-dev.sh <token-safous>
  ```
  Yang dipasang dua berkas:
  - `/etc/systemd/system/ausy-acctxt.service` — unit oneshot yang menjalankan tugasnya;
  - `/etc/cron.d/ausy-acctxt` — pemicunya jam 23:00 WIB.

  **Cron tidak memanggil `java` langsung**, melainkan `systemctl start ausy-acctxt.service`.
  Sebabnya nyata: nilai di `/opt/ausy/.env` tidak dikutip dan sandi DB DEV mengandung `&`
  dan `#`. Perintah `set -a && . ./.env` memotongnya di `&` lalu menganggap sisanya komentar,
  jadi tugasnya mati di sambungan DB padahal service web dengan berkas yang sama jalan normal —
  systemd membaca `EnvironmentFile` apa adanya, tanpa shell. Lewat unit, setelan tugas malam
  dijamin sama persis dengan setelan aplikasi web.

  Hasilnya dilihat dengan `systemctl status ausy-acctxt` atau `journalctl -u ausy-acctxt`,
  bukan berkas log tersendiri.

  `AUSY_SCHEDULER=on` dan `AUSY_CRON=on` sekaligus **ditolak skripnya**: dua penjadwal menembak
  jam 23:00 bersamaan, yang kalah mencatat `ACCTXT_GENERATE_GAGAL` di audit trail tanpa ada
  yang benar-benar salah.

- **Penyusunan ACCTXT dikunci antar-proses** (lanjutan REQ_AUSY_00058) memakai advisory lock
  Postgres. Kunci yang lama hanya berlaku dalam satu JVM, jadi tidak berarti apa-apa begitu
  berkasnya juga bisa disusun proses crontab.

- **Jejak penyusunan ACCTXT masuk audit trail Admin Approval** (REQ_AUSY_00059). Berlaku untuk
  penjadwal, tugas crontab, maupun tombol manual role EXECUTE — berhasil maupun gagal.

### Setelan server

**Memasang rilis ini TANPA penjadwalnya hidup — ini jalur yang dipilih untuk rilis pertama.**
Tambahkan di `/opt/ausy/.env`:

```
AUSY_SCHEDULER_ENABLED=false
```

Penjadwal tidak dipasang sama sekali: tidak ada jalan otomatis jam 23:00, tidak ada susulan
saat start, dan tombol nyala/mati serta Jalankan Sekarang hilang dari layar. Tombol
**Generate File ACCTXT Hari Ini** dan **Susun Ulang** tetap bekerja seperti biasa, jadi berkas
ACCTXT tetap bisa dibuat manual. Untuk menyalakannya nanti: hapus baris itu (atau isi `true`)
lalu `sudo systemctl restart ausy`.

Dengan setelan ini seluruh langkah penjadwal di bawah tidak berlaku — panel penjadwal
menampilkan badge **DIMATIKAN DI SERVER**, dan `ausy.service` tidak perlu diganti. Deploy-nya
menjadi: kirim JAR, tambah satu baris di `.env`, `systemctl restart ausy`.

Migrasi `V50`-`V52` tetap jalan dan membuat tabel serta baris menunya, tetapi tidak dipakai
selama saklarnya `false` — tidak ada perubahan perilaku bagi siapa pun.

Opsional, hanya kalau jadwalnya mau diubah dari jam 23:00:

```
AUSY_SCHEDULER_CRON=0 0 23 * * *
```

**Ekspresinya 6 kolom** (detik menit jam tanggal bulan hari). Jam 22:59 ditulis `0 59 22 * * *`,
bukan `59 22 * * *` seperti crontab biasa. Salah tulis tidak lagi menjatuhkan aplikasi: dipakai
jadwal bawaan dan peringatannya muncul di layar.

### Setelah deploy
1. **Hanya kalau penjadwal dihidupkan.** Dengan `AUSY_SCHEDULER_ENABLED=false`, LEWATI langkah
   ini — `ausy.service` tidak perlu disentuh sama sekali, dan risiko `Group=` di bawah tidak
   berlaku. Cukup pasang JAR baru lalu `sudo systemctl restart ausy`.

   `Restart=` berubah dari `on-failure` menjadi `always` karena penjadwal hanya menyala kalau
   prosesnya hidup. Tanpa penjadwal, `on-failure` yang sudah terpasang sudah memadai:
   ```
   sudo cp /opt/ausy/ausy.service /etc/systemd/system/
   # WAJIB: berkas di repo berisi Group=ausy, sedangkan grup nyata user ausy di server DEV
   # adalah "users". Tanpa baris ini service gagal dengan status=216/GROUP — persis yang
   # terjadi saat deploy pertama (4 Sep 2026).
   sudo sed -i "s/^Group=.*/Group=$(id -gn ausy)/" /etc/systemd/system/ausy.service
   sudo systemctl daemon-reload && sudo systemctl restart ausy
   sudo systemctl status ausy        # harus active (running) DAN enabled
   ```
   Penjadwal hanya menyala kalau aplikasinya hidup, jadi berhenti dengan alasan apa pun —
   termasuk keluar normal — harus dihidupkan lagi. `enabled` membuatnya ikut hidup saat
   server boot.
2. Buka **Generate File ACCTXT** dan periksa panel Penjadwal Otomatis: badge **AKTIF**, jadwal
   yang berlaku, dan jam jalan berikutnya.
3. Kalau badge-nya **TIDAK AKTIF**, nyalakan dari tombolnya. Migrasi `V50` hanya memasang baris
   setelan bila belum ada — kalau penjadwal pernah dimatikan dari layar, deploy TIDAK
   menyalakannya kembali.
4. Tekan **Jalankan Sekarang** sekali untuk memastikan jalurnya hidup; hasilnya masuk Riwayat
   Penjadwal.

### Catatan untuk developer
- Start pertama sesudah deploy kemungkinan langsung mengerjakan **susulan** jadwal 23:00 yang
  terlewat. Kalau penampungan sedang berisi, berkas terbit dan dikirim ke FTP saat itu juga,
  bukan menunggu jam 23:00. Disengaja — data yang menunggu memang untuk diterbitkan — tapi
  perlu diketahui kalau deploy dilakukan siang hari.
- Diuji memakai artefak deploy yang sebenarnya (`java -jar`, bukan `mvnw spring-boot:run`) di
  basis data kosong: 51 migrasi sampai v52, penjadwal terdaftar dengan jadwalnya, susulan
  dikerjakan sekali, dan jadwal jam tertentu menyala tepat pada detiknya.

---

## dev-20261009-1 — 09-10-2026 16:43

Commit `c553944`. DB naik dari v47 ke **v49**.

### Perubahan
- **Maker bisa menghapus batch upload yang belum di-approve** (REQ_AUSY_00022).
  - Tombol Hapus ada di section "Upload Menunggu Otorisasi" di List GL Aktif.
  - Hanya muncul untuk pengunggahnya sendiri, dan langsung terhapus tanpa approval checker.
  - Tercatat di audit trail sebagai `UPLOAD_DELETE`. Batch yang sudah diproses checker tidak
    bisa dihapus dari sini; untuk itu pakai menu Hapus Batch.
- **PDF per batch GL Aktif** (REQ_AUSY_00023). Di judul "Tabel List GL Aktif" ada pilihan batch
  dan tombol PDF; PDF dibuka di tab baru. Batch yang ditawarkan disaring per maker.
- **Upload Excel menolak nominal minus, nominal bukan angka, dan total tidak balance**
  (REQ_AUSY_00024). Seluruh berkas ditolak, dengan baris dan nilainya disebut. Dulu berkas tidak
  balance tetap masuk dengan sekadar peringatan, dan nominal yang tidak terbaca menjadi 0.
- **Logo MNC Bank** di sidebar dan halaman login (REQ_AUSY_00025). Dirender dari logo PDF jurnal
  (`static/img/mnc-logo.png`, PNG transparan), karena file SVG/PNG resmi belum ada. Saat sidebar
  diciutkan, logo diganti ikon.
- **Copyright MNC Bank** di kiri bawah semua halaman, termasuk login (REQ_AUSY_00026). Tahunnya
  otomatis.
- **Download ACCTXT di Return Export GL** (REQ_AUSY_00028). Pratinjau isi ACCTXT satu export,
  disusun dengan formatter yang sama seperti Generate, tetapi **tidak** ditulis ke server, tidak
  dikirim ke FTP, dan tidak menandai export sebagai sudah digenerate.
- **Export bulk edit per batch dengan Amount berformat `1.000.000,50`** (REQ_AUSY_00029).
  - Kelola Data GL punya pilihan batch (atau "Semua batch").
  - Saat diunggah ulang, Amount dibaca dalam format Indonesia maupun angka biasa.
  - Amount yang tidak terbaca menolak seluruh berkas. Dulu nilai seperti itu diam-diam menjadi 0.
- **Format PDF jurnal** (REQ_AUSY_00030).
  - "Tr. Account" menjadi "GL Number".
  - Kotak Dibuat/Diperiksa/Disetujui bergaris seperti tabel.
  - SELISIH sejajar dengan TOTAL Dr/Cr.
  - Font sedikit lebih besar.
  - Paket/halaman serta User Input/Tanggal tebal dan lebih besar, dan jarak diperlebar.
  - Judul jurnal (13pt, dulu 10pt) dan logo MNC (1,5×) diperbesar.
  - Jarak antarbaris di semua sel tabel diperlebar (1,8× ukuran font), supaya teks yang turun
    ke baris berikutnya tidak mepet.
  - Ukuran font masih bisa diatur setelah ditinjau QA (konstanta `J_*` di `MncPdfService`).
    PDF Audit Trail tidak berubah.

- **Palet warna MNC Bank** (REQ_AUSY_00027).
  - Sidebar navy `#102D50` (gelap: `#0A1D36`) dengan item aktif emas `#B28628`.
  - Garis emas di bawah header; badge role emas.
  - Tombol utama emas (hover `#C99A33`); tombol outline navy; Hapus merah `#B91C1C`.
  - Header tabel navy bertulisan putih kapital; hover baris `#E6EBF2` / `#1A3F6E`.
  - Badge D navy, C emas. Badge Approved/Pending/Rejected sesuai spesifikasi; warna alert tidak
    ikut diubah supaya paragrafnya tetap terbaca.
  - Mode gelap: latar `#0B1727`, kartu `#112240`.
  - Versi CSS dinaikkan (`app.css?v=17`) supaya browser tidak memakai warna lama dari cache.
- **Logo MNC Bank di halaman login** (REQ_AUSY_00025, dibuka lagi). Logo tampil lebih besar di
  kartu login berlatar putih.
- **Nomor batch upload mulai dari 1 setiap hari** (REQ_AUSY_00031). Format `yyyyMMdd` + 3 digit
  urut (contoh `20261008001`), sehingga tetap unik lintas hari. Batch lama ("01", "02", …) tidak
  berubah.
- **Latar halaman login: foto gedung MNC** (REQ_AUSY_00032), `static/img/login-bg.jpg`.
  - Desktop: foto utuh setinggi layar di kiri, memudar halus ke navy; kartu login di kanan.
  - Layar kecil: foto memenuhi layar dengan overlay gelap; kartu di tengah.
  - Fotonya kecil (584×684, dari tim; bingkai tangkapan layar dibuang). Bila ada foto
    beresolusi lebih tinggi, timpa berkas itu.

- **Logo NALA di pojok kanan bawah halaman login** (REQ_AUSY_00033). Transparan tanpa kotak
  putih (permintaan tim); di layar kecil pindah ke bawah form. Teks logonya biru tua, jadi
  kontrasnya rendah di atas navy; versi logo bertulisan putih dari NALA akan lebih terbaca.
  - Berkas `static/img/nala-logo.png` dibuat dari logo yang dikirim tim (JPG dengan pola
    kotak-kotak tercetak): latarnya dibuang menjadi transparan, dipotong rapat, diperkecil ke
    1356×160 px. Untuk mengganti dengan file resmi, timpa berkas itu.
  - Kalau berkasnya hilang, kotaknya otomatis tersembunyi (tidak tampil gambar rusak).

- **Alur baru: approve checker langsung masuk penampungan EXECUTE** (REQ_AUSY_00038).
  - Dulu: upload → checker approve → maker ajukan export → checker approve export → Execute.
  - Sekarang: upload → checker approve → **penampungan** ("Menunggu digenerate" di Generate File
    ACCTXT) → Execute. Penyusunan ACCTXT, FTP, dan Susun Ulang tidak berubah.
  - Tambah/Ubah dari Kelola Data GL yang di-approve juga langsung masuk penampungan.
- **Ajukan Export dicabut** dari List Data GL (REQ_AUSY_00034), dan **tab Otorisasi Export**
  dari Manajemen Otorisasi GL (REQ_AUSY_00043). Endpoint-nya ikut dicabut.
- **"List GL Aktif" menjadi "List Data GL"** (REQ_AUSY_00035), dan **"Return Export GL" menjadi
  "Return Data"** (REQ_AUSY_00041).
- **List Data GL maker** (REQ_AUSY_00036, 00044): hanya batch upload yang belum diotorisasi, per
  batch, dengan nama file, uploader, dan waktu upload. Batch yang sudah di-approve hilang dari
  sini. Checker tetap melihat data yang sudah diotorisasi untuk penelusuran.
- **Bulk Edit per batch di List Data GL** (REQ_AUSY_00037, 00047). Langsung diterapkan ke batch
  yang belum diotorisasi (tanpa checker, karena checker tetap meng-approve batch-nya), sehingga
  PDF batch ikut berubah.
- **Kelola Data GL** (REQ_AUSY_00039, 00046): Export Excel & Bulk Edit dicabut. Isinya hanya data
  manual yang belum final (pengajuan Tambah yang menunggu, dan data yang di-return). Hapus
  langsung tanpa checker.
- **Menu Hapus Batch disembunyikan** (REQ_AUSY_00040); endpoint-nya ikut dicabut.
- **Return Data** (REQ_AUSY_00042, 00045): data di penampungan (belum dibuat file) dikembalikan ke
  halaman asalnya — batch upload ke List Data GL dan antrian otorisasi, data manual ke Kelola
  Data GL. Tercatat di audit trail; setelah file ACCTXT terbit tidak bisa di-return.
- **Perbaikan:** Ubah dan Bulk Edit kini ikut mengubah `gleqam`/`glotam`, dua kolom nominal yang
  dipakai berkas ACCTXT. Dulu hanya `gltram` yang berubah.
- **Feedback QA atas alur baru (09-10-2026):**
  - **Nomor batch tampil 1, 2, 3 setiap hari** (REQ_AUSY_00031). Di layar, PDF jurnal, Excel, pesan,
    dan audit trail, `20261009001` tampil sebagai **`1`**, tanpa tanggal karena tanggal upload sudah
    tampil di kolomnya sendiri. Nomor di database tidak berubah (tetap unik), tidak ada data yang
    dihapus. Batch format lama tampil apa adanya.
  - **Judul tabel "Upload Menunggu Otorisasi" dihapus** dari List Data GL (REQ_AUSY_00036).
  - **Tabel "Data GL Sudah Diotorisasi" juga dihapus untuk checker** di List Data GL (REQ_AUSY_00036).
    Maker dan checker kini sama-sama hanya melihat batch yang belum diotorisasi. PDF batch GL Aktif
    (`/gl-legacy/batch-pdf`) yang hanya dipakai tabel itu ikut dicabut.
  - **Perbaikan: upload Bulk Edit melempar ke halaman login** (REQ_AUSY_00037, 00047). Form unggahnya
    tidak membawa token CSRF sehingga ditolak Spring Security. Sekarang hasil edit langsung
    terlihat di antrian checker dan tercatat di audit trail (`BULK_EDIT_PENDING`).
  - **Validasi Bulk Edit sebelum diterapkan** (REQ_AUSY_00037). File ditolak utuh, tidak ada data
    yang berubah dan tidak ada yang sampai ke checker, bila:
    - bukan template Bulk Edit (kolom harus ID, GL Code, D/C, Amount, Description, Batch) atau
      bukan hasil Export Excel batch itu;
    - ada Amount minus, GL Code bukan angka, D/C salah, atau ID dobel/bukan milik batch;
    - hasil akhirnya tidak balance (pesan menyebut Debit, Credit, dan selisihnya).
    Pesan kesalahan dibatasi 5 baris pertama + jumlah sisanya; dulu file yang salah menghasilkan
    pesan yang memenuhi layar.
  - **Unduh Excel batch ada di dalam popup Bulk Edit** (REQ_AUSY_00037, 00052): tombol kecil
    "Download" di langkah 1. Teks "Unduh Excel batch" hanya panduan, tidak bisa diklik. Tombol
    Export Excel di baris tabel dicabut.
  - **Kolom Status di List Data GL** (REQ_AUSY_00053):
    - **Menunggu Otorisasi**: batch menunggu checker.
    - **Dikembalikan**: batch di-Return Data checker dari penampungan, menunggu otorisasi lagi;
      nama checker dan alasannya tampil di bawah status.
    - **Rejected**: batch ditolak checker. **Datanya tidak lagi dihapus**: batch keluar dari antrian
      checker, tapi maker masih bisa membuka **PDF**, memperbaikinya lewat **Bulk Edit**, atau
      **Hapus**. Bulk Edit yang valid dan balance otomatis **mengajukan ulang** batch itu, sehingga
      statusnya kembali Menunggu Otorisasi.
    - Checker tidak bisa meng-approve/reject batch yang tidak sedang menunggu otorisasi (mis. yang
      sudah Rejected) lewat URL langsung.
    - Nomor batch yang ditolak/dihapus tidak dipakai ulang di hari yang sama.
  - **Rekapitulasi maker: data yang belum di-approve** (REQ_AUSY_00054). Isinya batch upload dan
    pengajuan Kelola Data GL milik maker itu, berstatus Menunggu Otorisasi, Dikembalikan, atau
    Rejected, berikut catatan checker.
    - Jumlah per status tampil di atas tabel dan bisa diklik untuk menyaring.
    - Filter Sumber (Batch Upload / Kelola Data GL) dan Status; Export Excel ikut filter.
    - Pengajuan Kelola Data GL yang ditolak tampil 7 hari. Batch upload yang ditolak tampil sampai
      diperbaiki atau dihapus.
    - Role lain (checker, dsb.) tetap melihat rekap GL aktif seperti sebelumnya.
  - **Template Bulk Edit mengikuti kolom template MNC** (REQ_AUSY_00057).
    - Download di popup Bulk Edit berisi **Sheet1 saja**: 17 kolom dengan judul & urutan yang sama
      dengan template upload MNC, terisi baris batch itu sebagai **teks biasa** (tanpa sheet
      referensi, tanpa rumus). Kolom A berisi tanggal hari ini.
    - Hasil editnya diperiksa dengan aturan yang **sama dengan Upload Excel** (tanggal posting hari
      ini, nominal, kode, IDR, Debit = Credit).
    - Template MNC tidak punya kolom ID, jadi isi batch **diganti** dengan isi file: baris boleh
      diubah, ditambah, atau dihapus. Nomor batch dan waktu upload tetap.
    - File upload asli (template MNC) juga bisa langsung dipakai untuk Bulk Edit. File format lama
      (6 kolom dengan ID) ditolak.
    - Template Upload Excel: kode angka tanpa nol di depan (COA, Product Code) di sheet referensi
      kini ditulis sebagai angka seperti di AUSY.xls, supaya VLOOKUP Gl_Name tidak #N/A saat kode GL
      diketik.
  - **Nama user, bukan username** (REQ_AUSY_00055). Berlaku di:
    - kolom User dan **teks keterangan** Audit Trail (layar dan PDF) serta Riwayat/Aktivitas di semua
      beranda: "(maker=user)" tampil "(maker=Nama User)";
    - Uploader, Maker, Checker, dan "Oleh" di List Data GL, Manajemen Otorisasi GL, Rekapitulasi,
      Return Data, Generate File ACCTXT, serta antrian otorisasi config;
    - catatan "Dikembalikan/Rejected oleh …", Target User, Pasangan Checker - Maker, Access User;
    - kolom "Dibuat Oleh" di Excel User dan kolom Maker di Excel Rekapitulasi.
    Yang disimpan di database tetap username; nama dipasang saat ditampilkan (filter riwayat mencari
    `maker=<username>` di teks audit). User tanpa nama tampil dengan username-nya. Halaman Manajemen
    User tetap menampilkan kolom Username, karena itu memang data akunnya.
  - **Batch hari sebelumnya tidak bisa di-approve** (REQ_AUSY_00056). Tombol Approve di-grey-out
    untuk batch yang tanggal posting-nya bukan hari ini (label "Lewat hari" + tanggal posting), dan
    ditolak juga di server. Tombol yang terkunci berwarna abu-abu (bukan hijau pudar). Batch itu
    masih bisa di-reject.
    - Maker melihat keterangan "Lewat hari" di List Data GL. Batch itu bisa diperbarui lewat Bulk
      Edit — tanggal posting-nya menjadi hari ini, sehingga bisa di-approve lagi — atau dihapus.
    - Approve juga tetap terkunci bila batch tidak balance; selisihnya tampil di kolom Balance.
  - **"Dikembalikan" menjadi "Menunggu Otorisasi" setelah diedit** (REQ_AUSY_00058). Berlaku untuk
    batch upload yang di-Bulk Edit, dan data Kelola Data GL yang diubah (jenisnya menjadi "Ubah",
    catatan "Diperbaiki setelah dikembalikan … oleh <checker>: <alasan>" tetap terbaca checker).
    Statusnya ikut berubah di List Data GL, Kelola Data GL, dan Rekapitulasi.
  - **Kelola Data GL jadi satu tabel "Pengajuan"** (REQ_AUSY_00046). Tabel "Data Dikembalikan"
    dihapus:
    - Data manual yang di-Return Data langsung diajukan ulang. Data itu muncul di tabel Pengajuan
      (jenis "Dikembalikan", berisi alasan checker) dan di antrian otorisasi checker.
    - **Ubah & Hapus tersedia selama pengajuan menunggu.** Ubah langsung mengganti isi pengajuan
      di sisi checker; nilai lama dan baru tercatat di audit trail (`GL_CHANGE_REQ_UPDATE`).
    - Hapus membatalkan pengajuan (`GL_CHANGE_REQ_CANCEL`). Untuk data yang dikembalikan, datanya
      ikut dihapus (`GL_DELETE`).
  - **Manajemen Otorisasi GL** (REQ_AUSY_00048, 00049): tab berganti nama menjadi "Otorisasi
    Upload Excel" dan "Otorisasi Kelola Data GL". Tab baru **"Otorisasi Manajemen Kode GL"** berisi
    otorisasi Kode GL, yang tidak lagi tercampur dengan Kelola Data GL.
  - **Section "Data GL Sudah Diotorisasi" dihapus dari beranda maker** (REQ_AUSY_00051). Data yang
    sudah diotorisasi tidak lagi dilihat maker. Kartu angka "Baris GL Aktif" tetap ada.
  - REQ_AUSY_00050 (tanggal & jam di kanan atas): sudah ada, lihat poin di bawah.
- **Tanggal & jam berjalan di header** (permintaan tim accounting): ikon kalender +
  `09-10-2026 09:07:13 WIB`, berjalan tiap detik di semua halaman. Berpatokan pada jam server
  (bukan jam laptop pengguna), zona Asia/Jakarta. Disembunyikan di layar sempit.
  CSS dinaikkan ke `app.css?v=18`.

### Migrasi DB
- `V48__req_00034_00046_alur_penampungan.sql`: nama menu, menu Hapus Batch (13) & Ajukan Export
  (604) nonaktif, kolom `t_upload.no_paket`, dan **data lama masuk penampungan**: pengajuan
  export yang masih PENDING disetujui sistem, GL yang sudah diotorisasi tapi belum diajukan
  dimasukkan per batch (data manual per maker).
- `V49__req_00053_status_batch.sql`: tabel `gl_batch_status` (status Dikembalikan/Rejected per
  batch upload, berikut catatan checker). Tabel baru saja; data lama tidak diubah. Batch yang sudah
  ditolak sebelum rilis ini tidak muncul sebagai Rejected (datanya sudah terhapus). Baris batch
  yang ditolak sesudah rilis ini: `gl_tabel.status_dual = 'REJECTED'`, `active = 0`.

### Setelah deploy
- Semua GL lama yang sudah diotorisasi dan belum digenerate muncul di "Menunggu digenerate" hari
  itu juga untuk role EXECUTE.
- Batch upload yang masih menunggu otorisasi dari hari sebelumnya kini tidak bisa di-approve
  (REQ_AUSY_00056). Batch itu perlu ditolak, diperbarui maker lewat Bulk Edit, atau dihapus.
- JAR versi sebelumnya disimpan di `/opt/ausy/backup/ausy-2.0.0.jar.dev-20261008-1`.
  Mengembalikan JAR saja tidak membatalkan V48/V49.
- Migrasi berikutnya: **V50**.

### Catatan untuk developer
- Penampungan = `gl_export_request` APPROVED tanpa `file_name`, dibuat oleh `StagingService`.
- Status baru `gl_tabel.status_dual = 'RETURNED'` untuk data manual yang dikembalikan; GL Aktif
  tidak menghitungnya. Data upload yang dikembalikan kembali ke `PENDING`.
- Nomor batch baru dibuat di satu tempat (`UploadService`) lewat `maxUrutBatchHarian`.
  `maxPaket()` dihapus: cast seluruh `no_paket` ke int akan error begitu nomor 11 digit ada.
  Nomor batch tidak boleh memuat `-`, karena referensi GLTRRF dipecah per `-` saat mencetak.
- Format nominal Indonesia ditulis dan dibaca lewat satu kelas, `Nominal`.
- Label nomor batch dibuat di satu tempat, `BatchLabel.label(...)` (di template: `${@batchLabel.of(x)}`).
  Nama berkas PDF dan GLTRRF tetap memakai nomor asli.
- Status baru `gl_change_request.status = 'CANCELLED'` untuk pengajuan yang dihapus maker. Pengajuan
  ulang otomatis untuk data yang dikembalikan berupa EDIT dengan sebelum = sesudah, dan
  `note_maker` diawali `Dikembalikan dari penampungan`.
- Form POST wajib memakai `th:action` (bukan `action="#"`), supaya token CSRF disisipkan.
- `GlTabel.batchKey()` adalah kunci batch yang sama dengan rekap `approvedPakets()`.
- PDFBox **tidak** ditambahkan ke project; hanya dipakai sekali untuk merender logo.

---

## dev-20261008-1 — 08-10-2026 10:25

Commit `a7f4d81`. DB naik dari v45 ke **v47**.

### Perubahan
- **Role baru EXECUTE untuk data center** (REQ_AUSY_00016).
  - Role `EXECUTE` (level `EXECUTE`, inisial `EXE`) memegang menu **Generate File ACCTXT**,
    beserta aksi Susun Ulang dan Hari Ini.
  - Menu itu **dicabut dari admin** (role CONFIG / SETTING), dari cetakan role dan dari hak
    per user.
  - Role EXECUTE muncul di dropdown Role saat Tambah/Ubah User. User-nya tetap dibuat admin dan
    diotorisasi Admin Approval.
  - Jejak generate ACCTXT oleh user EXECUTE terlihat di Audit Trail sisi Admin Approval.
- **Role EXECUTE dirapikan** (REQ_AUSY_00018, 00019, 00020).
  - User EXECUTE tidak muncul di Access User, karena haknya datang dari cetakan role.
  - Sesudah login, user EXECUTE langsung diantar ke Generate File ACCTXT, dan tautan Home
    disembunyikan.
  - Pilihan Generate File ACCTXT tidak lagi muncul di Access User role admin (section menu
    dibetulkan jadi `EXECUTE`).
- **Download Excel di menu User** (REQ_AUSY_00021). Kolom: Username, Nama, Role, Status, Email,
  Dibuat Oleh. Unduhannya tercatat di audit trail.
- **User baru dibuat tanpa hak akses** (REQ_AUSY_00017). Admin mencentang sendiri di Access
  User. User yang pindah role tetap disusun dari cetakan role barunya.
  - **Membatalkan** BUG_AUSY_00143 (hak awal = cetakan role) dan BUG_AUSY_00184 (Upload Excel
    menyala untuk maker baru). Maker baru tanpa Upload Excel adalah perilaku yang disengaja.
  - **Pengecualian: role EXECUTE.** User EXECUTE baru langsung mendapat Generate File ACCTXT
    dari cetakan rolenya. Tanpa pengecualian ini ia tidak punya hak dan tidak tampil di Access
    User, sehingga tidak ada yang bisa menerbitkan berkas ACCTXT.

### Migrasi DB
- `V46__req_00016_role_execute.sql`. id role diambil dari sequence, bukan angka tetap.
- `V47__req_00020_section_acctxt_execute.sql`. Section menu 43, 608, 610 jadi `EXECUTE`.

### Setelah deploy
- Belum ada user EXECUTE. Admin membuat user ber-role EXECUTE, lalu Admin Approval
  mengotorisasinya. Sebelum itu tidak ada yang bisa generate file ACCTXT.
- Migrasi berikutnya: **V48**.

---

## dev-20261007-1 — 07-10-2026 15:23

Commit `11fb17d`. DB tetap v45 (tidak ada migrasi baru).

### Perubahan
- **Nominal di audit trail** (REQ_AUSY_00012). Log `UPLOAD` di Audit Trail dan card Aktivitas
  Terakhir kini menulis `D=5.000.000,00`, bukan `D=5000000`. Log yang sudah ada tidak diubah.
- **Tombol Download PDF di List GL Aktif dicabut** (REQ_AUSY_00013), beserta endpoint
  `/gl-legacy/pdf`.
- **Section "Upload Menunggu Otorisasi" di List GL Aktif** (REQ_AUSY_00014).
  - Isinya batch upload yang belum diotorisasi checker, disaring per maker.
  - Tiap batch punya tombol PDF jurnalnya sendiri: `/gl-legacy/pending/{paket}/pdf`.
  - Batch milik maker lain menghasilkan 404.
- **Judul "Tabel List GL Aktif"** di atas tabel GL Aktif (REQ_AUSY_00015), supaya tidak terbaca
  sebagai lanjutan section pending di atasnya.
- **Card Aktivitas Terakhir checker disamakan dengan Audit Trail** (BUG_AUSY_00192).
  - Checker hanya melihat jejaknya sendiri dan maker pasangannya.
  - Dulu card ini menyaring per group, sehingga semua maker dan checker lain ikut terlihat.

### Operasional
- **Audit log dibersihkan setelah deploy** (BUG_AUSY_00191). Semua 39 baris `tlog_transaction`
  (06-10-2026 09:26 s/d 07-10-2026 13:47, id sampai 279) dihapus, termasuk 5 log `UPLOAD`
  berformat nominal lama. Log baru memakai format `5.000.000,00`.
  - Backup: `/opt/ausy/backup-tlog_transaction-20261007.csv` di server aplikasi (chmod 600).
  - `tlog_activity` (log login) tidak disentuh.

### Catatan untuk developer
- **ID bug ganda:** `BUG_AUSY_00191` dan `BUG_AUSY_00192` juga dipakai di `1be7a04` untuk
  masalah lain (penyaringan simbol dan kolom kode angka). Cocokkan dengan tracker QA.

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
