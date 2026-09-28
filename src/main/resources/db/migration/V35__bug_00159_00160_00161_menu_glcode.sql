-- BUG_AUSY_00159, 00160, 00161 — tiga permintaan yang semuanya soal isi menu & data awal.

-- 00159: menu "Manajemen Kode GL" dikembalikan seperti sebelumnya.
-- Dulu di-take-out dengan active=0 dan hak aksesnya ikut dicabut, jadi menghidupkan
-- kembali berarti mengembalikan dua-duanya.
UPDATE menu SET active = 1 WHERE id_menu = 21;

INSERT INTO group_menu (id_group, id_menu)
SELECT 3, 21
WHERE NOT EXISTS (SELECT 1 FROM group_menu WHERE id_group = 3 AND id_menu = 21);

-- Hak per user ikut dikembalikan untuk semua maker yang ada, supaya menunya benar-benar
-- muncul di sidebar (sidebar dibaca dari user_menu, bukan group_menu — BUG_AUSY_00143).
INSERT INTO user_menu (user_id, id_menu)
SELECT u.user_id, 21 FROM users u
WHERE u.id_group = 3
  AND NOT EXISTS (SELECT 1 FROM user_menu um WHERE um.user_id = u.user_id AND um.id_menu = 21);

-- 00161: menu "NALA Seamless Upload" di-take-out — tidak dibutuhkan.
-- Pola yang sama seperti take-out menu 21 dulu: active=0 + hak aksesnya dicabut, sehingga
-- menunya hilang dari sidebar maupun dari daftar centang di halaman Access User.
UPDATE menu SET active = 0 WHERE id_menu = 11;
DELETE FROM group_menu WHERE id_menu = 11;
DELETE FROM user_menu  WHERE id_menu = 11;

-- 00160: kode GL dikosongkan sejak awal.
-- V1 & V6 dulu menanam kode contoh supaya layar tidak kosong saat demo; sekarang justru
-- dianggap salah — daftar kode GL harus mulai dari nol dan diisi sendiri lewat Manajemen
-- Kode GL atau import CSV. Hanya baris tanaman sistem yang dihapus; kode yang sudah
-- dibuat orang dibiarkan.
DELETE FROM gl_code WHERE created_by = 'system';
