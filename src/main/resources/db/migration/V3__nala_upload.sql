-- V3 — NALA Seamless Bulk Upload (resumable chunked upload + async processing)
CREATE TABLE nala_uploads (
    id              BIGSERIAL PRIMARY KEY,
    upload_id       VARCHAR(64) NOT NULL UNIQUE,
    user_id         VARCHAR(50) NOT NULL,
    original_name   VARCHAR(255) NOT NULL,
    file_size       BIGINT NOT NULL,
    total_chunks    INT NOT NULL,
    chunks_received INT DEFAULT 0,
    checksum        VARCHAR(64),
    status          VARCHAR(20) DEFAULT 'uploading',  -- uploading|complete|processing|done|failed
    temp_path       VARCHAR(255),
    final_path      VARCHAR(255),
    result_msg      TEXT,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW(),
    completed_at    TIMESTAMP
);
CREATE INDEX idx_nala_user ON nala_uploads(user_id);

-- NALA Seamless Upload menu (under Import)
INSERT INTO menu (id_menu,nama_menu,url,parent,urutan) OVERRIDING SYSTEM VALUE VALUES (11,'NALA Seamless Upload','/nala',100,3);
INSERT INTO group_menu (id_group,id_menu) VALUES (2,11),(4,11);
