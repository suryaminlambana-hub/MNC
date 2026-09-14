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
