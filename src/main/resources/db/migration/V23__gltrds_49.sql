-- BUG_AUSY_00121 — pelonggaran kolom deskripsi.
-- Catatan (setelah source code asli MNC diterima): kolom deskripsi pada file ACCTXT tetap 40
-- karakter, jadi pelebaran ini bukan keharusan format. Dibiarkan 49 supaya tidak perlu migrasi
-- penyempitan yang berisiko; input tetap dibatasi 40 di form & upload agar tidak ada teks yang
-- diam-diam terpotong saat export.
ALTER TABLE gl_tabel ALTER COLUMN gltrds TYPE VARCHAR(49);
