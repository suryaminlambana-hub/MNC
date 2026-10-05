-- BUG_AUSY_00183, 00184, 00186 — tiga perbaikan data dari putaran UAT berikutnya.

-- 00183: tag akun Admin dan Admin Approval MNC tertukar.
-- Tag di pojok kanan atas layar membaca users.level. V41 menulisnya terbalik: mncadmin (role
-- CONFIG / SETTING, id_group 1) bertuliskan SUPERADMIN, sedangkan mncsuperadmin (role CONFIG
-- APPROVER, id_group 2) bertuliskan ADMIN. Disamakan menurut role-nya. Hanya baris yang memang
-- bertuliskan ADMIN / SUPERADMIN yang disentuh; level lain (mis. CONFIG) dibiarkan.
-- users.level hanya tampilan: otorisasi memakai user_menu, bukan kolom ini.
UPDATE users
   SET level = CASE id_group WHEN 1 THEN 'ADMIN' WHEN 2 THEN 'SUPERADMIN' END
 WHERE id_group IN (1, 2)
   AND level IN ('ADMIN', 'SUPERADMIN');

-- 00184: Upload Excel (menu 10) menyala sejak awal untuk setiap maker.
-- Maker = role yang memegang salah satu halaman pengisi data GL (daftar yang sama dengan
-- AccessService.GL_SUBMIT_PAGES). Cetakan role yang belum memuat Upload Excel dilengkapi, lalu
-- hak per user (yang dibaca sidebar) menyusul untuk maker yang sudah ada. Hanya MENAMBAH hak.
INSERT INTO group_menu (id_group, id_menu)
SELECT DISTINCT gm.id_group, 10
  FROM group_menu gm JOIN menu m ON m.id_menu = gm.id_menu
 WHERE trim(m.url) IN ('/upload', '/nala', '/gl-data', '/gl-delete', '/gl-legacy/export-request')
   AND m.active = 1
   AND EXISTS (SELECT 1 FROM menu WHERE id_menu = 10)
   AND NOT EXISTS (SELECT 1 FROM group_menu x WHERE x.id_group = gm.id_group AND x.id_menu = 10);

INSERT INTO user_menu (user_id, id_menu)
SELECT u.user_id, 10
  FROM users u JOIN group_menu gm ON gm.id_group = u.id_group AND gm.id_menu = 10
 WHERE EXISTS (SELECT 1 FROM menu WHERE id_menu = 10)
   AND EXISTS (SELECT 1 FROM group_menu g2 JOIN menu m2 ON m2.id_menu = g2.id_menu
                WHERE g2.id_group = u.id_group
                  AND trim(m2.url) IN ('/nala', '/gl-data', '/gl-delete', '/gl-legacy/export-request'))
   AND NOT EXISTS (SELECT 1 FROM user_menu um WHERE um.user_id = u.user_id AND um.id_menu = 10);

-- 00186: semua Kode GL dihapus untuk keperluan UAT (data awal kosong, diisi lagi lewat UAT).
-- Pengajuan perubahan Kode GL yang masih menunggu ikut dibatalkan: targetnya sudah tidak ada,
-- jadi kalau dibiarkan checker menyetujui sesuatu yang menunjuk ke kode yang tak ada lagi.
-- Riwayat yang sudah diputuskan (APPROVED / REJECTED) dibiarkan sebagai catatan.
-- Data GL (gl_tabel) tidak disentuh.
DELETE FROM gl_change_request WHERE target = 'GL_CODE' AND status = 'PENDING';
DELETE FROM gl_code;
