-- REQ_AUSY_00053: kolom Status di List Data GL (Menunggu Otorisasi / Dikembalikan / Rejected).
--
-- Status per batch upload (no_paket + maker), berikut catatan checker. Tidak ada baris =
-- "Menunggu Otorisasi".
--   DIKEMBALIKAN : batch di-Return Data checker dari penampungan, menunggu otorisasi lagi.
--   REJECTED     : batch ditolak checker. Baris gl_tabel-nya tidak lagi dihapus, melainkan
--                  status_dual = 'REJECTED' dan active = 0 (keluar dari antrian checker dan GL
--                  Aktif). Maker masih bisa membuka PDF, Bulk Edit (lalu diajukan ulang), atau Hapus.
-- Baris status dihapus saat batch di-approve, diajukan ulang, atau dihapus maker.
CREATE TABLE IF NOT EXISTS gl_batch_status (
    id          BIGSERIAL PRIMARY KEY,
    no_paket    VARCHAR(20)  NOT NULL,
    maker       VARCHAR(50)  NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    note        TEXT,
    decided_by  VARCHAR(50),
    decided_at  TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_gl_batch_status ON gl_batch_status (no_paket, maker);
