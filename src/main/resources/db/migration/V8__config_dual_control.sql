-- ============================================================
-- V8 — Admin Approval (Config Approver ②) revisions:
--   * Config dual control: Add/Edit/Delete USER goes through an approval
--     request (with reason) that ② authorizes before it is applied.
--   * New Verification menu "Otorisasi User" for ②.
--   * Hide Data menus from ②'s sidebar (audit + config approval only).
-- Audit-view scoping (② sees only groups 1+2) is enforced in AuditController.
-- ============================================================

-- Config change requests (mirrors gl_change_request, for the config side)
CREATE TABLE IF NOT EXISTS config_change_request (
    id           BIGSERIAL PRIMARY KEY,
    entity       VARCHAR(20)  NOT NULL DEFAULT 'USER',   -- what is being changed
    op           VARCHAR(10)  NOT NULL,                  -- ADD / EDIT / DELETE
    target_id    VARCHAR(50),                            -- user_id being changed (null for ADD)
    maker        VARCHAR(50)  NOT NULL,
    maker_group  INTEGER,
    checker      VARCHAR(50),
    status       VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    before_json  TEXT,
    after_json   TEXT,
    note_maker   TEXT,
    note_checker TEXT,
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    decided_at   TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ccr_status ON config_change_request (status);

-- New Verification menu: Otorisasi User (config approval), owned by ② Admin Approval
INSERT INTO menu (id_menu, nama_menu, url, icon, urutan, parent, active)
VALUES (35, 'Otorisasi User', '/config-change/review', 'fa-solid fa-user-check', 3, 400, 1)
ON CONFLICT (id_menu) DO NOTHING;

-- Admin Approval (②) sidebar: hide Data (List GL 9, Rekap 32); keep Audit (34) + add config approval (35)
DELETE FROM group_menu WHERE id_group = 2;
INSERT INTO group_menu (id_group, id_menu) VALUES (2, 34), (2, 35);
