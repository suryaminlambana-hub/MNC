-- BUG_AUSY_00143 — hak akses pindah dari role ke USER.
--
-- Sebelumnya satu-satunya sumber hak akses adalah group_menu: semua user dengan role yang sama
-- pasti memegang menu yang sama persis. Permintaan MNC: yang diatur adalah USER-nya, sehingga
-- dua maker bisa dibedakan haknya tanpa harus dibuatkan role baru.
--
-- Pembagian perannya sekarang:
--   group_menu : cetakan milik role — dipakai sebagai nilai awal user baru dan untuk pertanyaan
--                setingkat role (mis. "role ini penyetuju config?"). TIDAK dihapus.
--   user_menu  : hak yang benar-benar berlaku saat user membuka aplikasi (sidebar, izin URL, card).
CREATE TABLE IF NOT EXISTS user_menu (
    id      BIGSERIAL   PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL,
    id_menu INTEGER     NOT NULL,
    CONSTRAINT user_menu_uq UNIQUE (user_id, id_menu)
);
CREATE INDEX IF NOT EXISTS idx_user_menu_user ON user_menu(user_id);

-- Nilai awal: setiap user mewarisi persis hak role-nya, jadi tidak ada satu pun user yang
-- kehilangan menu pada saat migrasi ini dijalankan.
INSERT INTO user_menu (user_id, id_menu)
SELECT u.user_id, gm.id_menu
  FROM users u JOIN group_menu gm ON gm.id_group = u.id_group
ON CONFLICT (user_id, id_menu) DO NOTHING;

-- Halaman /access sekarang memilih user, bukan role.
UPDATE menu SET nama_menu = 'Access User' WHERE id_menu = 5;
