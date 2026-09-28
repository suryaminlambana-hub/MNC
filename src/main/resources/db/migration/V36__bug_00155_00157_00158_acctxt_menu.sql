-- BUG_AUSY_00155, 00157, 00158 — dua menu baru untuk alur export yang sekarang dua tahap:
-- checker menyetujui (status), admin yang menerbitkan file ACCTXT.

-- 00155 + 00158: menu admin "Generate File ACCTXT" (halaman + aksi prosesnya).
INSERT INTO menu (id_menu, nama_menu, url, icon, urutan, parent, active, kind, section) VALUES
 (43, 'Generate File ACCTXT', '/admin/acctxt', 'fa-solid fa-file-export', 95, 200, 1, 'MENU', 'ADMIN')
ON CONFLICT (id_menu) DO NOTHING;

INSERT INTO menu (id_menu, nama_menu, url, icon, urutan, parent, active, kind, section) VALUES
 (608, 'Generate File ACCTXT (proses)', '/admin/acctxt/generate', NULL, 8, 600, 1, 'ACTION', 'ADMIN')
ON CONFLICT (id_menu) DO NOTHING;

-- 00157: menu checker "Return Export GL".
-- Halaman sendiri, bukan tombol di Audit Trail: audit trail tetap murni catatan dan tidak
-- dipakai untuk mengubah data. Aksi POST /gl-export/return sudah tercakup pemilik URL
-- terpanjang milik ACTION 602 (/gl-export), jadi tidak perlu baris ACTION baru.
INSERT INTO menu (id_menu, nama_menu, url, icon, urutan, parent, active, kind, section) VALUES
 (38, 'Return Export GL', '/gl-export/return-list', 'fa-solid fa-rotate-left', 30, 400, 1, 'MENU', 'CHECKER')
ON CONFLICT (id_menu) DO NOTHING;

-- Hak akses: group 1 = admin, group 4 = checker (lihat seed V1).
INSERT INTO group_menu (id_group, id_menu)
SELECT g.id_group, m.id_menu FROM (VALUES (1, 43), (1, 608), (4, 38)) AS g(id_group, id_menu)
JOIN menu m ON m.id_menu = g.id_menu
WHERE NOT EXISTS (SELECT 1 FROM group_menu x WHERE x.id_group = g.id_group AND x.id_menu = g.id_menu);

-- Sidebar dibaca dari user_menu (BUG_AUSY_00143), jadi hak per user ikut diberikan ke
-- semua user yang sudah ada di group tersebut.
INSERT INTO user_menu (user_id, id_menu)
SELECT u.user_id, v.id_menu
FROM users u JOIN (VALUES (1, 43), (1, 608), (4, 38)) AS v(id_group, id_menu) ON u.id_group = v.id_group
WHERE NOT EXISTS (SELECT 1 FROM user_menu um WHERE um.user_id = u.user_id AND um.id_menu = v.id_menu);
