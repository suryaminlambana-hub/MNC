-- ============================================================
-- V9 — Gabungkan "Export to Text" (17) + "Otorisasi Perubahan GL" (33)
--      menjadi satu menu "Manajemen Otorisasi GL" (/otorisasi-gl) untuk Checker (4).
--      (BUG_AUSY_00042)
-- ============================================================

INSERT INTO menu (id_menu, nama_menu, url, icon, urutan, parent, active)
VALUES (36, 'Manajemen Otorisasi GL', '/otorisasi-gl', 'fa-solid fa-clipboard-check', 1, 400, 1)
ON CONFLICT (id_menu) DO NOTHING;

-- sidebar Checker: buang menu lama, pakai menu gabungan
DELETE FROM group_menu WHERE id_group = 4 AND id_menu IN (17, 33);
INSERT INTO group_menu (id_group, id_menu)
SELECT 4, 36 WHERE NOT EXISTS (SELECT 1 FROM group_menu WHERE id_group = 4 AND id_menu = 36);
