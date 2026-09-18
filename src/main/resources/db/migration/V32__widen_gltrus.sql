-- Prasyarat BUG_AUSY_00138: kolom pemilik baris GL harus memuat username utuh.
--
-- gl_tabel.gltrus dibuat VARCHAR(10) mengikuti lebar field operator di format GLTRRF, sedangkan
-- users.user_id VARCHAR(50). Username yang lebih panjang dari 10 karakter tersimpan terpotong,
-- sehingga pembandingan "baris ini milik siapa" tidak pernah cocok — pembatasan data per maker
-- akan bocor diam-diam untuk user seperti itu.
--
-- Lebar field di file ACCTXT tidak berubah: GltrrfFormatter.gltrus() tetap memotong & memadkan
-- ke 10 karakter saat menulis file, jadi hasil export tetap byte-exact.
ALTER TABLE gl_tabel ALTER COLUMN gltrus TYPE VARCHAR(50);

-- Pulihkan nilai yang sudah terpanjang-potong, HANYA bila tidak ambigu: tepat satu user aktif
-- yang user_id-nya diawali penggalan 10 karakter itu. Kalau ada dua kandidat, baris dibiarkan
-- apa adanya supaya tidak salah menetapkan pemilik data.
UPDATE gl_tabel g
   SET gltrus = (SELECT u.user_id FROM users u WHERE u.user_id LIKE g.gltrus || '%')
 WHERE length(g.gltrus) = 10
   AND (SELECT count(*) FROM users u WHERE u.user_id LIKE g.gltrus || '%') = 1
   AND NOT EXISTS (SELECT 1 FROM users u WHERE u.user_id = g.gltrus);
