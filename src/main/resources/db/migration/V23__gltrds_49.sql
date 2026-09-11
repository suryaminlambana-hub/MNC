-- BUG_AUSY_00121 — kolom deskripsi di file ACCTXT acuan lebarnya 49 karakter, sedangkan
-- gl_tabel.gltrds masih VARCHAR(40) sehingga deskripsi asli terpotong sebelum sempat ditulis.
ALTER TABLE gl_tabel ALTER COLUMN gltrds TYPE VARCHAR(49);
