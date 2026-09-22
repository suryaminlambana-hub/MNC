-- BUG_AUSY_00151 — halaman "Direktori Hasil Export" (/export-dir).
--
-- Baca saja: menunjukkan folder tujuan file ACCTXT di server ini beserta isinya, supaya
-- pertanyaan "hasil export-nya ke mana?" bisa dijawab dari dalam aplikasi. Tidak ada unduhan
-- file dan tidak ada kredensial FTP yang ditampilkan — setelan FTP tetap hanya dari konfigurasi
-- server (BUG_AUSY_00141).
--
-- Didaftarkan sebagai item ACTION yang menempel pada "Manajemen Otorisasi GL" (36): halamannya
-- dibuka dari pesan setelah checker approve, bukan dari sidebar, dan tetap terkunci hak akses
-- (bukan terbuka untuk semua yang login karena tidak dimiliki item mana pun).
INSERT INTO menu (id_menu, nama_menu, url, parent, urutan, active, kind, section, bundled_with)
VALUES (607, 'Direktori Hasil Export', '/export-dir', 600, 7, 1, 'ACTION', 'CHECKER', 36)
ON CONFLICT (id_menu) DO NOTHING;

-- Yang memegang halaman otorisasi GL (36) atau card antrian FTP di beranda Admin (704)
-- mendapatkannya: keduanya memang perlu memastikan file-nya terbit.
INSERT INTO group_menu (id_group, id_menu)
SELECT DISTINCT gm.id_group, 607 FROM group_menu gm
 WHERE gm.id_menu IN (36, 704)
   AND NOT EXISTS (SELECT 1 FROM group_menu x WHERE x.id_group = gm.id_group AND x.id_menu = 607);

INSERT INTO user_menu (user_id, id_menu)
SELECT DISTINCT um.user_id, 607 FROM user_menu um WHERE um.id_menu IN (36, 704)
ON CONFLICT (user_id, id_menu) DO NOTHING;

SELECT setval(pg_get_serial_sequence('menu','id_menu'),
              GREATEST((SELECT MAX(id_menu) FROM menu), 1000));
