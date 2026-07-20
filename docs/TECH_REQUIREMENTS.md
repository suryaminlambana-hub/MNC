# AUSY — Technical Requirements Specification
**MNC Bank — Accounting Upload System (Java / Spring Boot)**
Versi 1.0 · Klasifikasi: Internal

> Sumber acuan: Tech Doc (HLA, Application Architecture, Server Topology & Tech Stack,
> Data Flow), Proposal Teknis NALA v2.1, dan verifikasi terhadap implementasi aktual
> (repo `AUSY-MNC-JAVA`). Kolom **Status** mencerminkan kondisi kode saat ini:
> ✅ Implemented · 🟡 Stub (hook siap, butuh konfigurasi/infra eksternal) · ⛔ Planned.

---

## 1. Tujuan & Ruang Lingkup
Memodernisasi AUSY (dari PHP legacy) menjadi aplikasi web enterprise berbasis Java yang
mendukung unggah transaksi GL secara massal, validasi akuntansi, kontrol ganda
(Maker–Checker), ekspor GLTRRF ke core banking, dan audit penuh.

**In-scope:** unggah GL, verifikasi balance, dual-control (upload/export/update),
GLTRRF export, NALA seamless bulk upload, async processing, RBAC, audit trail,
monitoring & rekapitulasi, laporan jurnal PDF.
**Out-of-scope:** perubahan core banking, penyediaan server SMTP/FTP/DNS, hardware,
data UAT/produksi (tanggung jawab Bank).

---

## 2. Kebutuhan Fungsional (Functional Requirements)

| ID | Kebutuhan | Deskripsi | Status |
|----|-----------|-----------|--------|
| FR-01 | Autentikasi | Login form + logout, password BCrypt, session management | ✅ |
| FR-02 | User Management | CRUD user, set group, aktif/non-aktif, ubah password | ✅ |
| FR-03 | Role/Group Management | CRUD group; level/inisial | ✅ |
| FR-04 | Access Management (RBAC) | Menu yang tampil per group (group → menu) | ✅ |
| FR-05 | Approval Group | Definisi "group X boleh meng-approve group Y" | ✅ |
| FR-06 | Upload GL (Excel) | Parse sheet DATA, validasi tanggal posting = hari ini | ✅ |
| FR-07 | GL Verification | Balance check (Debit = Credit), posisi D/C, validasi format | ✅ |
| FR-08 | Dual Control — Upload | Data masuk status `PENDING`, hanya berlaku setelah di-approve | ✅ |
| FR-09 | Otorisasi / Export | Checker approve → generate **GLTRRF** (fixed-width) + jurnal PDF | ✅ |
| FR-10 | Reject + catatan | Checker menolak paket + catatan wajib; data dibuang dari antrian | ✅ |
| FR-11 | Update GL (Add/Edit/Delete) | Perubahan baris GL via change-request, before/after, **soft-delete** | ✅ |
| FR-12 | GL Code Master | CRUD kode GL (NoGL + D/C unik), impor CSV, status, audit kolom | ✅ |
| FR-13 | NALA Seamless Bulk Upload | Chunked upload + SHA-256 + **resume lintas sesi** | ✅ |
| FR-14 | Async Backend Processing | Pemrosesan file di background (@Async) | ✅ |
| FR-15 | Audit Trail | Log immutable (append-only): login, upload, approve, reject, delete, perubahan GL | ✅ |
| FR-16 | Monitoring & Rekapitulasi | Dashboard (stat + chart 7 hari), rekap paket, viewer audit | ✅ |
| FR-17 | Laporan Jurnal | Ekspor jurnal per paket dalam PDF | ✅ |
| FR-18 | Notifikasi Email | Email ke Checker saat ada pending (SMTP) | 🟡 |
| FR-19 | Transfer Out ke FTP GL | Push berkas GLTRRF ke FTP/FTPS server GL (Apache Commons Net) — aktif bila `FTP_HOST` diset | ✅ |

---

## 3. Kebutuhan Non-Fungsional (Non-Functional Requirements)

| ID | Kategori | Target | Status |
|----|----------|--------|--------|
| NFR-01 | Performa | Response time ≤ 3 detik untuk 95% request | ✅ (arsitektur async + JPA) |
| NFR-02 | Keandalan | Uptime ≥ 99.5% masa stabilisasi; recovery terukur | ✅ (single-JAR, stateless web) |
| NFR-03 | Keamanan | OWASP Top 10: 0 critical/high | ✅ (lihat §7) |
| NFR-04 | Auditability | 100% transaksi tercatat, log immutable | ✅ |
| NFR-05 | Skalabilitas beban | Bulk upload file besar tanpa blok UI (chunk + async) | ✅ |
| NFR-06 | Portabilitas | Deploy di RHEL 9+ dan Windows Server 2022 tanpa ubah kode | ✅ (fat-JAR + env config) |
| NFR-07 | Maintainability | 3-tier, Spring Data JPA, migrasi Flyway | ✅ |
| NFR-08 | Usability | UI responsif, light/dark, DataTables (search/sort/paging) | ✅ |
| NFR-09 | Enkripsi transit | HTTPS/TLS 1.3 (di depan reverse proxy) | 🟡 (:8443 optional) |

---

## 4. Arsitektur (3-Tier)

```
Client (Browser)  ──HTTPS──►  Application Layer (Spring Boot)  ──JDBC──►  Database (PostgreSQL)
  HTML5/CSS3                    Controllers (@Controller)                  users, group_user, menu,
  Bootstrap 5, jQuery 3        Services  (@Service)                        gl_tabel, gl_code,
  DataTables, AJAX             Repositories (Spring Data JPA)              gl_change_request (History),
  NALA bulk transfer          Thymeleaf views + REST (NALA)               tlog_* (Auditlogs), nala_uploads
                              Embedded Tomcat :8080, context /ausy
        │                                                                        ▲
        └────────── External: FTP GL (out) · SMTP (out) · Core Banking GLTRRF (out) ─┘
```

- **Presentation:** server-rendered (Thymeleaf), aset offline (webjars, tanpa CDN).
- **Application:** Spring MVC, Spring Security, transaksi @Transactional, @Async worker.
- **Data:** PostgreSQL; skema & seed dikelola **Flyway** (V1–V5) otomatis saat start.

---

## 5. Tech Stack (spesifik & terverifikasi)

| Komponen | Pilihan | Versi |
|----------|---------|-------|
| Runtime | Java (LTS) | **21** |
| Framework | Spring Boot (Spring 6) | **3.3.5** |
| App server | Embedded Apache Tomcat | 10.1.x |
| View | Thymeleaf | (Spring Boot managed) |
| Frontend | Bootstrap / jQuery / DataTables / Font Awesome | 5.3.3 / 3.7.1 / 1.13.5 / 6.5.2 |
| Data access | Spring Data JPA (Hibernate) | 6.5.x |
| Database | PostgreSQL (PGVector opsional) | 16–18 |
| Migrasi | Flyway | 10.x |
| Keamanan | Spring Security (BCrypt) | (managed) |
| Excel | Apache POI | 5.3.0 |
| PDF | OpenPDF | 2.0.3 |
| Build | Maven → single fat-JAR | 3.9.x |

---

## 6. Kebutuhan Infrastruktur (Server)

| Item | Minimum | Rekomendasi |
|------|---------|-------------|
| Tipe | Physical / VM (all-in-one) | Physical / VM |
| OS | RHEL 9+ / Windows Server 2022 | Bank preference (vendor-supported) |
| vCPU | 4 | 4 |
| RAM | 8 GB | 16 GB |
| Storage | 50 GB | 200 GB (staging, log, data) |
| Runtime | OpenJDK 21 + PostgreSQL 14+ | idem |

Staging folder: **Incoming / Temporary / Outgoing** (area proses sebelum masuk tabel GL).

---

## 7. Keamanan & Compliance

| Kontrol | Penerapan | Status |
|---------|-----------|--------|
| A01 Broken Access Control | RBAC (group→menu) + dual-control gate | ✅ |
| A02 Cryptographic Failures | Password BCrypt; TLS 1.3 di transit | ✅ / 🟡 |
| A03 Injection | Prepared statements via JPA/Hibernate | ✅ |
| A05 Security Misconfig | Static asset & /login permitAll; sisanya authenticated | ✅ |
| A07 Auth Failures | Spring Security + session + rotasi CSRF token | ✅ |
| A09 Logging Failures | Audit trail immutable (append-only) retensi panjang | ✅ |
| CSRF | Token per-request di semua POST form & endpoint NALA | ✅ |
| Data integrity | Verifikasi SHA-256 pada bulk upload | ✅ |
| Regulasi | Selaras OWASP Top 10, ISO 27001, UU PDP, POJK 11/2022 | referensi proposal |

---

## 8. Integrasi Sistem Eksternal

| Sistem | Protokol | Arah | Status |
|--------|----------|------|--------|
| Core Banking / GL System | File GLTRRF (fixed-width) | Keluar | ✅ (generate) |
| FTP Server GL | FTP / FTPS | Keluar | ✅ (Commons Net; aktif bila `FTP_HOST` diset) |
| SMTP Server | SMTP/SMTPS (TLS) | Keluar | 🟡 (EmailService, `spring.mail.*`) |
| Client PC | HTTPS (TLS 1.3) | Masuk | ✅ (app) / 🟡 (TLS di proxy) |

---

## 9. Alur Data (Data Lifecycle)
`File Upload (client)` → `Stream to Server (NALA chunked + verify/SHA-256)` →
`Import to Staging (dedup/format)` → `Parsing to Table (validasi per baris)` →
`Additional Data (Add/Edit/Delete via dual-control)` → `Compile (GLTRRF + balance)` →
`Transfer Out (FTP GL)`. Semua tahap tercatat di audit trail. **Status: ✅** kecuali
Transfer Out FTP = 🟡.

---

## 10. Workflow Bisnis (Dual Control) — semua ✅
- **8.1 Upload GL:** Maker upload → sistem validasi → `PENDING` → Checker approve/reject (+catatan) → tersimpan definitif / dibuang.
- **8.2 Export GL:** Checker otorisasi → GLTRRF + PDF dihasilkan (→ FTP 🟡) → audit.
- **8.3 Update GL:** Maker ajukan Add/Edit/Delete + justifikasi → Checker lihat before/after → approve (terapkan; Delete = soft-delete) / reject (data tak berubah).

---

## 11. Model Data (tabel utama)
`users`, `group_user`, `menu`, `group_menu`, `approval_group`, `gl_code`,
`gl_tabel` (transaksi GL, kolom `active` soft-delete), `gl_change_request` (History 8.3),
`tlog_activity` + `tlog_transaction` (Auditlogs, append-only), `nala_uploads`,
`web_sequence`, `t_upload`. Skema di `db/migration/V1–V5` (Flyway).

---

## 12. Konfigurasi & Deploy
- Env: `SERVER_PORT`, `CONTEXT_PATH` (default `/ausy`), `DB_HOST/PORT/NAME/USER/PASS`,
  `spring.mail.*` (email), `ausy.ftp.host` (FTP).
- Paket plug-and-play: `build-dist.sh` → `app-dist.zip` (JAR + `setup-db.sql` +
  `.env.example` + `start.sh` + `README.txt`).
- Akses: `http://<host>:<port>/ausy`. Login awal: admin/admin123 (ganti setelah go-live).

---

## 13. Kriteria Penerimaan (Acceptance)
- 100% fungsi in-scope (§2 status ✅) beroperasi di platform target.
- Balance check & GLTRRF byte-exact vs sistem lama (golden-master).
- 0 critical/high pada security scan.
- Audit trail 100% mencatat transaksi.
- Response ≤ 3 detik untuk 95% request; bulk upload besar tidak memblokir UI.

---
*Butuh diselesaikan sebelum produksi (di luar app):* SMTP aktif (FR-18), FTP/FTPS GL
(FR-19), TLS/HTTPS di reverse proxy (NFR-09), dan (opsional) PGVector bila fitur berbasis
vector diaktifkan.
