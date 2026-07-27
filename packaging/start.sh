#!/usr/bin/env bash
# ============================================================
# AUSY launcher — load .env, pastikan DB siap, jalankan aplikasi.
# ============================================================
set -euo pipefail
cd "$(dirname "$0")"

# --- 1. load config (.env) ---
if [ ! -f .env ]; then
  echo "ERROR: file .env belum ada. Jalankan dulu:  cp .env.example .env"
  exit 1
fi
set -a; source ./.env; set +a

SERVER_PORT="${SERVER_PORT:-8080}"
CONTEXT_PATH="${CONTEXT_PATH:-/ausy}"
DB_HOST="${DB_HOST:-127.0.0.1}"; DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-ausy}"; DB_USER="${DB_USER:-ausy_user}"; DB_PASS="${DB_PASS:-}"
export SERVER_PORT CONTEXT_PATH DB_HOST DB_PORT DB_NAME DB_USER DB_PASS

# --- 2. cek Java 21+ ---
if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: Java tidak ditemukan. Install OpenJDK 21:"
  echo "       Debian/Ubuntu : sudo apt install -y openjdk-21-jre"
  echo "       RHEL/Fedora   : sudo dnf install -y java-21-openjdk"
  exit 1
fi
JV=$(java -version 2>&1 | head -1 | grep -oE '[0-9]+' | head -1)
if [ "${JV:-0}" -lt 21 ]; then
  echo "ERROR: butuh Java 21 atau lebih baru. Terdeteksi: $(java -version 2>&1 | head -1)"
  exit 1
fi

# --- 3. cari JAR ---
JAR=$(ls ausy-*.jar 2>/dev/null | head -1 || true)
if [ -z "$JAR" ]; then echo "ERROR: file JAR aplikasi tidak ditemukan di folder ini."; exit 1; fi

# --- 4. pastikan DB bisa diakses; kalau belum, jalankan setup-db.sql otomatis ---
db_ok() { PGPASSWORD="$DB_PASS" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -tAc 'select 1' >/dev/null 2>&1; }

if ! db_ok; then
  echo ">> Database '$DB_NAME' belum siap / tidak bisa diakses."
  if ! command -v psql >/dev/null 2>&1; then
    echo "ERROR: 'psql' tidak ada. Install PostgreSQL client, atau jalankan manual:"
    echo "       psql -U postgres -f setup-db.sql"
    exit 1
  fi
  echo ">> Menjalankan setup-db.sql (perlu akses superuser PostgreSQL)..."
  if command -v sudo >/dev/null 2>&1 && id postgres >/dev/null 2>&1; then
    sudo -u postgres psql -v ON_ERROR_STOP=1 -f setup-db.sql
  else
    psql -U postgres -v ON_ERROR_STOP=1 -f setup-db.sql
  fi
  if ! db_ok; then
    echo "ERROR: DB tetap tidak bisa diakses setelah setup."
    echo "       Cek kredensial di .env vs setup-db.sql, dan apakah PostgreSQL jalan."
    exit 1
  fi
  echo ">> Database siap."
fi

# --- 5. jalankan (Flyway otomatis bikin skema + seed saat pertama kali) ---
echo ">> AUSY jalan di  http://localhost:${SERVER_PORT}${CONTEXT_PATH}    (Ctrl+C untuk berhenti)"
echo ">> Login: admin(config) / adminapv / user(maker) / spv(approver) — pw sama seperti sebelumnya"
exec java -jar "$JAR"
