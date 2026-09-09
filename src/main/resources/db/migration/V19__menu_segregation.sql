-- Pemisahan menu per fungsi role (BUG_AUSY_00115).
--
-- Sebelumnya parent "Verification" (400) menampung otorisasi operasional (GL), otorisasi
-- administratif (user/config), dan Audit Trail sekaligus — jadi menu Maker/Checker dan
-- Admin/Admin Approval terlihat bercampur. Sekarang dipisah:
--   Operasional (Maker & Checker) : Import(100), Data(300), GL(500), Otorisasi GL(400)
--   Administratif (Admin & Admin Approval) : Master Data(200), Otorisasi Administratif(450)
--   Bersama kedua sisi            : Audit Trail(460) — isinya tetap tersaring per sisi
UPDATE menu SET nama_menu = 'Otorisasi GL', icon = 'fa-solid fa-circle-check' WHERE id_menu = 400;

INSERT INTO menu (id_menu, nama_menu, url, parent, urutan, icon, active, kind) VALUES
  (450, 'Otorisasi Administratif', NULL, 0, 45, 'fa-solid fa-user-shield', 1, 'MENU'),
  (460, 'Audit Trail',             NULL, 0, 46, 'fa-solid fa-clock-rotate-left', 1, 'MENU')
ON CONFLICT (id_menu) DO NOTHING;

UPDATE menu SET parent = 450 WHERE id_menu = 35;   -- Otorisasi User  -> administratif
UPDATE menu SET parent = 460 WHERE id_menu = 34;   -- Audit Trail     -> parent sendiri

SELECT setval(pg_get_serial_sequence('menu','id_menu'),
              GREATEST((SELECT MAX(id_menu) FROM menu), 1000));
