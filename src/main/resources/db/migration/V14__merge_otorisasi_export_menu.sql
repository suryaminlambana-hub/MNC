-- Otorisasi Export GL digabung jadi tab ke-3 di halaman Manajemen Otorisasi GL (/otorisasi-gl).
-- Nonaktifkan menu terpisahnya (id_menu 41) supaya tidak dobel akses di sidebar; group_menu dibiarkan
-- (tidak dipakai lagi karena query sidebar hanya mengambil menu dengan active = 1).
UPDATE menu SET active = 0 WHERE id_menu = 41;