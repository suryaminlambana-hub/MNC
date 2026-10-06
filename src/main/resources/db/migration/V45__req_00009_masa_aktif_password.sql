-- REQ_AUSY_00009 — pengingat masa berlaku password (H-3 dari 30 hari).
--
-- Kebijakan "password berlaku 30 hari sejak reset terakhir" selama ini hanya ada di atas kertas:
-- tabel users tidak menyimpan kapan password terakhir diganti, jadi tidak ada satu pun angka
-- yang bisa dihitung mundur. Kolom inilah dasarnya.
--
-- Nomor versi sengaja melompati 44. V44 sempat terbit lalu ditarik kembali (commit revert), dan
-- basis data yang terlanjur menjalankannya masih mencatat versi 44 di flyway_schema_history —
-- memakai ulang nomor itu akan membuat migrasi ini dilewati diam-diam di sana.
ALTER TABLE users ADD COLUMN IF NOT EXISTS pass_changed_at TIMESTAMP;

-- Nilai awal: tanggal akun dibuat. Itu memang bukan tanggal ganti password, tapi ia jujur —
-- password akun sekurang-kurangnya seumur akunnya. Menyetelnya ke NOW() akan lebih enak dilihat
-- sekaligus berbohong: akun yang passwordnya sudah lama sekali tidak tersentuh akan tampak baru
-- saja diperbarui, dan justru itu yang ingin dicegah fitur ini.
UPDATE users SET pass_changed_at = COALESCE(cre_date, NOW()) WHERE pass_changed_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_users_pass_changed_at ON users(pass_changed_at);
