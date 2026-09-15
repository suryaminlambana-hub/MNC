-- BUG_AUSY_00134 — menu Approval Group dihapus.
--
-- Pasangan otorisasi sekarang tetap: Config Approver selalu berwenang atas sisi config, dan
-- Checker atas Maker lewat baris approval_group yang sudah ada. Tidak ada lagi yang perlu diatur
-- dari layar, jadi menunya ditiadakan beserta hak aksesnya.
DELETE FROM group_menu WHERE id_menu = 20;
UPDATE menu SET active = 0 WHERE id_menu = 20;
