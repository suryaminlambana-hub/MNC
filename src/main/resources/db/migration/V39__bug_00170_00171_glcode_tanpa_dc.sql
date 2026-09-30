-- BUG_AUSY_00170 & BUG_AUSY_00171 — kolom Debit/Credit dilepas dari master Kode GL.
--
-- Di AUSY.xls milik MNC daftar kodenya (sheet COA) hanya berisi nomor akun dan namanya; tidak
-- ada sisi D/C. Permintaan penguji mengikuti bentuk itu: satu nomor GL berlaku untuk dua-duanya,
-- dan sisi D/C ditentukan oleh baris transaksinya, bukan oleh masternya.
--
-- Akibatnya kunci uniknya berubah: dulu (code, position) sehingga 100-D dan 100-C adalah dua
-- baris berbeda, sekarang cukup code.

-- Kode yang sebelumnya terdaftar dua kali karena beda sisi menjadi satu baris. Yang dipertahankan
-- baris dengan id terkecil — yang lebih dulu dibuat — dan deskripsinya ikut yang itu. Kalau sisi
-- yang belakangan punya deskripsi berbeda, perbedaan itu memang tidak bisa dipertahankan lagi
-- setelah dua baris dilebur; pilihan "yang lebih dulu" dipakai supaya hasilnya bisa ditebak.
DELETE FROM gl_code a
 USING gl_code b
 WHERE a.code = b.code
   AND a.id > b.id;

ALTER TABLE gl_code DROP CONSTRAINT IF EXISTS uq_glcode_pos;

-- Kolomnya sendiri TIDAK dihapus, hanya tidak dipakai lagi oleh aplikasi. Isinya dibiarkan
-- sebagai catatan bagaimana kode itu dulu didaftarkan; menghapusnya tidak bisa dibatalkan kalau
-- MNC kembali membutuhkan pemisahan D/C.
ALTER TABLE gl_code ALTER COLUMN position DROP NOT NULL;

ALTER TABLE gl_code ADD CONSTRAINT uq_glcode_code UNIQUE (code);
