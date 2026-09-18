-- BUG_AUSY_00139 — checker punya maker masing-masing.
--
-- Otorisasi selama ini dipasangkan per ROLE (approval_group): semua checker melihat pengajuan
-- semua maker. Yang diminta MNC: checker A ditetapkan atas maker A & B, sehingga hanya pengajuan
-- dan data GL milik kedua maker itu yang dilihat dan diotorisasinya.
CREATE TABLE IF NOT EXISTS checker_maker (
    id      BIGSERIAL   PRIMARY KEY,
    checker VARCHAR(50) NOT NULL,
    maker   VARCHAR(50) NOT NULL,
    CONSTRAINT checker_maker_uq UNIQUE (checker, maker)
);
CREATE INDEX IF NOT EXISTS idx_checker_maker_checker ON checker_maker(checker);
CREATE INDEX IF NOT EXISTS idx_checker_maker_maker   ON checker_maker(maker);

-- Nilai awal = perilaku hari ini: setiap checker dipasangkan ke seluruh maker yang memang sudah
-- boleh diotorisasinya menurut matriks approval_group. Tanpa ini semua antrian otorisasi yang
-- sedang berjalan akan langsung kosong begitu versi ini naik.
--
-- Hanya pasangan sisi BISNIS yang diseed: target role-nya harus benar-benar memegang halaman
-- pengajuan data GL. Pasangan sisi config (Admin Approval -> Admin) tidak ikut, karena otorisasi
-- config tidak pernah dibatasi per maker.
INSERT INTO checker_maker (checker, maker)
SELECT chk.user_id, mkr.user_id
  FROM approval_group ag
  JOIN users chk ON chk.id_group = ag.approver_group AND chk.status_account = 1
  JOIN users mkr ON mkr.id_group = ag.target_group   AND mkr.status_account = 1
 WHERE EXISTS (
       SELECT 1 FROM group_menu gm JOIN menu m ON m.id_menu = gm.id_menu
        WHERE gm.id_group = ag.target_group AND m.active = 1
          AND trim(m.url) IN ('/upload', '/nala', '/gl-data', '/gl-delete', '/gl-legacy/export-request'))
ON CONFLICT (checker, maker) DO NOTHING;

-- Menu pengaturannya, di bawah Master Data (200) — milik Admin, tetap lewat dual control.
INSERT INTO menu (id_menu, nama_menu, url, parent, urutan, icon, active, kind, section)
VALUES (42, 'Pasangan Checker - Maker', '/pairing', 200, 6, 'fa-solid fa-user-group', 1, 'MENU', 'ADMIN')
ON CONFLICT (id_menu) DO NOTHING;

INSERT INTO group_menu (id_group, id_menu)
SELECT 1, 42 WHERE NOT EXISTS (SELECT 1 FROM group_menu WHERE id_group = 1 AND id_menu = 42);

INSERT INTO user_menu (user_id, id_menu)
SELECT u.user_id, 42 FROM users u WHERE u.id_group = 1
ON CONFLICT (user_id, id_menu) DO NOTHING;

SELECT setval(pg_get_serial_sequence('menu','id_menu'),
              GREATEST((SELECT MAX(id_menu) FROM menu), 1000));
