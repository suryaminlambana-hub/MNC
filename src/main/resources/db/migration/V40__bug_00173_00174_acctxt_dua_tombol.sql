-- BUG_AUSY_00173 & BUG_AUSY_00174 — halaman Generate File ACCTXT dirapikan jadi dua tombol.

-- 00173: bagian "File ACCTXT yang sudah dibuat" di-take out, dan fitur Populate Ulang di
-- dalamnya dibatalkan. Baris menunya ikut dimatikan: aksi admin yang bisa mengubah data tapi
-- tidak punya tampilan hanya menyisakan jalan masuk yang tidak terlihat siapa pun.
UPDATE menu SET active = 0 WHERE id_menu = 609;
DELETE FROM group_menu WHERE id_menu = 609;
DELETE FROM user_menu  WHERE id_menu = 609;

-- 00174: tombol kedua — menyusun berkas dari export yang disetujui HARI INI. Perlu baris
-- ACTION sendiri karena hak akses dicocokkan per ruas URL: /admin/acctxt/generate-all bukan
-- bagian dari /admin/acctxt/generate, jadi ACTION 608 tidak mencakupnya.
INSERT INTO menu (id_menu, nama_menu, url, icon, urutan, parent, active, kind, section) VALUES
 (610, 'Generate File ACCTXT Hari Ini', '/admin/acctxt/generate-today', NULL, 10, 600, 1, 'ACTION', 'ADMIN')
ON CONFLICT (id_menu) DO NOTHING;

INSERT INTO group_menu (id_group, id_menu)
SELECT 1, 610
WHERE NOT EXISTS (SELECT 1 FROM group_menu WHERE id_group = 1 AND id_menu = 610);

INSERT INTO user_menu (user_id, id_menu)
SELECT u.user_id, 610 FROM users u
WHERE u.id_group = 1
  AND NOT EXISTS (SELECT 1 FROM user_menu um WHERE um.user_id = u.user_id AND um.id_menu = 610);
