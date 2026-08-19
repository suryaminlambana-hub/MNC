-- Export flow: maker Ajukan Export -> checker Otorisasi Export GL -> ACCTXT ke FTP.

ALTER TABLE gl_tabel ADD COLUMN IF NOT EXISTS export_req_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_gl_export_req ON gl_tabel(export_req_id);

CREATE TABLE IF NOT EXISTS gl_export_request (
    id              BIGSERIAL PRIMARY KEY,
    requested_by    VARCHAR(100) NOT NULL,
    requester_group INTEGER,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',   -- PENDING / APPROVED / REJECTED
    rows_count      INTEGER      DEFAULT 0,
    debit           NUMERIC(17,2) DEFAULT 0,
    credit          NUMERIC(17,2) DEFAULT 0,
    file_name       VARCHAR(255),
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    checker         VARCHAR(100),
    decided_at      TIMESTAMP,
    note_checker    VARCHAR(1000),
    transfer_id     BIGINT
);
CREATE INDEX IF NOT EXISTS idx_gl_export_status ON gl_export_request(status);

-- Menu "Otorisasi Export GL" untuk Checker (group 4), di bawah parent Verification (400).
INSERT INTO menu (id_menu, nama_menu, url, parent, urutan, icon, active)
VALUES (41, 'Otorisasi Export GL', '/otorisasi-export', 400, 20, 'fa-file-export', 1)
ON CONFLICT (id_menu) DO NOTHING;

INSERT INTO group_menu (id_group, id_menu)
SELECT 4, 41 WHERE NOT EXISTS (SELECT 1 FROM group_menu WHERE id_group = 4 AND id_menu = 41);
