-- Audit Trail dipecah per role (BUG_AUSY_00115, feedback terakhir).
--
-- Sebelumnya "Audit Trail" satu item akses yang dipakai Checker maupun Admin Approval, dan isinya
-- ditentukan dengan menebak sisi kerja group dari menu lain yang dipegangnya. Sekarang tegas:
-- dua item terpisah, masing-masing punya halaman sendiri dengan isi yang sudah tetap.
UPDATE menu SET nama_menu = 'Audit Trail — Maker & Checker', section = 'CHECKER' WHERE id_menu = 34;

INSERT INTO menu (id_menu, nama_menu, url, parent, urutan, icon, active, kind, section) VALUES
  (37, 'Audit Trail — Admin & Admin Approval', '/audit-admin', 460, 2,
       'fa-solid fa-clock-rotate-left', 1, 'MENU', 'ADMIN_APV')
ON CONFLICT (id_menu) DO NOTHING;

-- Card "Aktivitas Terbaru" ikut dipecah, sumbernya audit trail yang sama.
UPDATE menu SET nama_menu = 'Aktivitas Terbaru — Maker & Checker', section = 'CHECKER', parent = 700
 WHERE id_menu = 711;
INSERT INTO menu (id_menu, nama_menu, url, parent, urutan, active, kind, section) VALUES
  (713, 'Aktivitas Terbaru — Admin & Admin Approval', 'card:audit-recent-admin', 750, 5, 1, 'CARD', 'ADMIN_APV')
ON CONFLICT (id_menu) DO NOTHING;

-- Admin Approval pindah ke pasangan administratif; Checker tetap di yang operasional.
DELETE FROM group_menu WHERE id_group = 2 AND id_menu IN (34, 711);
INSERT INTO group_menu (id_group, id_menu)
SELECT v.g, v.m FROM (VALUES (2, 37), (2, 713)) AS v(g, m)
WHERE NOT EXISTS (SELECT 1 FROM group_menu gm WHERE gm.id_group = v.g AND gm.id_menu = v.m);

-- Parent 780 ("Card Dashboard — Umum") tidak terpakai lagi.
UPDATE menu SET active = 0 WHERE id_menu = 780;

SELECT setval(pg_get_serial_sequence('menu','id_menu'),
              GREATEST((SELECT MAX(id_menu) FROM menu), 1000));
