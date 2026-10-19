-- REQ_AUSY_00065 — GL Name mengacu pada Manajemen Kode GL, bukan isian kolom F Excel.
--
-- 1. Kolom dilebarkan mengikuti gl_code.description (255): nama master kini disalin ke sini,
--    dan nama yang lebih dari 100 karakter tidak boleh menggagalkan upload.
-- 2. Baris yang sudah ada diselaraskan dengan master. Hanya baris yang kodenya terdaftar;
--    yang tidak terdaftar dibiarkan apa adanya karena tidak ada sumber nama lain.
--    GL Name tidak masuk berkas ACCTXT, jadi baris yang sudah terkirim aman ikut diperbarui.
ALTER TABLE gl_tabel ALTER COLUMN gl_name TYPE VARCHAR(255);

UPDATE gl_tabel g
   SET gl_name = coalesce(c.description, '')
  FROM gl_code c
 WHERE c.code = g.gltrac
   AND coalesce(g.gl_name, '') <> coalesce(c.description, '');
