-- ============================================================
-- V17 — BUG_AUSY_00087: menu "Manage GL Code" -> "Manajemen Kode GL"
-- (seluruh UI berbahasa Indonesia; URL /gl-code tidak berubah)
-- ============================================================

UPDATE menu SET nama_menu = 'Manajemen Kode GL' WHERE id_menu = 21;
