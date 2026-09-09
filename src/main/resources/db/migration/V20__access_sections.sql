-- (1) BUG_AUSY_00117 — card "Volume Transaksi (grafik)" dihapus, tidak dipakai.
DELETE FROM group_menu WHERE id_menu = 712;
DELETE FROM menu WHERE id_menu = 712;

-- (2) BUG_AUSY_00115 lanjutan — halaman Access Menu dikelompokkan per ROLE, bukan hanya per
-- parent menu, supaya jelas mana bagian Maker, Checker, Admin, dan Admin Approval.
-- Kolom ini murni penataan tampilan Access Menu; siapa boleh diberi apa tetap bebas.
ALTER TABLE menu ADD COLUMN IF NOT EXISTS section VARCHAR(20);

-- Maker: transaksi & data GL (Import, Data, GL) + aksi & card-nya
UPDATE menu SET section = 'MAKER'
 WHERE id_menu IN (10, 11, 9, 13, 31, 32, 21, 22, 604, 705, 706, 707, 708);

-- Checker: otorisasi operasional + aksi & card-nya
UPDATE menu SET section = 'CHECKER'
 WHERE id_menu IN (36, 601, 602, 603, 709, 710);

-- Admin: konfigurasi sistem (Master Data) + card-nya
UPDATE menu SET section = 'ADMIN'
 WHERE id_menu IN (2, 3, 4, 5, 20, 40, 701, 703, 704);

-- Admin Approval: otorisasi konfigurasi + card-nya
UPDATE menu SET section = 'ADMIN_APV'
 WHERE id_menu IN (35, 605, 702);

-- Dipakai lebih dari satu role
UPDATE menu SET section = 'UMUM' WHERE id_menu IN (34, 711);

-- Nama kelompok card cukup "Card Dashboard": role-nya sudah jelas dari section di atasnya.
UPDATE menu SET nama_menu = 'Card Dashboard' WHERE id_menu IN (700, 750, 780);
