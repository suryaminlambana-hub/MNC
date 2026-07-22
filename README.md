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

Prasyarat (sekali saja): **JDK 21** + **PostgreSQL**. Maven TIDAK perlu diinstal — pakai `./mvnw`.

```bash
# 1. clone
git clone https://github.com/SEAuser/AUSY-MNC-JAVA.git
cd AUSY-MNC-JAVA

# 2. bikin database + user (sekali). Jalankan sebagai superuser PostgreSQL:
#    Linux : sudo -u postgres psql -f packaging/setup-db.sql
#    Mac   : psql -d postgres -f packaging/setup-db.sql
#    -> membuat db "ausy" + user "ausy_user" / pass "ausy_pass_2026"

# 3. jalankan (Flyway otomatis bikin skema + data awal saat start pertama)
DB_NAME=ausy DB_USER=ausy_user DB_PASS=ausy_pass_2026 ./mvnw spring-boot:run
#   Windows: set env dulu lalu  mvnw.cmd spring-boot:run
```
Buka **http://localhost:8080/ausy** — login `admin`/`admin123` (atau `spv`/`spv123`, `user`/`user123`).

> Wajib set `DB_NAME/DB_USER/DB_PASS` (default di `application.yml` menunjuk DB dev penulis). Env lain: `SERVER_PORT`, `CONTEXT_PATH`, `DB_HOST`, `DB_PORT`, dan `FTP_*` (opsional). Flyway membuat skema + seed otomatis.

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
