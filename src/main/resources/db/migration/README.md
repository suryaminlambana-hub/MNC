# Aturan migrasi Flyway

**Jangan pernah mengubah berkas migrasi yang sudah pernah dijalankan** — termasuk barisan
komentarnya. Flyway menghitung checksum dari seluruh isi berkas, jadi mengubah satu komentar
saja membuat aplikasi menolak start di lingkungan yang sudah menerapkan versi tersebut:

```
FlywayValidateException: Migration checksum mismatch for migration version 23
```

Kejadian nyata: V23 diedit (hanya komentarnya) setelah sempat diterapkan di DEV, dan aplikasi
gagal start sampai berkasnya dikembalikan persis seperti yang ada di database DEV.

Kalau perlu mengubah hasil sebuah migrasi, buat migrasi **baru** dengan nomor berikutnya.
Nomor migrasi juga harus selalu lebih besar dari yang sudah ada di DEV — migrasi dengan nomor
lebih kecil (out of order) akan ditolak.

Acuan versi yang sedang berjalan di DEV dapat dilihat dari log saat aplikasi start
(`Successfully applied ... now at version vNN`).

## Penyelarasan otomatis

Sejak perbaikan ini aplikasi menjalankan `flyway.repair()` sebelum `migrate()` (lihat
`config/FlywayConfig.java`). Checksum di tabel riwayat otomatis diselaraskan dengan berkas
migrasi, sehingga aplikasi tidak lagi mati hanya karena perbedaan komentar antar lingkungan.

Ini **bukan** izin mengubah migrasi yang sudah dijalankan: kalau yang diubah perintah SQL-nya,
database lama tetap tidak ikut berubah dan skemanya menyimpang diam-diam tanpa peringatan.
Perubahan hasil migrasi tetap harus lewat migrasi baru.

Untuk lingkungan yang menghendaki kegagalan keras saat ada penyimpangan (mis. produksi),
setel `AUSY_FLYWAY_AUTO_REPAIR=false` di `.env`.
