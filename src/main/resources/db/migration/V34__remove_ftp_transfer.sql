-- Pengiriman FTP dihapus dari aplikasi.
--
-- Hasil export sekarang HANYA berupa file yang ditulis ke folder hasil export di server
-- (AUSY_EXPORT_DIR, bawaan C:/inetpub/ftproot di Windows) dan core banking mengambilnya dari
-- sana. Tidak ada lagi push FTP, antrian kirim, tombol kirim ulang, maupun setelan host/kredensial
-- FTP — kelas, halaman, dan card-nya ikut dihapus dari aplikasi.

-- Card & aksi yang isinya khusus FTP tidak lagi punya pemilik di aplikasi.
DELETE FROM group_menu WHERE id_menu IN (606, 607, 704, 708);
DELETE FROM user_menu  WHERE id_menu IN (606, 607, 704, 708);
UPDATE menu SET active = 0 WHERE id_menu IN (606, 607, 704, 708);

-- Setelan FTP: tidak ada satu pun kode yang membacanya lagi, dan justru baris peninggalan di
-- sinilah yang dulu diam-diam menyalakan FTP dengan kredensial yang tidak bisa dilihat siapa pun
-- (BUG_AUSY_00141).
DROP TABLE IF EXISTS ftp_config;

-- Tabel export_transfer DIPERTAHANKAN: isinya bukan urusan FTP, melainkan catatan file hasil
-- export (siapa maker, siapa checker, berapa baris, nama file) yang dipakai "Riwayat Export"
-- maker sebagai bukti dual-control. Statusnya kini selalu SENT begitu file-nya terbentuk.
UPDATE export_transfer SET status = 'SENT', error = NULL WHERE status <> 'SENT';
