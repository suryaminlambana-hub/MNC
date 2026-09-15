-- Satu menu = satu kemampuan utuh.
--
-- Hak "otorisasi" sebelumnya terpecah dua: menu halamannya + baris ACTION terpisah untuk endpoint
-- approve/reject-nya. Admin yang membuat role penyetuju baru wajar hanya mencentang menunya, lalu
-- role itu bisa membuka halaman tapi tidak melihat/menyetujui apa pun. Sekarang ACTION menempel
-- pada menunya (kolom bundled_with) dan ikut diberikan otomatis saat menunya dicentang.
ALTER TABLE menu ADD COLUMN IF NOT EXISTS bundled_with INT;

-- Otorisasi config: URL menu dinaikkan ke /config-change supaya sekaligus mencakup
-- /config-change/review, /approve, /reject. Aksi 605 jadi tidak perlu lagi.
UPDATE menu SET url = '/config-change' WHERE id_menu = 35;
UPDATE menu SET active = 0 WHERE id_menu = 605;

-- Otorisasi GL: endpointnya beda prefix, jadi tetap baris sendiri tapi menempel ke menu 36.
UPDATE menu SET bundled_with = 36 WHERE id_menu IN (601, 602, 603);

-- Susulkan hak yang menempel ke group yang sudah punya menunya, supaya tidak ada yang tertinggal.
INSERT INTO group_menu (id_group, id_menu)
SELECT gm.id_group, m.id_menu
FROM menu m JOIN group_menu gm ON gm.id_menu = m.bundled_with
WHERE m.bundled_with IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM group_menu x WHERE x.id_group = gm.id_group AND x.id_menu = m.id_menu);
