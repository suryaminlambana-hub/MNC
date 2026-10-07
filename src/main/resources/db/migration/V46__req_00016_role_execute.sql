-- REQ_AUSY_00016 — role baru EXECUTE untuk posisi data center.
--
-- Penerbitan berkas ACCTXT dipindah dari admin ke role tersendiri: admin mengurus akun dan
-- konfigurasi, data center yang menekan tombol Generate File ACCTXT.
--
-- Role baru otomatis muncul di dropdown Role saat Tambah/Ubah User, karena daftarnya dibaca
-- dari tabel group_user.

-- (1) Role EXECUTE. id diambil dari sequence, bukan angka tetap: role buatan admin lewat layar
-- bisa saja sudah memakai id 5 di lingkungan lain. Sequence disamakan dulu dengan id terbesar
-- karena V1 mengisi id secara eksplisit.
SELECT setval(pg_get_serial_sequence('group_user', 'id_group'),
              GREATEST((SELECT MAX(id_group) FROM group_user), 1));

INSERT INTO group_user (nama_group, level, inisial)
SELECT 'EXECUTE', 'EXECUTE', 'EXE'
 WHERE NOT EXISTS (SELECT 1 FROM group_user WHERE upper(nama_group) = 'EXECUTE');

-- (2) Cetakan role EXECUTE: halaman Generate File ACCTXT (43) beserta dua aksinya,
-- Susun Ulang (608) dan Hari Ini (610). User EXECUTE baru mendapat hak awal dari cetakan ini.
INSERT INTO group_menu (id_group, id_menu)
SELECT g.id_group, m.id_menu
  FROM group_user g
  JOIN menu m ON m.id_menu IN (43, 608, 610)
 WHERE upper(g.nama_group) = 'EXECUTE'
   AND NOT EXISTS (SELECT 1 FROM group_menu x WHERE x.id_group = g.id_group AND x.id_menu = m.id_menu);

-- (3) Menu Generate File ACCTXT dicabut dari admin (role CONFIG / SETTING, id 1): dari
-- cetakan role-nya dan dari hak per user, karena sidebar dan izin URL dibaca dari user_menu.
DELETE FROM group_menu WHERE id_group = 1 AND id_menu IN (43, 608, 610);
DELETE FROM user_menu
 WHERE id_menu IN (43, 608, 610)
   AND user_id IN (SELECT user_id FROM users WHERE id_group = 1);
