-- Export -> FTP flow: admin-managed FTP config + transfer queue/log.

CREATE TABLE IF NOT EXISTS ftp_config (
    id          INTEGER PRIMARY KEY,
    host        VARCHAR(255) NOT NULL DEFAULT '',
    port        INTEGER      NOT NULL DEFAULT 21,
    username    VARCHAR(255) NOT NULL DEFAULT '',
    password    VARCHAR(255) NOT NULL DEFAULT '',
    remote_dir  VARCHAR(255) NOT NULL DEFAULT '',
    secure      BOOLEAN      NOT NULL DEFAULT FALSE,
    passive     BOOLEAN      NOT NULL DEFAULT TRUE,
    enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_by  VARCHAR(100),
    updated_at  TIMESTAMP,
    CONSTRAINT ftp_config_singleton CHECK (id = 1)
);
INSERT INTO ftp_config (id) VALUES (1) ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS export_transfer (
    id          BIGSERIAL PRIMARY KEY,
    file_name   VARCHAR(255) NOT NULL,
    paket       VARCHAR(100),
    inputer     VARCHAR(100),
    checker     VARCHAR(100),
    rows_count  INTEGER      DEFAULT 0,
    status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING',   -- SENT / PENDING / FAILED
    remote_path VARCHAR(500),
    error       VARCHAR(1000),
    attempts    INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    sent_at     TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_export_transfer_status ON export_transfer(status);

-- Menu "Konfigurasi FTP" untuk Admin (group 1), di bawah parent Master Data (200).
INSERT INTO menu (id_menu, nama_menu, url, parent, urutan, icon, active)
VALUES (40, 'Konfigurasi FTP', '/admin/ftp', 200, 90, 'fa-server', 1)
ON CONFLICT (id_menu) DO NOTHING;

INSERT INTO group_menu (id_group, id_menu)
SELECT 1, 40 WHERE NOT EXISTS (SELECT 1 FROM group_menu WHERE id_group = 1 AND id_menu = 40);
