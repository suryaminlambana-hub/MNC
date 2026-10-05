-- Reset akun untuk UAT: hanya dua akun config yang tersisa, keduanya Akun Utama.
--
--   mncsuperadmin : role CONFIG APPROVER (id_group 2)
--   mncadmin      : role CONFIG / SETTING (id_group 1)
--
-- User ID disimpan huruf kecil (UserService.add dan AusyUserDetailsService sama-sama
-- melowercase), jadi login "MNCsuperadmin" / "MNCadmin" tetap cocok. Password hanya disimpan
-- sebagai hash BCrypt.
--
-- SEMUA user lain dihapus, termasuk akun bawaan (admin, adminapv, spv, user) dan akun MNC
-- sebelumnya (adminmnc, adminapvmnc), berikut hak akses (user_menu) dan pasangan
-- checker-maker-nya. Akun maker/checker untuk UAT dibuat ulang lewat halaman User.
INSERT INTO users (user_id, pass, nama, status_account, email, id_group, level, addby, protected_account)
VALUES
 ('mncsuperadmin', '$2a$10$YSpV2p1aREpap4Jxt2K2We0BYIlEItMa2HMHB266t0N1g/0NjUWLa',
  'MNC Super Admin', 1, 'mncsuperadmin@mncbank.co.id', 2, 'ADMIN', 'system', 1),
 ('mncadmin',      '$2a$10$cFjboxw9912Yvn7FlnThBeMJkd0VDq0XHo/FhLDDfEKvzJC8J3cYG',
  'MNC Admin',       1, 'mncadmin@mncbank.co.id',      1, 'SUPERADMIN', 'system', 1)
ON CONFLICT (user_id) DO UPDATE
   SET pass = EXCLUDED.pass, nama = EXCLUDED.nama, status_account = 1,
       id_group = EXCLUDED.id_group, level = EXCLUDED.level, protected_account = 1;

-- Hak akses awal = cetakan role-nya (sama seperti user baru lewat halaman Access User).
DELETE FROM user_menu WHERE user_id IN ('mncsuperadmin', 'mncadmin');
INSERT INTO user_menu (user_id, id_menu)
SELECT u.user_id, gm.id_menu
  FROM users u JOIN group_menu gm ON gm.id_group = u.id_group
 WHERE u.user_id IN ('mncsuperadmin', 'mncadmin')
ON CONFLICT (user_id, id_menu) DO NOTHING;

-- Hapus semua selain keduanya.
DELETE FROM user_menu     WHERE user_id NOT IN ('mncsuperadmin', 'mncadmin');
DELETE FROM checker_maker WHERE checker NOT IN ('mncsuperadmin', 'mncadmin')
                             OR maker   NOT IN ('mncsuperadmin', 'mncadmin');
DELETE FROM users         WHERE user_id NOT IN ('mncsuperadmin', 'mncadmin');
