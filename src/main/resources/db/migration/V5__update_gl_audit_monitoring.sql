-- ============================================================
-- V5 — closes PDF spec gaps:
--   * 8.3 Update GL (Add/Edit/Delete) via dual-control + soft-delete + before/after
--   * Audit trail (tlog_*) now actively written (immutable, append-only)
--   * Monitoring & Rekapitulasi menus
--   * Reject path for otorisasi (handled in code; no schema needed)
-- ============================================================

-- Soft-delete flag on GL rows (8.3.e: Delete = mark inactive, never hard-delete)
ALTER TABLE gl_tabel ADD COLUMN active SMALLINT DEFAULT 1;

-- Dual-control change requests for individual GL rows (Workflow 8.3)
CREATE TABLE gl_change_request (
    id           BIGSERIAL PRIMARY KEY,
    op           VARCHAR(10) NOT NULL,            -- ADD / EDIT / DELETE
    gl_id        BIGINT,                          -- target gl_tabel row (NULL for ADD)
    no_paket     VARCHAR(20),
    maker        VARCHAR(50) NOT NULL,
    maker_group  INT,
    checker      VARCHAR(50),
    status       VARCHAR(20) DEFAULT 'PENDING',   -- PENDING / APPROVED / REJECTED
    before_json  TEXT,
    after_json   TEXT,
    note_maker   TEXT,
    note_checker TEXT,
    created_at   TIMESTAMP DEFAULT NOW(),
    decided_at   TIMESTAMP
);
CREATE INDEX idx_glcr_status ON gl_change_request(status);
CREATE INDEX idx_glcr_maker  ON gl_change_request(maker);

-- New menus: Kelola Data GL + Rekapitulasi (Data), Otorisasi Perubahan GL + Audit Trail (Verification)
INSERT INTO menu (id_menu,nama_menu,url,icon,parent,urutan) OVERRIDING SYSTEM VALUE VALUES
 (31,'Kelola Data GL','/gl-data','fa-solid fa-pen-to-square',300,3),
 (32,'Rekapitulasi','/rekap','fa-solid fa-chart-column',300,4),
 (33,'Otorisasi Perubahan GL','/gl-change/review','fa-solid fa-clipboard-check',400,2),
 (34,'Audit Trail','/audit','fa-solid fa-clock-rotate-left',400,3);
SELECT setval('menu_id_menu_seq', 500);

-- Grants: ADMIN(2) all; SUPERVISOR(3) checker+recap+audit; STAFF(4) maker+recap
INSERT INTO group_menu (id_group,id_menu) VALUES
 (2,31),(2,32),(2,33),(2,34),
 (3,32),(3,33),(3,34),
 (4,31),(4,32);
