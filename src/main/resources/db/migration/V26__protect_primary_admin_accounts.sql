-- BUG_AUSY_00131 — akun admin & admin approver utama tidak boleh dihapus.
--
-- Keduanya adalah akun bawaan sistem: kalau terhapus, tidak ada lagi yang bisa mengonfigurasi
-- maupun mengotorisasi konfigurasi. Akun admin lain yang DIBUAT oleh admin utama tetap bisa
-- dihapus seperti biasa, jadi penandaannya per-akun, bukan per-role.
ALTER TABLE users ADD COLUMN IF NOT EXISTS protected_account SMALLINT DEFAULT 0;

UPDATE users SET protected_account = 1 WHERE user_id IN ('admin', 'adminapv');
