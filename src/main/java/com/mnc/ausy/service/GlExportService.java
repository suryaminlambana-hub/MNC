package com.mnc.ausy.service;

import com.mnc.ausy.domain.ExportTransfer;
import com.mnc.ausy.domain.GlExportRequest;
import com.mnc.ausy.domain.GlTabel;
import com.mnc.ausy.repo.ExportTransferRepository;
import com.mnc.ausy.repo.GlExportRequestRepository;
import com.mnc.ausy.repo.GlTabelRepository;
import com.mnc.ausy.security.AusyUserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Export flow (separated from otorisasi upload):
 *   Maker "Ajukan Export" (List GL Aktif) → checker "Otorisasi Export GL" approve → file ACCTXT
 *   ditulis ke folder hasil export di server, dan core banking mengambilnya dari sana.
 * File ACCTXT tidak pernah diunduh ke komputer.
 */
@Service
public class GlExportService {
    private final GlTabelRepository gl;
    private final GlExportRequestRepository requests;
    private final ExportTransferRepository transfers;
    private final ApprovalService approval;
    private final AuditService audit;
    private final GlChangeService changes;
    private final DataScopeService scope;

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(GlExportService.class);
    /** Semua penulisan & pembacaan file hasil export lewat sini. */
    private final ExportFileStore files;
    private final BatchStatusService batchStatus;

    public GlExportService(GlTabelRepository gl, GlExportRequestRepository requests, ExportTransferRepository transfers,
                           ApprovalService approval, AuditService audit, GlChangeService changes,
                           DataScopeService scope, ExportFileStore files, BatchStatusService batchStatus) {
        this.gl = gl; this.requests = requests; this.transfers = transfers;
        this.approval = approval; this.audit = audit; this.changes = changes; this.scope = scope;
        this.files = files; this.batchStatus = batchStatus;
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

    /** One export request + the file it produced (if it got that far) — for the maker's "Riwayat Export". */
    public record ExportHistoryItem(GlExportRequest req, ExportTransfer transfer) {}

    /** Maker's full export history — pending, approved, and rejected — with the file produced where applicable. */
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
     * Dulu pemanggilnya menebak berhasil/gagal dari isi pesan, jadi satu perubahan kata saja
     * sudah cukup membuat kegagalan tampil sebagai notifikasi hijau.
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

        // BUG_AUSY_00156: otorisasi berhenti di sini — approve hanya menandai data sebagai
        // disetujui, TIDAK lagi membuat file ACCTXT. Pembuatan filenya satu pintu lewat menu
        // admin "Generate File ACCTXT" (BUG_AUSY_00155/00158), yang menggabungkan seluruh
        // export approved dalam rentang tanggal jadi SATU file. Dulu tiap approve menulis
        // filenya sendiri, sehingga approve kedua di hari yang sama menimpa hasil approve
        // pertama dan isinya tinggal sebagian.
        //
        // Barisnya sengaja dibiarkan PROSES EXPORT dengan export_req_id tetap terpasang: sudah
        // keluar dari kumpulan "boleh diajukan", tapi belum SUDAH UPLOAD karena memang belum
        // ada file yang terbit. markExported() dipanggil saat generate.
        req.setStatus("APPROVED"); req.setChecker(checker); req.setDecidedAt(LocalDateTime.now());
        requests.save(req);
        audit.transaction(checker, "EXPORT_APPROVE",
                "Export CR#" + reqId + " (maker=" + req.getRequestedBy() + ") di-approve, "
                + rows.size() + " baris — menunggu generate file ACCTXT oleh admin.");
        return new ApproveResult(true, "Otorisasi export berhasil — " + rows.size()
                + " baris disetujui. File ACCTXT dibuat lewat menu Generate File ACCTXT (admin).");
    }

    /**
     * Tarik kembali export yang sudah di-approve (BUG_AUSY_00157).
     *
     * Hanya selama file ACCTXT-nya belum terbit. Begitu digenerate, berkasnya sudah berada di
     * folder yang dibaca core banking dan mungkin sudah diambil — menarik kembali di layar
     * setelah itu cuma membuat catatan aplikasi berbeda dari apa yang diterima core. Untuk
     * kasus itu jalurnya koreksi jurnal, bukan return.
     */
    /** Export approved yang filenya belum terbit — satu-satunya yang masih bisa ditarik. */
    public List<GlExportRequest> returnable() { return requests.findApprovedNotGenerated(); }

    /**
     * Idem, dibatasi yang benar-benar boleh ditarik checker ini (BUG_AUSY_00176).
     *
     * Daftarnya dulu tidak disaring sama sekali, padahal {@link #returnExport} menolak export
     * dari maker yang bukan pasangan otorisasi si checker. Akibatnya layar memperlihatkan
     * pengajuan maker group lain lengkap dengan tombol Return yang tidak akan pernah berhasil —
     * sekaligus membocorkan data maker yang bukan wewenangnya.
     *
     * Saringannya memakai dua syarat yang sama persis dengan aksinya: group pengaju harus boleh
     * disetujui group ini, dan makernya harus pasangan otorisasi checker ini. Satu aturan di
     * dua tempat selalu berakhir beda; di sini sengaja dipanggil pemeriksaan yang sama.
     */
    public List<GlExportRequest> returnable(AusyUserDetails me) {
        List<GlExportRequest> sesuaiGroup = new ArrayList<>();
        for (GlExportRequest r : returnable())
            if (approval.canApproveGroup(me.getIdGroup(), groupOf(r))) sesuaiGroup.add(r);
        return scope.filterBy(me, sesuaiGroup, GlExportRequest::getRequestedBy);
    }

    @Transactional
    public String returnExport(Long reqId, AusyUserDetails me, String note) {
        String checker = me.getUsername();
        GlExportRequest req = requests.findById(reqId).orElse(null);
        if (req == null || !"APPROVED".equals(req.getStatus()))
            return "Permintaan export tidak ditemukan / statusnya bukan approved.";
        if (req.getFileName() != null)
            return "Export ini sudah masuk file " + req.getFileName()
                    + " dan tidak bisa ditarik kembali. Perbaikan lewat jurnal koreksi.";
        if (!approval.canApproveGroup(me.getIdGroup(), groupOf(req)))
            return "Group Anda tidak berwenang menarik kembali export dari group tersebut.";
        if (!scope.canSee(me, req.getRequestedBy())) return DataScopeService.NOT_MY_MAKER;
        if (note == null || note.isBlank()) return "Alasan penarikan wajib diisi.";

        // REQ_AUSY_00042 & 00045: data dikembalikan ke halaman ASALNYA untuk diproses ulang.
        //   - baris hasil upload (glsrc 'p') → status PENDING lagi: batch-nya muncul kembali di
        //     List Data GL maker dan di antrian otorisasi upload checker;
        //   - baris manual Kelola Data GL (glsrc 'm') → status RETURNED dan langsung diajukan ulang
        //     (REQ_AUSY_00046 feedback): muncul di tabel Pengajuan Kelola Data GL maker — bisa diubah
        //     atau dihapus selama menunggu — dan di antrian Otorisasi Kelola Data GL checker.
        // Asalnya dibaca dari kolom glsrc, yang memang menyimpan dari mana baris itu dibuat.
        List<GlTabel> rows = gl.findByExportReqIdOrderById(reqId);
        Set<String> batchUpload = new TreeSet<>(), kunciBatch = new HashSet<>();
        List<GlTabel> barisManual = new ArrayList<>();
        for (GlTabel g : rows) {
            boolean dariManual = "m".equals(g.getGlsrc());
            g.setExportReqId(null);
            g.setStatusPrint("BELUM UPLOAD");
            g.setStatusDual(dariManual ? "RETURNED" : "PENDING");
            if (dariManual) { barisManual.add(g); continue; }
            batchUpload.add(String.valueOf(g.getNoPaket()));
            // REQ_AUSY_00053: batch ini tampil berstatus Dikembalikan di List Data GL maker.
            if (kunciBatch.add(g.getNoPaket() + "|" + g.getGltrus()))
                batchStatus.returned(g.getNoPaket(), g.getGltrus(), checker, note);
        }
        gl.saveAll(rows);
        for (GlTabel g : barisManual) changes.returnToPengajuan(g, checker, note);
        int manual = barisManual.size();
        req.setStatus("RETURNED"); req.setChecker(checker);
        req.setDecidedAt(LocalDateTime.now()); req.setNoteChecker(note);
        requests.save(req);

        String tujuan = (batchUpload.isEmpty() ? "" : "Batch " + batchUpload.stream().map(BatchLabel::label).collect(java.util.stream.Collectors.joining(", "))
                        + " kembali ke List Data GL maker dan antrian otorisasi upload")
                + (!batchUpload.isEmpty() && manual > 0 ? "; " : "")
                + (manual == 0 ? "" : manual + " data manual kembali ke tabel Pengajuan Kelola Data GL maker dan antrian otorisasi Kelola Data GL");
        audit.transaction(checker, "EXPORT_RETURN",
                "Data penampungan CR#" + reqId + " (maker=" + req.getRequestedBy() + ", " + rows.size()
                + " baris) DIKEMBALIKAN sebelum file dibuat: " + tujuan + ". Alasan: " + note);
        return "Data dikembalikan: " + tujuan + ". Alasan tercatat di audit trail.";
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

    /** Lokasi satu file hasil export (nama saja — tidak bisa menunjuk ke luar folder). */
    public Path txtFile(String name) { return files.resolve(name); }
}
