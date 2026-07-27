-- ============================================================
-- V7 — Role restructure (Segregation of Duties) per MNC Bank (Pak Robby):
--   (1) CONFIG/SETTING maker  -> urus akses & parameter, bikin semua user
--   (2) CONFIG APPROVER       -> approve perubahan config (approve-only)
--   (3) BUSINESS MAKER        -> upload/kelola data GL (bisnis)
--   (4) BUSINESS APPROVER     -> otorisasi/approve bisnis (approve-only)
-- Prinsip: yang approve TIDAK boleh add/edit/delete; config TIDAK pegang data bisnis.
-- Matriks approval: (4)->(3) bisnis, (2)->(1) config.
-- ============================================================

UPDATE group_user SET nama_group='CONFIG / SETTING',  level='CONFIG',     inisial='CFG' WHERE id_group=1;
UPDATE group_user SET nama_group='CONFIG APPROVER',   level='CONFIG APV', inisial='CFA' WHERE id_group=2;
UPDATE group_user SET nama_group='BUSINESS MAKER',    level='MAKER',      inisial='MKR' WHERE id_group=3;
UPDATE group_user SET nama_group='BUSINESS APPROVER', level='CHECKER',    inisial='CHK' WHERE id_group=4;

-- Re-map seed users to the new roles
UPDATE users SET id_group=1, level='CONFIG'  WHERE user_id='admin';   -- Config/Setting maker
UPDATE users SET id_group=3, level='MAKER'   WHERE user_id='user';    -- Business maker
UPDATE users SET id_group=4, level='CHECKER' WHERE user_id='spv';     -- Business approver

-- Config Approver demo user (password sama dgn admin: admin123)
INSERT INTO users (user_id,pass,nama,status_account,email,id_group,level,addby)
SELECT 'adminapv', (SELECT pass FROM users WHERE user_id='admin'),
       'Config Approver', 1, 'adminapv@mncbank.co.id', 2, 'CONFIG APV', 'system'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE user_id='adminapv');

-- Rebuild menu access (segregation)
DELETE FROM group_menu;
-- (1) CONFIG: Master Data + GL Code (parameter)
INSERT INTO group_menu (id_group,id_menu) VALUES (1,2),(1,3),(1,4),(1,5),(1,20),(1,21),(1,22);
-- (2) CONFIG APPROVER: monitoring (Audit, Rekap, List Data GL)
INSERT INTO group_menu (id_group,id_menu) VALUES (2,34),(2,32),(2,9);
-- (3) BUSINESS MAKER: Import + Data
INSERT INTO group_menu (id_group,id_menu) VALUES (3,10),(3,11),(3,31),(3,13),(3,9),(3,32);
-- (4) BUSINESS APPROVER: Verification (approve) + monitoring
INSERT INTO group_menu (id_group,id_menu) VALUES (4,17),(4,33),(4,34),(4,32),(4,9);

-- Approval matrix (approver_group -> target_group)
DELETE FROM approval_group;
INSERT INTO approval_group (approver_group,target_group) VALUES (4,3),(2,1);
