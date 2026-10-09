-- REQ_AUSY_00034 .. 00046 — alur baru: approve checker langsung masuk penampungan EXECUTE.
--
-- Alur lama : maker upload → checker approve → maker ajukan export → checker approve export →
--             Execute generate ACCTXT.
-- Alur baru : maker upload → checker approve → masuk penampungan → Execute generate ACCTXT.
--
-- "Penampungan" = gl_export_request berstatus APPROVED yang belum punya file — persis yang
-- dibaca halaman Generate File ACCTXT sebagai "Menunggu digenerate" (lihat StagingService).

-- (1) Nama menu.
UPDATE menu SET nama_menu = 'List Data GL' WHERE id_menu = 9;      -- REQ_AUSY_00035
UPDATE menu SET nama_menu = 'Return Data'  WHERE id_menu = 38;     -- REQ_AUSY_00041

-- (2) Menu & aksi yang tidak dipakai lagi: Hapus Batch (13, REQ_AUSY_00040) dan Ajukan Export
-- maker (604, REQ_AUSY_00034). Endpoint-nya ikut dicabut di kode, jadi menonaktifkan menunya
-- tidak membuka alamat itu untuk siapa pun.
UPDATE menu SET active = 0 WHERE id_menu IN (13, 604);
DELETE FROM group_menu WHERE id_menu IN (13, 604);
DELETE FROM user_menu  WHERE id_menu IN (13, 604);

-- (3) Nomor batch di catatan upload, supaya List Data GL bisa menampilkan nama file per batch
-- (REQ_AUSY_00036). Upload lama tidak punya tautan ini dan tampil "-".
ALTER TABLE t_upload ADD COLUMN IF NOT EXISTS no_paket VARCHAR(20);
CREATE INDEX IF NOT EXISTS idx_t_upload_no_paket ON t_upload(no_paket);

-- (4) Data lama masuk penampungan (keputusan pengguna: "Masukkan ke penampungan").
--
-- (4a) Pengajuan export yang masih PENDING: barisnya sudah tertaut ke pengajuan itu. Langkah
-- otorisasi export tidak ada lagi, jadi pengajuan ini disetujui sistem.
UPDATE gl_export_request
   SET status = 'APPROVED', checker = 'system', decided_at = now(),
       note_checker = 'Otomatis (V48): alur baru — otorisasi export tidak dipakai lagi; data yang '
                   || 'upload-nya sudah diotorisasi langsung masuk penampungan.'
 WHERE status = 'PENDING';

-- (4b) GL yang sudah diotorisasi tapi belum pernah diajukan export: satu catatan penampungan per
-- batch upload per maker, dan satu per maker untuk data tambahan manual.
INSERT INTO gl_export_request
    (requested_by, requester_group, status, rows_count, debit, credit, created_at,
     note_maker, checker, decided_at, note_checker)
SELECT x.gltrus,
       (SELECT u.id_group FROM users u WHERE u.user_id = x.gltrus),
       'APPROVED',
       count(*),
       coalesce(sum(CASE WHEN upper(x.gltrcd) = 'C' THEN 0 ELSE x.gltram END), 0),
       coalesce(sum(CASE WHEN upper(x.gltrcd) = 'C' THEN x.gltram ELSE 0 END), 0),
       now(),
       'V48|' || x.kunci || '|' || x.gltrus,
       'system', now(),
       'Otomatis (V48): GL yang sudah diotorisasi langsung masuk penampungan Generate File ACCTXT.'
  FROM (SELECT g.*,
               CASE WHEN g.glsrc = 'm' OR coalesce(g.no_paket, '') = '' THEN '(manual)' ELSE g.no_paket END AS kunci
          FROM gl_tabel g
         WHERE g.active = 1
           AND (g.status_dual IS NULL OR g.status_dual = 'APPROVED')
           AND g.status_print = 'BELUM UPLOAD'
           AND g.export_req_id IS NULL
           AND g.gltrus IS NOT NULL) x
 GROUP BY x.kunci, x.gltrus;

UPDATE gl_tabel g
   SET export_req_id = r.id, status_print = 'PROSES EXPORT'
  FROM gl_export_request r
 WHERE r.note_maker = 'V48|' || (CASE WHEN g.glsrc = 'm' OR coalesce(g.no_paket, '') = '' THEN '(manual)'
                                       ELSE g.no_paket END) || '|' || g.gltrus
   AND g.active = 1
   AND (g.status_dual IS NULL OR g.status_dual = 'APPROVED')
   AND g.status_print = 'BELUM UPLOAD'
   AND g.export_req_id IS NULL;

UPDATE gl_export_request
   SET note_maker = 'Otomatis masuk penampungan (V48): '
                    || CASE WHEN split_part(note_maker, '|', 2) = '(manual)' THEN 'data manual'
                            ELSE 'Batch ' || split_part(note_maker, '|', 2) END
 WHERE note_maker LIKE 'V48|%';
