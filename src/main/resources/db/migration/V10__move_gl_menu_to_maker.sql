-- ============================================================
-- V10 — BUG_00056: menu GL (parameter GL Code) dipindah dari role Admin (1) ke Maker (3).
-- Admin hanya mengurus role/user/akses; parameter GL jadi wewenang Maker (bisnis).
-- Menu 21 = Manage GL Code (/gl-code), 22 = List GL (/gl-code/list).
-- ============================================================

DELETE FROM group_menu WHERE id_group = 1 AND id_menu IN (21, 22);

INSERT INTO group_menu (id_group, id_menu)
SELECT 3, 21 WHERE NOT EXISTS (SELECT 1 FROM group_menu WHERE id_group = 3 AND id_menu = 21);
INSERT INTO group_menu (id_group, id_menu)
SELECT 3, 22 WHERE NOT EXISTS (SELECT 1 FROM group_menu WHERE id_group = 3 AND id_menu = 22);
