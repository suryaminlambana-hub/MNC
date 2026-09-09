-- BUG_AUSY_00120 — istilah "Group User" diganti "Role" supaya lebih jelas bagi pengguna.
-- Hanya teks yang dilihat user; nama tabel/kolom (group_user, id_group) sengaja dibiarkan
-- supaya tidak menyentuh data, query, dan format lain yang sudah jalan.
UPDATE menu SET nama_menu = 'Role' WHERE id_menu = 3;
