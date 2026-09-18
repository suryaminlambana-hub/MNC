-- BUG_AUSY_00140 — menu "Manajemen Kode GL" (21) dihapus.
-- Maker tidak berwenang meng-CRUD parameter Kode GL; yang tersisa hanya "List Kode GL" (22)
-- yang sifatnya baca saja. Halaman /gl-code beserta endpoint add/edit/delete/import ikut
-- dihapus dari aplikasi, jadi baris menunya tidak boleh lagi menjadi pemilik URL.
DELETE FROM group_menu WHERE id_menu = 21;
DELETE FROM user_menu  WHERE id_menu = 21;
UPDATE menu SET active = 0 WHERE id_menu = 21;

-- BUG_AUSY_00141 — menu "Konfigurasi FTP" (40) dihapus.
-- Setelan FTP dibaca dari database/properti deploy, bukan diubah lewat website, sehingga tidak
-- ada satu pun user yang bisa melihat atau mengganti host/kredensialnya dari antarmuka.
DELETE FROM group_menu WHERE id_menu = 40;
DELETE FROM user_menu  WHERE id_menu = 40;
UPDATE menu SET active = 0 WHERE id_menu = 40;

-- Kirim ulang transfer FTP yang gagal BUKAN konfigurasi — kemampuannya tetap ada, pindah ke
-- card "Export FTP Gagal / Tertunda" di beranda Admin. Supaya endpoint-nya tetap terkunci hak
-- akses (bukan terbuka untuk semua yang login karena tidak dimiliki menu mana pun), aksinya
-- didaftarkan sendiri dan menempel pada card tersebut.
INSERT INTO menu (id_menu, nama_menu, url, parent, urutan, active, kind, section, bundled_with)
VALUES (606, 'Kirim Ulang Transfer FTP', '/admin/ftp-resend', 600, 6, 1, 'ACTION', 'ADMIN', 704)
ON CONFLICT (id_menu) DO NOTHING;

INSERT INTO group_menu (id_group, id_menu)
SELECT gm.id_group, 606 FROM group_menu gm
 WHERE gm.id_menu = 704
   AND NOT EXISTS (SELECT 1 FROM group_menu x WHERE x.id_group = gm.id_group AND x.id_menu = 606);

INSERT INTO user_menu (user_id, id_menu)
SELECT um.user_id, 606 FROM user_menu um WHERE um.id_menu = 704
ON CONFLICT (user_id, id_menu) DO NOTHING;

SELECT setval(pg_get_serial_sequence('menu','id_menu'),
              GREATEST((SELECT MAX(id_menu) FROM menu), 1000));
