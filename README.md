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

## Run (dev)
```bash
# needs Java 21 + PostgreSQL running
createdb ausy_sb
DB_NAME=ausy_sb DB_USER=$(whoami) mvn spring-boot:run
# open http://localhost:8080/ausy   (admin/admin123, spv/spv123, user/user123)
```
Config via env: `SERVER_PORT`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASS`.
Flyway creates the schema + seed automatically on first start.

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
