-- ============================================================
-- V16 — dual control (maker–checker) untuk master Kode GL & hapus batch
--   * BUG_AUSY_00080: Add/Edit/Delete pada Manage GL Code wajib otorisasi Checker
--   * BUG_AUSY_00082: hapus batch wajib otorisasi Checker
--   * BUG_AUSY_00084: menu "Delete Paket" -> "Hapus Batch"
-- gl_change_request dipakai ulang untuk 3 target: GL_ROW / GL_CODE / PAKET,
-- jadi Checker tetap memutuskan semuanya di satu tab "Otorisasi Perubahan GL".
-- ============================================================

ALTER TABLE gl_change_request ADD COLUMN IF NOT EXISTS target  VARCHAR(20) DEFAULT 'GL_ROW';
ALTER TABLE gl_change_request ADD COLUMN IF NOT EXISTS ref_id  BIGINT;        -- gl_code.id (target GL_CODE)
ALTER TABLE gl_change_request ADD COLUMN IF NOT EXISTS inputer VARCHAR(50);   -- gl_tabel.gltrus (target PAKET)

UPDATE gl_change_request SET target = 'GL_ROW' WHERE target IS NULL;

CREATE INDEX IF NOT EXISTS idx_glcr_target ON gl_change_request (target);

-- BUG_AUSY_00084 — penamaan menu dalam Bahasa Indonesia yang benar
UPDATE menu SET nama_menu = 'Hapus Batch' WHERE id_menu = 13;
