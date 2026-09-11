-- ============================================================
-- V19 — buang menu sisa pengujian di lingkungan DEV.
-- Baris ini dibuat saat QA menguji Master Data (id 501, nama "menu", tanpa URL,
-- induk Import). Halaman Menu sekarang hanya menyediakan Ubah — tidak ada Hapus —
-- sehingga baris sampah seperti ini hanya bisa dibersihkan lewat migrasi.
-- Syaratnya diperketat (id + nama + url kosong) supaya tidak mengenai menu lain
-- bila id yang sama dipakai di lingkungan berbeda.
-- ============================================================

DELETE FROM group_menu
 WHERE id_menu IN (SELECT id_menu FROM menu
                    WHERE id_menu = 501 AND nama_menu = 'menu' AND (url IS NULL OR url = ''));

DELETE FROM menu
 WHERE id_menu = 501 AND nama_menu = 'menu' AND (url IS NULL OR url = '');
