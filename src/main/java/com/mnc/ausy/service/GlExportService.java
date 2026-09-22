package com.mnc.ausy.service;

import com.mnc.ausy.domain.ExportTransfer;
import com.mnc.ausy.domain.GlExportRequest;
import com.mnc.ausy.domain.GlTabel;
import com.mnc.ausy.repo.ExportTransferRepository;
import com.mnc.ausy.repo.GlExportRequestRepository;
import com.mnc.ausy.repo.GlTabelRepository;
import com.mnc.ausy.security.AusyUserDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Export flow (separated from otorisasi upload):
 *   Maker "Ajukan Export" (List GL Aktif) → checker "Otorisasi Export GL" approve → ACCTXT ke FTP.
 * File ACCTXT tidak pernah diunduh ke komputer; hanya dikirim ke FTP untuk diproses core.
 */
@Service
public class GlExportService {
    private final GlTabelRepository gl;
    private final GlExportRequestRepository requests;
    private final ExportTransferRepository transfers;
    private final ApprovalService approval;
    private final AuditService audit;
    private final FtpService ftp;
    private final GlChangeService changes;
    private final DataScopeService scope;

    @Value("${ausy.txt-dir:}") private String txtDir;
    /** Tujuan file ACCTXT — di produksi diarahkan ke C:/inetpub/ftproot (BUG_AUSY_00146). */
    @Value("${ausy.export-dir:}") private String exportDir;

    private static final String DEFAULT_EXPORT_DIR = "data/txt";
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(GlExportService.class);

    /**
     * Direktori tujuan ACCTXT, selalu absolut.
     *
     * Nilai KOSONG diperlakukan seperti tidak diisi (BUG_AUSY_00151). Default placeholder Spring
     * hanya berlaku kalau variabelnya tidak ada sama sekali, sedangkan {@code .env.example} justru
     * mengirim {@code AUSY_EXPORT_DIR=} kosong: hasilnya {@code Paths.get("")}, yaitu direktori
     * kerja proses — file ACCTXT nyangkut di situ, bukan di folder yang dituju, tanpa error apa pun.
     *
     * Absolut supaya path yang dicatat & ditampilkan bisa langsung dibuka, bukan "data/txt" yang
     * artinya tergantung dari mana aplikasi dijalankan.
     */
    Path exportDir() {
        String v = exportDir == null ? "" : exportDir.trim();
        if (v.isEmpty()) v = txtDir == null ? "" : txtDir.trim();
        if (v.isEmpty()) v = DEFAULT_EXPORT_DIR;
        return Paths.get(v).toAbsolutePath().normalize();
    }

    /**
     * Sebutkan direktorinya saat start, sekalian pastikan bisa ditulis.
     *
     * Kegagalan menulis ACCTXT dulu baru ketahuan saat checker menekan approve, dan yang dicari
     * orang ("kenapa tidak muncul di inetpub/ftproot") justru tidak pernah disebut di mana pun
     * (BUG_AUSY_00151). Sekarang salah setelan atau folder tanpa izin tulis kelihatan sejak boot.
     */
    @jakarta.annotation.PostConstruct
    void logExportDir() {
        Path d = exportDir();
        boolean isNew = !Files.isDirectory(d);
        // Folder induk yang juga tidak ada = nyaris pasti salah tulis (mis. "C:\inethub\ftproot"
        // untuk "C:\inetpub\ftproot"). Tanpa peringatan ini, folder salah itu dibuat begitu saja
        // dan hasil export terbit di tempat yang tidak dilihat siapa pun.
        Path parent = d.getParent();
        boolean orphan = isNew && parent != null && !Files.isDirectory(parent);
        try {
            Files.createDirectories(d);
            if (!Files.isWritable(d)) {
                LOG.warn("Folder hasil export {} TIDAK BISA DITULIS — file export akan gagal dibuat."
                        + " Cek izin folder / setelan AUSY_EXPORT_DIR.", d);
            } else if (orphan) {
                LOG.warn("Folder hasil export {} DIBUAT BARU, dan folder induknya ({}) juga belum ada."
                        + " Periksa ejaan AUSY_EXPORT_DIR — hasil export akan terbit di folder ini,"
                        + " bukan di folder yang mungkin Anda maksud.", d, parent);
            } else if (isNew) {
                LOG.info("File hasil export ditulis ke {} (folder baru dibuat)", d);
            } else {
                LOG.info("File ACCTXT ditulis ke {}", d);
            }
        } catch (Exception e) {
            LOG.warn("Folder hasil export {} tidak bisa disiapkan: {}. Cek AUSY_EXPORT_DIR & izin folder.",
                    d, e.getMessage());
        }
    }

    public GlExportService(GlTabelRepository gl, GlExportRequestRepository requests, ExportTransferRepository transfers,
                           ApprovalService approval, AuditService audit, FtpService ftp, GlChangeService changes,
                           DataScopeService scope) {
        this.gl = gl; this.requests = requests; this.transfers = transfers;
        this.approval = approval; this.audit = audit; this.ftp = ftp; this.changes = changes; this.scope = scope;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /** JPA kadang membungkus hasil agregat multi-kolom dalam Object[1][]; rapikan di satu tempat. */
    private static Object[] unwrap(Object[] row) {
        return (row != null && row.length == 1 && row[0] instanceof Object[] inner) ? inner : row;
    }

    /** Group pengaju export: dari permintaannya, baru dari record user kalau belum tersimpan. */
    private Integer groupOf(GlExportRequest r) {
        return r.getRequesterGroup() != null ? r.getRequesterGroup() : approval.groupOf(r.getRequestedBy());
    }

    public long pendingCount() { return requests.countByStatus("PENDING"); }

    /** All pending export requests, unscoped — for the maker dashboard's "Perubahan GL Pending" overview. */
    public List<GlExportRequest> allPending() {
        return requests.findByStatusOrderByCreatedAtDesc("PENDING");
    }

    /** Idem, dibatasi maker yang boleh dilihat user ini (BUG_AUSY_00138). */
    public List<GlExportRequest> allPending(AusyUserDetails me) {
        return scope.filterBy(me, allPending(), GlExportRequest::getRequestedBy);
    }

    /** Maker's own pending export requests (still waiting on checker) — shown so they don't re-submit blindly. */
    public List<GlExportRequest> myPending(String username) {
        return requests.findByRequestedByAndStatusOrderByCreatedAtDesc(username, "PENDING");
    }

    /** One export request + its FTP delivery outcome (if it got that far) — for the maker's "Riwayat Export". */
    public record ExportHistoryItem(GlExportRequest req, ExportTransfer transfer) {}

    /** Maker's full export history — pending, approved, and rejected — with FTP send status where applicable. */
    public List<ExportHistoryItem> myHistory(String username) {
        List<ExportHistoryItem> out = new ArrayList<>();
        for (GlExportRequest r : requests.findByRequestedByOrderByCreatedAtDesc(username)) {
            ExportTransfer t = r.getTransferId() != null ? transfers.findById(r.getTransferId()).orElse(null) : null;
            out.add(new ExportHistoryItem(r, t));
        }
        return out;
    }

    /** How many active GL rows are exportable right now (for the maker button state). */
    public long exportableCount() {
        return gl.countByActiveAndStatusDualAndStatusPrintAndExportReqIdIsNull((short)1, "APPROVED", "BELUM UPLOAD");
    }

    /** Idem, hanya baris milik maker yang boleh dilihat user ini (BUG_AUSY_00138). */
    public long exportableCount(AusyUserDetails me) {
        Set<String> allowed = scope.visibleMakers(me);
        if (allowed == null) return exportableCount();
        return allowed.isEmpty() ? 0 : gl.countExportableForMakers(allowed);
    }

    /** Exportable rows grouped by paket, so the maker can pick which one(s) to submit. */
    public List<Object[]> exportablePakets() { return gl.exportablePakets(); }

    /** Idem, hanya batch milik maker yang boleh dilihat user ini — kolom ke-5 adalah gltrus. */
    public List<Object[]> exportablePakets(AusyUserDetails me) {
        return scope.filterArrays(me, gl.exportablePakets(), 4);
    }

    /**
     * Baris GL tambahan manual yang siap diexport, dipilih satu per satu — batch-nya bisa kosong
     * sehingga tidak pernah muncul di daftar batch (BUG_AUSY_00124), dan memang diminta tampil
     * terpisah supaya tidak tercampur dengan batch hasil upload (BUG_AUSY_00125).
     * Baris yang masih menunggu otorisasi perubahan tidak ikut (BUG_AUSY_00102).
     */
    public List<GlTabel> exportableManualRows() {
        Set<Long> pending = changes.pendingGlRowIds();
        List<GlTabel> out = new ArrayList<>();
        for (GlTabel g : gl.findExportableManual()) if (!pending.contains(g.getId())) out.add(g);
        return out;
    }

    /** Idem, hanya baris milik maker yang boleh dilihat user ini (BUG_AUSY_00138). */
    public List<GlTabel> exportableManualRows(AusyUserDetails me) {
        return scope.filterRows(me, exportableManualRows());
    }

    // ---------- Maker: Ajukan Export ----------
    @Transactional
    public String submitExport(AusyUserDetails me, String note, List<String> pakets, List<Long> glIds) {
        if (note == null || note.isBlank()) return "Alasan pengajuan export wajib diisi.";
        if (!approval.hasActiveApprover(me.getIdGroup())) return ApprovalService.NO_APPROVER;
        List<String> paket = clean(pakets);
        List<Long> ids = glIds == null ? List.of() : glIds.stream().filter(Objects::nonNull).toList();
        if (paket.isEmpty() && ids.isEmpty())
            return "Pilih minimal satu batch atau satu data GL untuk diajukan export.";
        // BUG_AUSY_00102: data yang masih menunggu otorisasi belum final — batch-nya tidak boleh diexport
        // dulu, kalau tidak file ACCTXT terkirim ke core dengan nilai yang mungkin masih berubah.
        List<String> held = pendingPakets(paket);
        if (!held.isEmpty())
            return "Batch " + String.join(", ", held) + " tidak dapat diajukan export: masih ada data yang"
                    + " menunggu otorisasi Checker. Selesaikan otorisasi tersebut lebih dulu.";
        Set<Long> pendingRows = changes.pendingGlRowIds();
        for (Long id : ids)
            if (pendingRows.contains(id))
                return "Data GL #" + id + " masih menunggu otorisasi Checker — selesaikan dulu sebelum diexport.";

        // Maker hanya boleh mengajukan barisnya sendiri, walau nomor batch / id barisnya
        // ditebak dan dikirim langsung ke endpoint ini (BUG_AUSY_00138). Diperiksa lewat daftar
        // uploader-nya saja, bukan dengan memuat semua barisnya (BUG_AUSY_00148).
        List<String> uploaders = new ArrayList<>();
        if (!paket.isEmpty()) uploaders.addAll(gl.exportableUploadersByPaket(paket));
        if (!ids.isEmpty()) uploaders.addAll(gl.exportableUploadersByIds(ids));
        for (String u : uploaders)
            if (!scope.canSee(me, u)) return DataScopeService.NOT_MY_MAKER;

        // Jumlah & total dihitung di database — pengajuan export tidak lagi tergantung
        // banyaknya baris (BUG_AUSY_00148).
        long count = 0; BigDecimal d = BigDecimal.ZERO, c = BigDecimal.ZERO;
        if (!paket.isEmpty()) {
            Object[] sum = unwrap(gl.exportableSummaryByPaket(paket));
            count += ((Number) sum[0]).longValue();
            d = d.add((BigDecimal) sum[1]); c = c.add((BigDecimal) sum[2]);
        }
        if (!ids.isEmpty()) {
            Object[] sum = unwrap(gl.exportableSummaryByIds(ids));
            count += ((Number) sum[0]).longValue();
            d = d.add((BigDecimal) sum[1]); c = c.add((BigDecimal) sum[2]);
        }
        if (count == 0) {
            if (pendingCount() > 0) return "Export sebelumnya sedang menunggu approval checker.";
            return "Tidak ada GL aktif yang belum di-export.";
        }
        int rowsCount = (int) count;
        GlExportRequest req = new GlExportRequest();
        req.setRequestedBy(me.getUsername()); req.setRequesterGroup(me.getIdGroup());
        req.setStatus("PENDING"); req.setRowsCount(rowsCount); req.setDebit(d); req.setCredit(c);
        req.setCreatedAt(LocalDateTime.now()); req.setNoteMaker(note);
        requests.save(req);
        if (!paket.isEmpty()) gl.markExportable(req.getId(), paket);
        if (!ids.isEmpty()) gl.markExportableByIds(req.getId(), ids);
        String what = (paket.isEmpty() ? "" : "batch " + String.join(",", paket))
                + (paket.isEmpty() || ids.isEmpty() ? "" : " + ")
                + (ids.isEmpty() ? "" : ids.size() + " data GL manual");
        audit.transaction(me.getUsername(), "EXPORT_REQ",
                "Ajukan export " + what + ": " + rowsCount + " baris (CR#" + req.getId() + ")");
        return "Permintaan export diajukan (" + rowsCount + " baris) — menunggu otorisasi checker.";
    }

    /** Buang nilai kosong dari pilihan batch — batch kosong tidak pernah bisa dicocokkan. */
    private static List<String> clean(List<String> v) {
        if (v == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : v) if (s != null && !s.isBlank() && !out.contains(s.trim())) out.add(s.trim());
        return out;
    }

    /** Which of the batches the maker picked still hold rows awaiting a checker (BUG_AUSY_00102). */
    public List<String> pendingPakets(List<String> pakets) {
        Set<String> pending = changes.pendingRowPakets();
        List<String> out = new ArrayList<>();
        if (pakets == null || pending.isEmpty()) return out;
        for (String p : pakets) {
            String key = p == null ? "" : p.trim();
            if (pending.contains(key) && !out.contains(key)) out.add(key);
        }
        Collections.sort(out);
        return out;
    }

    /** Batches with pending data, so the picklist can grey them out instead of failing on submit. */
    public Set<String> pendingPaketKeys() { return changes.pendingRowPakets(); }

    // ---------- Checker: Otorisasi Export ----------
    /** Antrian export milik checker ini — hanya maker yang dipasangkan padanya (BUG_AUSY_00139). */
    public List<GlExportRequest> pendingExportFor(AusyUserDetails me) {
        List<GlExportRequest> out = new ArrayList<>();
        for (GlExportRequest r : requests.findByStatusOrderByCreatedAtDesc("PENDING"))
            if (approval.canApproveGroup(me == null ? null : me.getIdGroup(), groupOf(r))
                    && scope.canSee(me, r.getRequestedBy())) out.add(r);
        return out;
    }

    /**
     * Hasil otorisasi export: pesan untuk ditampilkan + apakah semuanya benar-benar beres.
     *
     * Dulu pemanggilnya menebak lewat isi pesan ("Gagal kirim ke FTP"), jadi satu perubahan kata
     * saja sudah cukup membuat kegagalan pengiriman tampil sebagai notifikasi hijau.
     */
    public record ApproveResult(boolean ok, String message) {}

    @Transactional
    public ApproveResult approveExport(Long reqId, AusyUserDetails me) {
        String checker = me.getUsername();
        GlExportRequest req = requests.findById(reqId).orElse(null);
        if (req == null || !"PENDING".equals(req.getStatus())) return new ApproveResult(false, "Permintaan export tidak ditemukan / sudah diproses.");
        if (!approval.canApproveGroup(me.getIdGroup(), groupOf(req)))
            return new ApproveResult(false, "Group Anda tidak berwenang meng-approve export dari group tersebut.");
        if (!scope.canSee(me, req.getRequestedBy())) return new ApproveResult(false, DataScopeService.NOT_MY_MAKER);
        List<GlTabel> rows = gl.findByExportReqIdOrderById(reqId);
        if (rows.isEmpty()) return new ApproveResult(false, "Tidak ada baris GL untuk permintaan ini.");

        BigDecimal d = BigDecimal.ZERO, c = BigDecimal.ZERO;
        StringBuilder content = new StringBuilder();
        for (GlTabel g : rows) {
            content.append(GltrrfFormatter.line(g)).append(System.lineSeparator());
            if ("D".equalsIgnoreCase(g.getGltrcd())) d = d.add(nz(g.getGltram())); else c = c.add(nz(g.getGltram()));
        }
        if (d.compareTo(c) != 0) return new ApproveResult(false, "Total tidak balance (Debit != Credit). Otorisasi export dibatalkan.");

        String ftpNote;
        try {
            Path dir = exportDir();
            Files.createDirectories(dir);
            // Nama file AS400: ACCTXT.<YYMMDD> — SATU file per hari (BUG_AUSY_00142).
            // Export berikutnya di hari yang sama menimpa file hari itu, baik di disk maupun di FTP
            // (nama remote-nya sama), sehingga core hanya pernah menemukan hasil export terakhir.
            // Sebelumnya file kedua dibuat dengan akhiran "_<reqId>" sehingga menumpuk beberapa
            // file untuk satu tanggal dan tidak jelas mana yang berlaku.
            String yymmdd = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
            String fileName = "ACCTXT." + yymmdd;
            Path out = dir.resolve(fileName);
            boolean replaced = Files.exists(out);
            Files.writeString(out, content.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            if (replaced)
                audit.transaction(checker, "EXPORT_REPLACE",
                        "File " + fileName + " ditimpa oleh export CR#" + reqId + " (satu file per hari).");

            ExportTransfer t = new ExportTransfer();
            t.setFileName(fileName); t.setPaket("EXPORT#" + reqId); t.setInputer(req.getRequestedBy());
            t.setChecker(checker); t.setRowsCount(rows.size()); t.setCreatedAt(LocalDateTime.now());
            // Nama file selalu disebut, apa pun hasil FTP-nya (BUG_AUSY_00151). Path lengkap &
            // isi foldernya ada di halaman "Direktori Hasil Export" yang ditautkan di bawah pesan
            // ini — pesan untuk checker ditulis apa adanya, tanpa istilah teknis dan tanpa
            // pesan mentah dari library; detail sebabnya ada di beranda Admin & audit trail.
            boolean delivered = true;
            if (ftp.enabled()) {
                try {
                    String remote = ftp.push(out);
                    t.setStatus("SENT"); t.setRemotePath(remote); t.setSentAt(LocalDateTime.now()); t.setAttempts(1);
                    audit.transaction(checker, "FTP_SENT", "Export CR#" + reqId + " -> FTP: " + remote);
                    ftpNote = " File " + fileName + " sudah dibuat dan dikirim ke server FTP.";
                } catch (Exception fe) {
                    t.setStatus("FAILED"); t.setError(fe.getMessage()); t.setAttempts(1);
                    audit.transaction(checker, "FTP_FAILED", "Export CR#" + reqId + " gagal kirim FTP: " + fe.getMessage());
                    delivered = false;
                    ftpNote = " File " + fileName + " sudah dibuat di folder hasil export, tetapi gagal"
                            + " dikirim ke server FTP. Admin dapat mengirimnya ulang dari beranda Admin.";
                }
            } else {
                // Tanpa setelan FTP, file ACCTXT cukup ditulis ke direktori tujuan (root FTP lokal)
                // dan core mengambilnya dari sana — tidak ada lagi antrian kirim (BUG_AUSY_00146).
                t.setStatus("SENT"); t.setRemotePath(out.toString());
                t.setSentAt(LocalDateTime.now()); t.setAttempts(1);
                ftpNote = " File " + fileName + " sudah dibuat di folder hasil export.";
            }
            transfers.save(t);

            gl.markExported(reqId);
            req.setStatus("APPROVED"); req.setChecker(checker); req.setDecidedAt(LocalDateTime.now());
            req.setFileName(fileName); req.setTransferId(t.getId());
            requests.save(req);
            audit.transaction(checker, "EXPORT_APPROVE",
                    "Export CR#" + reqId + " (maker=" + req.getRequestedBy() + ") di-approve, " + rows.size() + " baris -> " + fileName);
            return new ApproveResult(delivered,
                    "Otorisasi export berhasil — " + rows.size() + " baris diproses." + ftpNote);
        } catch (Exception e) {
            return new ApproveResult(false, "Gagal menulis file ACCTXT: " + e.getMessage());
        }
    }

    @Transactional
    public String rejectExport(Long reqId, AusyUserDetails me, String note) {
        String checker = me.getUsername();
        GlExportRequest req = requests.findById(reqId).orElse(null);
        if (req == null || !"PENDING".equals(req.getStatus())) return "Permintaan export tidak ditemukan / sudah diproses.";
        if (!approval.canApproveGroup(me.getIdGroup(), groupOf(req)))
            return "Group Anda tidak berwenang menolak export dari group tersebut.";
        if (!scope.canSee(me, req.getRequestedBy())) return DataScopeService.NOT_MY_MAKER;
        if (note == null || note.isBlank()) return "Catatan penolakan wajib diisi.";
        gl.revertExport(reqId);
        req.setStatus("REJECTED"); req.setChecker(checker); req.setDecidedAt(LocalDateTime.now()); req.setNoteChecker(note);
        requests.save(req);
        audit.transaction(checker, "EXPORT_REJECT",
                "Export CR#" + reqId + " (maker=" + req.getRequestedBy() + ") DITOLAK. Catatan: " + note);
        return "Permintaan export ditolak. GL dikembalikan agar bisa diajukan lagi; alasan tercatat di audit trail.";
    }

    // ---------- Admin: resend a queued/failed transfer ----------
    /** Satu file di direktori export, untuk halaman "Direktori Hasil Export". */
    public record ExportFile(String name, long size, String sizeText, LocalDateTime modified) {
        static ExportFile of(String name, long size, LocalDateTime modified) {
            String t = size < 1024 ? size + " byte"
                     : size < 1024 * 1024 ? String.format("%.1f KB", size / 1024.0)
                     : String.format("%.1f MB", size / (1024.0 * 1024));
            return new ExportFile(name, size, t, modified);
        }
    }

    /** Keterangan direktori export + isinya — dipakai halaman /export-dir. */
    public record ExportDirView(String path, boolean exists, boolean writable, List<ExportFile> files) {}

    /**
     * Isi direktori export, terbaru dulu.
     *
     * Ada supaya pertanyaan "hasil export-nya ke mana?" bisa dijawab dari dalam aplikasi
     * (BUG_AUSY_00151): link file:// dari halaman http diblokir browser, jadi menunjukkan
     * daftarnya sendiri adalah satu-satunya cara yang benar-benar bisa dipencet.
     * File-nya tidak diunduh — ACCTXT memang tidak untuk turun ke komputer siapa pun.
     */
    public ExportDirView exportDirView() {
        Path dir = exportDir();
        List<ExportFile> files = new ArrayList<>();
        boolean exists = Files.isDirectory(dir);
        if (exists) {
            try (var paths = Files.list(dir)) {
                for (Path f : paths.filter(Files::isRegularFile).toList()) {
                    LocalDateTime at = LocalDateTime.ofInstant(
                            Files.getLastModifiedTime(f).toInstant(), java.time.ZoneId.systemDefault());
                    files.add(ExportFile.of(f.getFileName().toString(), Files.size(f), at));
                }
            } catch (Exception e) {
                LOG.warn("Direktori export {} tidak bisa dibaca: {}", dir, e.getMessage());
            }
            files.sort(Comparator.comparing(ExportFile::modified).reversed());
        }
        return new ExportDirView(dir.toString(), exists, exists && Files.isWritable(dir), files);
    }

    /** Path direktori export apa adanya — ditempel di halaman otorisasi & beranda Admin. */
    public String exportDirPath() { return exportDir().toString(); }

    /** Resolve a generated text file inside the txt dir (basename only — no path traversal). */
    public Path txtFile(String name) {
        String safe = Paths.get(name == null ? "" : name).getFileName().toString();
        return exportDir().resolve(safe);   // file ditulis di direktori export
    }

    @Transactional
    public String resend(Long transferId, String admin) {
        ExportTransfer t = transfers.findById(transferId).orElse(null);
        if (t == null) return "Data transfer tidak ditemukan.";
        if ("SENT".equals(t.getStatus())) return "File sudah terkirim sebelumnya.";
        Path f = txtFile(t.getFileName());
        t.setAttempts((t.getAttempts()==null?0:t.getAttempts()) + 1);
        if (!Files.exists(f)) {
            t.setStatus("FAILED"); t.setError("File lokal tidak ditemukan: " + t.getFileName());
            transfers.save(t);
            return "Gagal: file lokal '" + t.getFileName() + "' tidak ditemukan.";
        }
        // Push FTP mati: file di direktori export ITU pengirimannya (BUG_AUSY_00146) — persis seperti
        // export baru yang langsung ditandai SENT. Sebelumnya tombol Kirim Ulang hanya menolak, jadi
        // transfer yang gagal sewaktu FTP masih menyala tidak pernah bisa diselesaikan: barisnya
        // menetap FAILED di card beranda Admin (dan di peringatan maker) tanpa satu pun jalan keluar.
        if (!ftp.enabled()) {
            String at = f.toAbsolutePath().toString();
            t.setStatus("SENT"); t.setRemotePath(at); t.setSentAt(LocalDateTime.now()); t.setError(null);
            transfers.save(t);
            audit.transaction(admin, "FTP_SENT", "Resend " + t.getFileName() + ": pengiriman FTP tidak aktif ("
                    + ftp.sourceLabel() + "), file diserahkan lewat folder hasil export " + at);
            return "Berhasil: pengiriman ke server FTP tidak aktif, jadi file " + t.getFileName()
                    + " diambil core langsung dari folder hasil export. Transfer ditandai selesai.";
        }
        try {
            String remote = ftp.push(f);
            t.setStatus("SENT"); t.setRemotePath(remote); t.setSentAt(LocalDateTime.now()); t.setError(null);
            transfers.save(t);
            audit.transaction(admin, "FTP_SENT", "Resend " + t.getFileName() + " -> FTP: " + remote);
            return "Berhasil dikirim ke server FTP: " + remote + ".";
        } catch (Exception fe) {
            t.setStatus("FAILED"); t.setError(fe.getMessage());
            transfers.save(t);
            audit.transaction(admin, "FTP_FAILED", "Resend " + t.getFileName() + " gagal: " + fe.getMessage());
            return "Gagal dikirim ke server FTP: " + fe.getMessage();
        }
    }
}
