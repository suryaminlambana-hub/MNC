-- BUG_00067: menu "Legacy - List GL" -> "List GL Aktif" (nama terlalu mirip dgn "List GL")
UPDATE menu SET nama_menu = 'List GL Aktif' WHERE id_menu = 9;

-- BUG_00068: menu "List GL" -> "List Kode GL" (nama terlalu mirip dgn "Legacy - List GL")
UPDATE menu SET nama_menu = 'List Kode GL' WHERE id_menu = 22;
