-- ============================================================
-- V2 — transaction tables: GL postings, upload staging, sequence, logs.
-- ============================================================

CREATE TABLE web_sequence (
    id          BIGSERIAL PRIMARY KEY,
    next_number BIGINT DEFAULT 1
);

CREATE TABLE web_xl_upload (
    num                     BIGSERIAL PRIMARY KEY,
    posting_date            VARCHAR(20),
    dc                      CHAR(1),
    product_code            VARCHAR(20),
    product_name            VARCHAR(100),
    transaction_account     VARCHAR(50),
    gl_name                 VARCHAR(100),
    transaction_description VARCHAR(255),
    branch                  VARCHAR(20),
    branch_name             VARCHAR(100),
    cost_center             VARCHAR(20),
    cost_center_name        VARCHAR(100),
    currency                VARCHAR(10),
    foreign_curr            VARCHAR(10),
    transaction_amount      NUMERIC(17,2),
    flag                    VARCHAR(5),
    responding_branch       VARCHAR(20),
    responding_branch_name  VARCHAR(100),
    inserted_by             VARCHAR(50),
    sess_id                 VARCHAR(50),
    adddt                   TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_wxl_sess ON web_xl_upload(sess_id, inserted_by);

CREATE TABLE gl_tabel (
    id              BIGSERIAL PRIMARY KEY,
    gltrfy          VARCHAR(5),
    gltrcd          CHAR(1),
    glprdc          VARCHAR(20),
    gltrac          VARCHAR(50),
    gltrpd          VARCHAR(10),
    gl_name         VARCHAR(100),
    gltrds          VARCHAR(40),
    gltrbr          VARCHAR(10),
    br_name         VARCHAR(100),
    gltrcc          VARCHAR(10),
    gltrcy          VARCHAR(5),
    gltram          NUMERIC(17,2),
    glflag          VARCHAR(5),
    glresp          VARCHAR(10),
    cs_name         VARCHAR(100),
    no_paket        VARCHAR(20),
    gleqam          NUMERIC(17,2),
    glotam          NUMERIC(17,2),
    gltrus          VARCHAR(10),
    gltrpd1         VARCHAR(10),
    status_print    VARCHAR(30) DEFAULT 'BELUM UPLOAD',
    glsrc           VARCHAR(5)  DEFAULT 'p',
    glotcc          VARCHAR(10),
    gltrrf          VARCHAR(20),
    adddt           TIMESTAMP DEFAULT NOW(),
    status_dual     VARCHAR(20) DEFAULT 'PENDING'
);
CREATE INDEX idx_gl_no_paket ON gl_tabel(no_paket);
CREATE INDEX idx_gl_gltrus   ON gl_tabel(gltrus);
CREATE INDEX idx_gl_gltrac   ON gl_tabel(gltrac);
CREATE INDEX idx_gl_status   ON gl_tabel(status_print);

CREATE TABLE t_upload (
    id          BIGSERIAL PRIMARY KEY,
    sess_id     VARCHAR(50) UNIQUE,
    file_name   TEXT,
    jml_data    INT DEFAULT 0,
    user_id     VARCHAR(50),
    adddt       TIMESTAMP DEFAULT NOW(),
    status      VARCHAR(20) DEFAULT 'Pending'
);

CREATE TABLE tlog_activity (
    id            BIGSERIAL PRIMARY KEY,
    user_id       VARCHAR(50),
    time_activity TIMESTAMP DEFAULT NOW(),
    ip            VARCHAR(50),
    name_activity VARCHAR(100),
    info          TEXT
);

CREATE TABLE tlog_transaction (
    id            BIGSERIAL PRIMARY KEY,
    user_id       VARCHAR(50),
    time_activity TIMESTAMP DEFAULT NOW(),
    name_activity VARCHAR(100),
    tinfo         TEXT
);
