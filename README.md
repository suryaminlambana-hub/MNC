# AUSY — MNC Bank Accounting Upload System (Java)

Modernized AUSY: a Spring Boot rewrite of the legacy PHP accounting upload system.
Bulk GL upload with dual-control (Maker–Checker), GLTRRF export for core banking,
resumable chunked upload, audit trail, and RBAC.

## Stack
- **Java 21** · **Spring Boot 3.3.5** (Spring MVC)
- **Thymeleaf** server-rendered UI (bundled in the JAR — no separate frontend build)
- **Spring Data JPA / Hibernate** · **PostgreSQL** · **Flyway** migrations
- **Spring Security** (form login, BCrypt) · **Apache POI** (Excel) · **OpenPDF** (PDF)
- Build: **Maven** → single fat-JAR that serves UI + API

## Features
- Upload GL (Excel) with balance check (Debit = Credit) → GLTRRF byte-exact export
- Dual-control approval (Otorisasi) + Reject with decision note
- Update GL (Add/Edit/Delete) via change requests with before/after + soft-delete
- NALA Seamless Bulk Upload: chunked, SHA-256 checksum, **resumable across sessions**
- Immutable audit trail, Monitoring & Rekapitulasi, RBAC (group → menu access)
- Theme: MNC gold & black, light/dark toggle

## Jalankan di lokal (clone dari Git)

**Prasyarat (sekali saja):** OpenJDK **21** + **PostgreSQL**. Maven TIDAK perlu diinstal — pakai `./mvnw`.

```bash
# macOS
brew install openjdk@21 postgresql@16 && brew services start postgresql@16
# Ubuntu/Debian
sudo apt install -y openjdk-21-jdk postgresql && sudo systemctl start postgresql
```

**Langkah:**
```bash
# 1. clone
git clone https://github.com/SEAuser/AUSY-MNC-JAVA.git
cd AUSY-MNC-JAVA

# 2. bikin database + user (SEKALI), sebagai superuser PostgreSQL:
sudo -u postgres psql -f packaging/setup-db.sql     # Linux
psql -d postgres -f packaging/setup-db.sql          # Mac (kalau role OS-mu superuser)
#    -> otomatis bikin db "ausy" + user "ausy_user" / pass "ausy_pass_2026"

# 3. jalankan (Flyway otomatis bikin tabel + data awal saat start pertama)
DB_NAME=ausy DB_USER=ausy_user DB_PASS=ausy_pass_2026 ./mvnw spring-boot:run
```

Buka **http://localhost:8080/ausy** — login **`admin`/`admin123`** (atau `spv`/`spv123`, `user`/`user123`).

**Catatan:**
- **Wajib** set `DB_NAME/DB_USER/DB_PASS` di depan perintah — default di `application.yml` menunjuk DB dev penulis, jadi tanpa override akan gagal konek.
- Ganti port: tambah `SERVER_PORT=9090`. Ganti context path: `CONTEXT_PATH=` (root) atau biarkan `/ausy` (default).
- **Windows:** `set DB_NAME=ausy` (dst) dulu, lalu `mvnw.cmd spring-boot:run`.
- **FTP** (`FTP_*`) & **email** default mati — tidak memengaruhi run lokal.
- Reset total: jalankan ulang `packaging/setup-db.sql` (selalu fresh).

### Windows (step-by-step)

**Prasyarat** (install via winget, lalu tutup & buka ulang terminal):
```powershell
winget install EclipseAdoptium.Temurin.21.JDK
winget install PostgreSQL.PostgreSQL.16
```
> Saat instalasi PostgreSQL, catat password user `postgres` yang kamu set — dipakai di langkah 2.
> Jika `psql` / `java` tidak dikenali, tambahkan ke PATH: `C:\Program Files\PostgreSQL\16\bin` dan folder `bin` JDK 21.

**Langkah** (jalankan di folder repo setelah `git clone` + `cd AUSY-MNC-JAVA`):

1. Bikin database + user (SEKALI) — akan diminta password `postgres`:
```cmd
psql -U postgres -f packaging\setup-db.sql
```
2. Jalankan aplikasi:

   **Command Prompt (cmd):**
   ```cmd
   set DB_NAME=ausy
   set DB_USER=ausy_user
   set DB_PASS=ausy_pass_2026
   mvnw.cmd spring-boot:run
   ```
   **PowerShell:**
   ```powershell
   $env:DB_NAME="ausy"; $env:DB_USER="ausy_user"; $env:DB_PASS="ausy_pass_2026"
   .\mvnw.cmd spring-boot:run
   ```
3. Buka **http://localhost:8080/ausy** — login `admin`/`admin123`.

> Ganti port: tambah `set SERVER_PORT=9090` (cmd) atau `$env:SERVER_PORT="9090"` (PowerShell) sebelum menjalankan.
> Reset DB: jalankan ulang perintah `psql -U postgres -f packaging\setup-db.sql`.

## Deploy (plug-and-play zip)
```bash
./build-dist.sh          # -> target/app-dist.zip
```
The zip contains the JAR + `setup-db.sql`, `.env.example`, `start.sh`, `README.txt`.
On the target Linux host (needs Java 21 + PostgreSQL):
```bash
unzip app-dist.zip -d app && cd app
sudo -u postgres psql -f setup-db.sql
cp .env.example .env
chmod +x start.sh && ./start.sh
```
See `packaging/README.txt` for details.
