-- REQ_AUSY_00061 — penjadwal pembuatan berkas ACCTXT otomatis.
--
-- Tombol manual TIDAK diganti: penjadwal ini tambahan. Dua mekanisme berjalan berdampingan —
-- manual kapan saja oleh EXECUTE, dan otomatis setiap jam 23:00.

-- (1) Satu baris setelan. Dikunci id=1 supaya tidak mungkin ada dua baris yang saling
-- bertentangan soal penjadwal mana yang berlaku.
CREATE TABLE IF NOT EXISTS acctxt_scheduler (
    id           smallint     PRIMARY KEY,
    aktif        boolean      NOT NULL DEFAULT true,
    diubah_oleh  varchar(50),
    diubah_pada  timestamp,
    CONSTRAINT acctxt_scheduler_satu_baris CHECK (id = 1)
);

-- Menyala sejak awal: yang diminta memang berjalan tanpa trigger manual. Bisa dimatikan
-- dari halaman Generate File ACCTXT kapan saja.
INSERT INTO acctxt_scheduler (id, aktif, diubah_oleh, diubah_pada)
VALUES (1, true, 'system', now())
ON CONFLICT (id) DO NOTHING;

-- (2) Riwayat tiap kali penjadwal berjalan — berhasil maupun gagal.
-- Tanpa ini "kenapa berkas hari itu tidak terbit" hanya bisa ditebak.
CREATE TABLE IF NOT EXISTS acctxt_scheduler_log (
    id         bigserial    PRIMARY KEY,
    mulai      timestamp    NOT NULL,
    berhasil   boolean      NOT NULL,
    pesan      text,
    file_name  varchar(100),
    rows_count integer      NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS acctxt_scheduler_log_mulai_idx ON acctxt_scheduler_log (mulai DESC);
