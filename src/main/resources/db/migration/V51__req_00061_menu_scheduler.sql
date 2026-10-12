-- REQ_AUSY_00061 — aksi menyalakan/mematikan penjadwal.
--
-- Perlu baris ACTION sendiri karena hak akses dicocokkan PER RUAS URL:
-- /admin/acctxt/scheduler bukan bagian dari /admin/acctxt/generate, jadi ACTION 608 tidak
-- mencakupnya. Diberikan ke role yang memegang halaman Generate File ACCTXT, yaitu EXECUTE.
INSERT INTO menu (id_menu, nama_menu, url, icon, urutan, parent, active, kind, section) VALUES
 (611, 'Penjadwal File ACCTXT', '/admin/acctxt/scheduler', NULL, 11, 600, 1, 'ACTION', 'EXECUTE')
ON CONFLICT (id_menu) DO NOTHING;

INSERT INTO group_menu (id_group, id_menu)
SELECT g.id_group, 611 FROM group_user g
 WHERE upper(g.nama_group) = 'EXECUTE'
   AND NOT EXISTS (SELECT 1 FROM group_menu x WHERE x.id_group = g.id_group AND x.id_menu = 611);

INSERT INTO user_menu (user_id, id_menu)
SELECT u.user_id, 611 FROM users u JOIN group_user g ON g.id_group = u.id_group
 WHERE upper(g.nama_group) = 'EXECUTE'
   AND NOT EXISTS (SELECT 1 FROM user_menu um WHERE um.user_id = u.user_id AND um.id_menu = 611);
