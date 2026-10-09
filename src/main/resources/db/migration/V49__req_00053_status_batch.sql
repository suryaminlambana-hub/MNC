-- REQ_AUSY_00053: kolom Status di List Data GL (Menunggu Otorisasi / Dikembalikan / Rejected).
--
-- Status per batch upload (no_paket + maker). Tidak ada baris = "Menunggu Otorisasi".
--   DIKEMBALIKAN : batch di-Return Data checker dari penampungan, menunggu otorisasi lagi.
--   REJECTED     : batch ditolak checker. Baris gl_tabel batch itu tetap dihapus seperti dulu,
--                  jadi ringkasannya (jumlah, debit, credit, waktu upload) disimpan di sini
--                  supaya maker masih bisa melihatnya sampai ia menghapusnya dari daftar.
-- Baris status dihapus saat batch di-approve atau dihapus maker.
CREATE TABLE IF NOT EXISTS gl_batch_status (
    id          BIGSERIAL PRIMARY KEY,
    no_paket    VARCHAR(20)  NOT NULL,
    maker       VARCHAR(50)  NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    note        TEXT,
    decided_by  VARCHAR(50),
    decided_at  TIMESTAMP,
    jml         INTEGER,
    debit       NUMERIC(20,2),
    credit      NUMERIC(20,2),
    uploaded_at TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_gl_batch_status ON gl_batch_status (no_paket, maker);
