-- V4 — Font Awesome icons per menu (parents + children) for the sidebar
UPDATE menu SET icon='fa-solid fa-file-import'   WHERE id_menu=100;  -- Import
UPDATE menu SET icon='fa-solid fa-table-list'    WHERE id_menu=300;  -- Data
UPDATE menu SET icon='fa-solid fa-book'          WHERE id_menu=500;  -- GL
UPDATE menu SET icon='fa-solid fa-circle-check'  WHERE id_menu=400;  -- Verification
UPDATE menu SET icon='fa-solid fa-gear'          WHERE id_menu=200;  -- Master Data

UPDATE menu SET icon='fa-solid fa-file-excel'      WHERE id_menu=10;  -- Upload Excel
UPDATE menu SET icon='fa-solid fa-cloud-arrow-up'  WHERE id_menu=11;  -- NALA Seamless
UPDATE menu SET icon='fa-solid fa-list'            WHERE id_menu=9;   -- Legacy List GL
UPDATE menu SET icon='fa-solid fa-trash-can'       WHERE id_menu=13;  -- Delete Paket
UPDATE menu SET icon='fa-solid fa-pen-to-square'   WHERE id_menu=21;  -- Manage GL Code
UPDATE menu SET icon='fa-solid fa-list-ul'         WHERE id_menu=22;  -- List GL
UPDATE menu SET icon='fa-solid fa-file-export'     WHERE id_menu=17;  -- Export to Text
UPDATE menu SET icon='fa-solid fa-user'            WHERE id_menu=2;   -- User
UPDATE menu SET icon='fa-solid fa-user-group'      WHERE id_menu=3;   -- Group User
UPDATE menu SET icon='fa-solid fa-bars-staggered'  WHERE id_menu=4;   -- Menu
UPDATE menu SET icon='fa-solid fa-key'             WHERE id_menu=5;   -- Access Menu
UPDATE menu SET icon='fa-solid fa-user-shield'     WHERE id_menu=20;  -- Approval Group
