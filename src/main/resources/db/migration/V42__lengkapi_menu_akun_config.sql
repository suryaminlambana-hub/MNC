-- Lengkapi menu dua akun config setelah V41.
--
-- V41 menyalin hak akses awal dari cetakan role (group_menu). Di DEV cetakan CONFIG / SETTING
-- ternyata sudah menyimpang dari standar: mncadmin hanya melihat Pasangan Checker - Maker dan
-- Generate File ACCTXT, sedangkan User, Role, Menu, dan Access User tidak muncul. Migrasi ini
-- mengembalikan cetakan kedua role config ke standar, lalu menyamakan hak kedua akun.
--
-- Hanya MENAMBAH hak yang kurang (ON CONFLICT DO NOTHING); tidak ada yang dicabut. Baris menu
-- yang tidak ada di tabel menu dilewati lewat JOIN, bukan memicu error.

-- Menu halaman admin harus aktif supaya tampil di sidebar.
UPDATE menu SET active = 1 WHERE id_menu IN (2, 3, 4, 5, 42, 43, 701, 702, 703, 35, 37, 713);

-- Cetakan role: CONFIG / SETTING (1) dan CONFIG APPROVER (2).
INSERT INTO group_menu (id_group, id_menu)
SELECT t.id_group, m.id_menu
  FROM (VALUES (1, 2), (1, 3), (1, 4), (1, 5), (1, 42), (1, 43), (1, 608), (1, 610),
               (1, 701), (1, 702), (1, 703),
               (2, 35), (2, 37), (2, 605), (2, 702), (2, 713)) AS t(id_group, id_menu)
  JOIN menu m ON m.id_menu = t.id_menu
 WHERE NOT EXISTS (SELECT 1 FROM group_menu g WHERE g.id_group = t.id_group AND g.id_menu = t.id_menu);

-- Hak berlaku per user (user_menu): samakan dengan cetakan role-nya.
INSERT INTO user_menu (user_id, id_menu)
SELECT u.user_id, gm.id_menu
  FROM users u JOIN group_menu gm ON gm.id_group = u.id_group
 WHERE u.user_id IN ('mncsuperadmin', 'mncadmin')
ON CONFLICT (user_id, id_menu) DO NOTHING;
