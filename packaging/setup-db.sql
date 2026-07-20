-- ============================================================
-- AUSY — PostgreSQL bootstrap (jalankan SEKALI, sebagai superuser)
--   Linux:  sudo -u postgres psql -f setup-db.sql
--   atau :  psql -U postgres -f setup-db.sql
--
-- Membuat database + user aplikasi yang fresh. SKEMA & DATA SEED
-- diterapkan otomatis oleh Flyway saat aplikasi pertama kali start.
-- Selalu fresh: drop dulu kalau sudah ada (anti state kotor).
-- ============================================================
\set ON_ERROR_STOP on

-- putuskan koneksi lama supaya DROP DATABASE tidak ke-block
SELECT pg_terminate_backend(pid)
  FROM pg_stat_activity
 WHERE datname = 'ausy' AND pid <> pg_backend_pid();

DROP DATABASE IF EXISTS ausy;
DROP USER     IF EXISTS ausy_user;

-- password DB fixed (samakan dengan DB_PASS di .env)
CREATE USER ausy_user WITH PASSWORD 'ausy_pass_2026';
CREATE DATABASE ausy OWNER ausy_user;

-- pastikan user bisa membuat tabel di schema public (PostgreSQL 15+)
\connect ausy
GRANT ALL ON SCHEMA public TO ausy_user;
ALTER SCHEMA public OWNER TO ausy_user;

\echo '>> setup-db.sql selesai: database "ausy" + user "ausy_user" siap.'
