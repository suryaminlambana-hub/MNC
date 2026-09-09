-- Access jadi data, bukan grup hardcode (BUG_AUSY_00112/00113/00114).
--
-- menu.kind memisahkan tiga jenis hak akses yang semuanya diatur di halaman Access Menu:
--   MENU   : link di sidebar + izin URL-nya
--   ACTION : izin URL saja (tidak muncul di sidebar) — endpoint yang tidak boleh ikut terbuka
--            hanya karena group punya halaman induknya, mis. "Ajukan Export" di List GL Aktif
--   CARD   : card/section dashboard (url diisi 'card:<key>', tidak pernah dipakai untuk URL)
ALTER TABLE menu ADD COLUMN IF NOT EXISTS kind VARCHAR(10) NOT NULL DEFAULT 'MENU';

-- Kelompok (parent) untuk halaman Access Menu. Dipisah operasional vs administratif
-- sesuai pemisahan role Maker/Checker dan Admin/Admin Approval (BUG_AUSY_00115).
INSERT INTO menu (id_menu, nama_menu, url, parent, urutan, active, kind) VALUES
  (600, 'Aksi Tambahan',                              NULL, 0, 60, 1, 'ACTION'),
  (700, 'Card Dashboard — Operasional (Maker & Checker)', NULL, 0, 70, 1, 'CARD'),
  (750, 'Card Dashboard — Administratif (Admin & Admin Approval)', NULL, 0, 75, 1, 'CARD'),
  (780, 'Card Dashboard — Umum',                      NULL, 0, 78, 1, 'CARD')
ON CONFLICT (id_menu) DO NOTHING;

-- ---------- ACTION: endpoint yang tidak tercakup URL menu mana pun ----------
INSERT INTO menu (id_menu, nama_menu, url, parent, urutan, active, kind) VALUES
  (601, 'Otorisasi Perubahan GL (approve/reject)', '/gl-change',                600, 1, 1, 'ACTION'),
  (602, 'Otorisasi Export GL (approve/reject)',    '/gl-export',                600, 2, 1, 'ACTION'),
  (603, 'Proses Export ke Teks',                   '/otorisasi',                600, 3, 1, 'ACTION'),
  (604, 'Ajukan Export GL (maker)',                '/gl-legacy/export-request', 600, 4, 1, 'ACTION'),
  (605, 'Otorisasi Perubahan Config',              '/config-change',            600, 5, 1, 'ACTION')
ON CONFLICT (id_menu) DO NOTHING;

-- Menu 17 (/otorisasi) & 33 (/gl-change/review) sudah dilebur ke "Manajemen Otorisasi GL" (V9/V14)
-- dan tidak dipegang group mana pun. Dinonaktifkan supaya tidak jadi pemilik URL ganda dengan
-- ACTION 603/601 di atas — kalau dibiarkan aktif, endpoint approve/reject bisa ikut terkunci.
UPDATE menu SET active = 0 WHERE id_menu IN (17, 33);

-- ---------- CARD: card & section dashboard ----------
INSERT INTO menu (id_menu, nama_menu, url, parent, urutan, active, kind) VALUES
  (701, 'Ringkasan User & Group',              'card:cfg-stats',         750, 1, 1, 'CARD'),
  (702, 'Perubahan Config Menunggu Otorisasi', 'card:cfg-pending',       750, 2, 1, 'CARD'),
  (703, 'Riwayat Perubahan Config',            'card:cfg-history',       750, 3, 1, 'CARD'),
  (704, 'Transfer FTP Perlu Kirim Ulang',      'card:ftp-resend',        750, 4, 1, 'CARD'),
  (705, 'GL Aktif (ringkasan & daftar)',       'card:gl-active',         700, 1, 1, 'CARD'),
  (706, 'Perubahan GL Pending',                'card:gl-pending',        700, 2, 1, 'CARD'),
  (707, 'Riwayat GL',                          'card:gl-history',        700, 3, 1, 'CARD'),
  (708, 'Export Gagal Terkirim FTP',           'card:ftp-failed',        700, 4, 1, 'CARD'),
  (709, 'Menunggu Otorisasi (upload/export)',  'card:otorisasi-pending', 700, 5, 1, 'CARD'),
  (710, 'Perubahan GL Menunggu Otorisasi',     'card:gl-change-pending', 700, 6, 1, 'CARD'),
  (711, 'Aktivitas Terbaru (audit trail)',     'card:audit-recent',      780, 1, 1, 'CARD'),
  (712, 'Volume Transaksi (grafik)',           'card:volume-tx',         780, 2, 1, 'CARD')
ON CONFLICT (id_menu) DO NOTHING;

-- Hak default = persis perilaku hardcode sebelumnya (aksi) dan card yang selama ini
-- tampil di tiap dashboard bawaan, supaya 4 grup bawaan tidak berubah sama sekali.
INSERT INTO group_menu (id_group, id_menu)
SELECT v.g, v.m FROM (VALUES
  (4,601),(4,602),(4,603),        -- Business Approver: otorisasi GL, export, proses teks
  (3,604),                        -- Business Maker: ajukan export
  (2,605),                        -- Config Approver: otorisasi perubahan config
  (1,701),(1,702),(1,703),(1,704),
  (2,702),(2,711),
  (3,705),(3,706),(3,707),(3,708),
  (4,709),(4,710),(4,711)
) AS v(g,m)
WHERE NOT EXISTS (SELECT 1 FROM group_menu gm WHERE gm.id_group = v.g AND gm.id_menu = v.m);

-- Grup baru buatan admin sengaja tidak dapat apa pun: dashboardnya kosong dan menunya
-- tertutup sampai admin mencentangnya di Access Menu (BUG_AUSY_00112).

-- id 601+/701+ diisi manual, jadi majukan sequence supaya menu baru dari UI tidak bentrok PK.
SELECT setval(pg_get_serial_sequence('menu','id_menu'),
              GREATEST((SELECT MAX(id_menu) FROM menu), 1000));
