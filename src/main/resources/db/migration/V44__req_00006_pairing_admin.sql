-- REQ_AUSY_00006 — menu pasangan Admin dengan Admin Approval.
--
-- Sisi bisnis sudah punya pasangannya (checker_maker, V30), tapi sisi administratif belum:
-- tidak ada satu layar pun untuk memasangkan admin dengan admin approval. Tabelnya dipakai
-- bersama karena maknanya sama persis — "X berwenang atas Y" — hanya sisinya yang berbeda.
--
-- Yang ditentukan pasangan ini BUKAN wewenang otorisasi (itu tetap matriks approval_group),
-- melainkan siapa melihat jejak siapa di Audit Trail Admin (REQ_AUSY_00007 & 00008).

-- Menu pengaturannya, tepat di bawah Pasangan Checker - Maker (200/6) supaya keduanya
-- berdampingan. Milik Admin, dan seperti perubahan config lain tetap lewat dual control.
--
-- id_menu sengaja TIDAK dipatok tangan, melainkan diserahkan ke sequence, dan baris-baris hak
-- akses di bawah mengacu ke menu ini lewat URL-nya. Memilih nomor sendiri menuntut seseorang
-- menebak nomor mana yang masih kosong, dan tebakan yang meleset tidak gagal dengan berisik:
-- ON CONFLICT DO NOTHING membuang menunya diam-diam sementara baris hak aksesnya tetap jalan,
-- ke menu milik orang lain.
INSERT INTO menu (nama_menu, url, parent, urutan, icon, active, kind, section)
SELECT 'Pasangan Admin - Admin Approval', '/pairing-admin', 200, 7,
       'fa-solid fa-user-shield', 1, 'MENU', 'ADMIN'
 WHERE NOT EXISTS (SELECT 1 FROM menu WHERE trim(url) = '/pairing-admin');

INSERT INTO group_menu (id_group, id_menu)
SELECT 1, m.id_menu FROM menu m
 WHERE trim(m.url) = '/pairing-admin'
   AND NOT EXISTS (SELECT 1 FROM group_menu g WHERE g.id_group = 1 AND g.id_menu = m.id_menu);

-- Sidebar dibaca dari user_menu (BUG_AUSY_00143), jadi hak per user ikut diberikan.
INSERT INTO user_menu (user_id, id_menu)
SELECT u.user_id, m.id_menu
  FROM users u CROSS JOIN menu m
 WHERE u.id_group = 1 AND trim(m.url) = '/pairing-admin'
ON CONFLICT (user_id, id_menu) DO NOTHING;

-- Pasangan awal: setiap Admin Approval yang SUDAH ADA dipasangkan ke setiap Admin yang sudah
-- ada. Tanpa ini, begitu versi ini naik, Audit Trail Admin milik semua orang langsung menyempit
-- jadi "hanya jejak sendiri" — termasuk pasangan bawaan mncsuperadmin dan mncadmin, yang memang
-- seharusnya saling terlihat. Pasangan selanjutnya diatur lewat layarnya.
--
-- Sisinya ditentukan dari hak akses yang dipegang role, bukan id role yang ditulis di sini:
-- pemegang menu Otorisasi Config (/config-change) adalah Admin Approval, sisanya di sisi
-- administratif adalah Admin.
INSERT INTO checker_maker (checker, maker)
SELECT apv.user_id, adm.user_id
  FROM users apv
  JOIN users adm ON adm.user_id <> apv.user_id AND adm.status_account = 1
 WHERE apv.status_account = 1
   AND EXISTS (SELECT 1 FROM group_menu gm JOIN menu m ON m.id_menu = gm.id_menu
                WHERE gm.id_group = apv.id_group AND m.active = 1 AND trim(m.url) = '/config-change')
   AND NOT EXISTS (SELECT 1 FROM group_menu gm JOIN menu m ON m.id_menu = gm.id_menu
                    WHERE gm.id_group = adm.id_group AND m.active = 1 AND trim(m.url) = '/config-change')
   AND EXISTS (SELECT 1 FROM group_menu gm JOIN menu m ON m.id_menu = gm.id_menu
                WHERE gm.id_group = adm.id_group AND m.active = 1 AND trim(m.url) IN ('/users', '/groups', '/menus'))
ON CONFLICT (checker, maker) DO NOTHING;

SELECT setval(pg_get_serial_sequence('menu','id_menu'),
              GREATEST((SELECT MAX(id_menu) FROM menu), 1000));
