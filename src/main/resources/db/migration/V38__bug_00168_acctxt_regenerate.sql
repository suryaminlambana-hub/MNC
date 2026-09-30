-- BUG_AUSY_00168 — tombol "Populate Ulang" pada file ACCTXT yang sudah dibuat.
--
-- Didaftarkan sebagai ACTION tersendiri, bukan dibiarkan ikut halaman /admin/acctxt, dengan
-- alasan yang sama seperti aksi generate (menu 608): menerbitkan ulang berkas ke folder yang
-- dibaca core banking adalah tindakan, bukan sekadar melihat layar, jadi haknya harus bisa
-- dicabut tanpa ikut menutup halamannya.
INSERT INTO menu (id_menu, nama_menu, url, icon, urutan, parent, active, kind, section) VALUES
 (609, 'Populate Ulang File ACCTXT', '/admin/acctxt/regenerate', NULL, 9, 600, 1, 'ACTION', 'ADMIN')
ON CONFLICT (id_menu) DO NOTHING;

-- Diberikan ke group yang sudah memegang aksi generate (608), supaya tidak ada admin yang
-- melihat tombolnya lalu ditolak saat menekannya.
INSERT INTO group_menu (id_group, id_menu)
SELECT gm.id_group, 609 FROM group_menu gm
 WHERE gm.id_menu = 608
   AND NOT EXISTS (SELECT 1 FROM group_menu x WHERE x.id_group = gm.id_group AND x.id_menu = 609);

-- Sidebar & izin URL dibaca dari user_menu (BUG_AUSY_00143).
INSERT INTO user_menu (user_id, id_menu)
SELECT um.user_id, 609 FROM user_menu um
 WHERE um.id_menu = 608
   AND NOT EXISTS (SELECT 1 FROM user_menu x WHERE x.user_id = um.user_id AND x.id_menu = 609);
