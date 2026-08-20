-- Maker mengisi alasan/justifikasi saat mengajukan export GL, ditampilkan ke checker (Otorisasi Export GL).
ALTER TABLE gl_export_request ADD COLUMN IF NOT EXISTS note_maker VARCHAR(1000);