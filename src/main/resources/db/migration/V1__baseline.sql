-- ============================================================
-- AUSY (Spring Boot) — V1 baseline: identity, RBAC, approval, GL code.
-- PostgreSQL 18. Transaction tables (gl_tabel, staging, nala_uploads, logs)
-- arrive in later migrations alongside the upload/otorisasi phases.
-- ============================================================

CREATE TABLE group_user (
    id_group    SERIAL PRIMARY KEY,
    nama_group  VARCHAR(50) NOT NULL,
    level       VARCHAR(20),
    inisial     VARCHAR(20),
    adddt       TIMESTAMP DEFAULT NOW()
);

CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    user_id         VARCHAR(50)  NOT NULL UNIQUE,
    pass            VARCHAR(255) NOT NULL,          -- bcrypt
    nama            VARCHAR(100),
    status_account  SMALLINT DEFAULT 1,             -- 1=active, 0=non-active
    email           VARCHAR(100),
    id_group        INT REFERENCES group_user(id_group),
    level           VARCHAR(20),
    img             VARCHAR(255),
    last_login      TIMESTAMP,
    cre_date        TIMESTAMP DEFAULT NOW(),
    addby           VARCHAR(50)
);
CREATE INDEX idx_users_user_id ON users(user_id);

-- Clean URL-based menu (replaces the legacy sha1 id_module routing)
CREATE TABLE menu (
    id_menu   SERIAL PRIMARY KEY,
    nama_menu VARCHAR(100) NOT NULL,
    url       VARCHAR(150),            -- NULL for parent headers
    icon      VARCHAR(50),
    urutan    INT DEFAULT 0,
    parent    INT DEFAULT 0,
    active    SMALLINT DEFAULT 1
);

CREATE TABLE group_menu (
    id        BIGSERIAL PRIMARY KEY,
    id_group  INT REFERENCES group_user(id_group),
    id_menu   INT REFERENCES menu(id_menu)
);
CREATE INDEX idx_group_menu_group ON group_menu(id_group);

CREATE TABLE approval_group (
    id              BIGSERIAL PRIMARY KEY,
    approver_group  INT NOT NULL,
    target_group    INT NOT NULL,
    adddt           TIMESTAMP DEFAULT NOW(),
    CONSTRAINT uq_approval_pair UNIQUE (approver_group, target_group)
);

CREATE TABLE gl_code (
    id            BIGSERIAL PRIMARY KEY,
    code          VARCHAR(50) NOT NULL,    -- NoGL
    position      VARCHAR(2)  NOT NULL,    -- D / C
    description   VARCHAR(255),
    status        SMALLINT DEFAULT 1,
    created_by    VARCHAR(50),
    created_date  TIMESTAMP DEFAULT NOW(),
    modified_by   VARCHAR(50),
    modified_date TIMESTAMP,
    CONSTRAINT uq_glcode_pos UNIQUE (code, position)
);

-- ============================================================
-- Seed
-- ============================================================
INSERT INTO group_user (id_group, nama_group, level, inisial) OVERRIDING SYSTEM VALUE VALUES
 (1,'SUPERADMIN','SUPERADMIN','SA'),(2,'ADMIN','ADMIN','AD'),(3,'SUPERVISOR','SPV','SP'),(4,'STAFF','USER','ST');
SELECT setval('group_user_id_group_seq', 4);

-- bcrypt hashes carried over from the PHP app: admin123 / spv123 / user123
INSERT INTO users (user_id,pass,nama,status_account,email,id_group,level,addby) VALUES
 ('admin','$2y$12$X7wmjqas.qvH9c64Zd9HoO/81gp7eYaIOmDX/iGo0tN4fkbSwWN0O','Administrator',1,'admin@mncbank.co.id',2,'ADMIN','system'),
 ('spv','$2y$12$WN6ReQcvuLorENnERcNHHevzxdMBPJmv/CDIH.42mVh7lw63JF6NG','Supervisor Accounting',1,'spv@mncbank.co.id',3,'SPV','system'),
 ('user','$2y$12$Rg.ndl61KnVqVnosSqlTreLMd97pvqEhUgKw6FYw21kOk7bc1VsR6','Staff Accounting',1,'user@mncbank.co.id',4,'USER','system');

INSERT INTO menu (id_menu,nama_menu,url,icon,parent,urutan) OVERRIDING SYSTEM VALUE VALUES
 (100,'Import',NULL,'icon-docs',0,1),
 (300,'Data',NULL,'fa fa-th-list',0,2),
 (500,'GL',NULL,'fa fa-gear',0,3),
 (400,'Verification',NULL,'icon-briefcase',0,4),
 (200,'Master Data',NULL,'icon-wallet',0,5),
 (10,'Upload Excel','/upload',NULL,100,1),
 (9,'Legacy - List GL','/gl-legacy',NULL,300,1),
 (13,'Delete Paket','/gl-delete',NULL,300,2),
 (21,'Manage GL Code','/gl-code',NULL,500,1),
 (22,'List GL','/gl-code/list',NULL,500,2),
 (17,'Export to Text','/otorisasi',NULL,400,1),
 (2,'User','/users',NULL,200,1),
 (3,'Group User','/groups',NULL,200,2),
 (4,'Menu','/menus',NULL,200,3),
 (5,'Access Menu','/access',NULL,200,4),
 (20,'Approval Group','/approval',NULL,200,5);
SELECT setval('menu_id_menu_seq', 500);

-- ADMIN(2): everything; SUPERVISOR(3): data + verification; STAFF(4): upload + list
INSERT INTO group_menu (id_group,id_menu) VALUES
 (2,10),(2,9),(2,13),(2,21),(2,22),(2,17),(2,2),(2,3),(2,4),(2,5),(2,20),
 (3,9),(3,13),(3,17),
 (4,10),(4,9);

INSERT INTO approval_group (approver_group,target_group) VALUES (2,3),(2,4),(3,4);

INSERT INTO gl_code (code,position,description,status,created_by) VALUES
 ('100','D','KAS KANTOR PUSAT',1,'system'),
 ('100','C','KAS KANTOR PUSAT',1,'system'),
 ('900','D','KANTOR PUSAT OPERASIONAL',1,'system'),
 ('901','C','BIAYA OPERASIONAL',1,'system');
