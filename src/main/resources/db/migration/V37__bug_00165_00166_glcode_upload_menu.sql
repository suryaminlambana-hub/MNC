-- BUG_AUSY_00165 & BUG_AUSY_00166 — import Kode GL dipisah dari Manajemen Kode GL.
--
-- Di halaman Manajemen Kode GL, import CSV berdiri sebaris dengan tambah/ubah/hapus sehingga
-- satu layar mencampur dua hal yang beda sifatnya: perawatan kode satu per satu, dan pemuatan
-- massal saat migrasi data. Permintaan penguji: wewenang maker di halaman itu tinggal
-- create/update/delete, dan uploadnya pindah ke menunya sendiri.
--
-- Tetap dipegang maker (group 3), sama seperti Manajemen Kode GL — yang dipisah halamannya,
-- bukan siapa yang berhak. Dual control juga tidak berubah: import tetap jadi permintaan
-- perubahan yang menunggu Checker.
INSERT INTO menu (id_menu, nama_menu, url, icon, urutan, parent, active, kind, section) VALUES
 (23, 'Upload Kode GL', '/gl-code/upload', 'fa-solid fa-file-import', 3, 500, 1, 'MENU', 'MAKER')
ON CONFLICT (id_menu) DO NOTHING;

INSERT INTO group_menu (id_group, id_menu)
SELECT 3, 23
WHERE NOT EXISTS (SELECT 1 FROM group_menu WHERE id_group = 3 AND id_menu = 23);

-- Sidebar dibaca dari user_menu (BUG_AUSY_00143), jadi hak per user ikut diberikan ke maker
-- yang sudah ada — kalau tidak, menunya cuma tampak di halaman Access User.
INSERT INTO user_menu (user_id, id_menu)
SELECT u.user_id, 23 FROM users u
WHERE u.id_group = 3
  AND NOT EXISTS (SELECT 1 FROM user_menu um WHERE um.user_id = u.user_id AND um.id_menu = 23);

-- Aksi import & unduh template ada di bawah /gl-code/upload, jadi pemilik URL terpanjang
-- (AccessService) sudah menu ini — tidak perlu baris ACTION sendiri, dan yang hanya punya
-- Manajemen Kode GL tidak ikut kebagian jalur importnya.
