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

    @Value("${ausy.txt-dir:data/txt}") private String txtDir;

    public GlExportService(GlTabelRepository gl, GlExportRequestRepository requests, ExportTransferRepository transfers,
                           ApprovalService approval, AuditService audit, FtpService ftp) {
        this.gl = gl; this.requests = requests; this.transfers = transfers;
        this.approval = approval; this.audit = audit; this.ftp = ftp;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    public long pendingCount() { return requests.countByStatus("PENDING"); }

    /** How many active GL rows are exportable right now (for the maker button state). */
    public long exportableCount() {
        return gl.countByActiveAndStatusDualAndStatusPrintAndExportReqIdIsNull((short)1, "APPROVED", "BELUM UPLOAD");
    }

    // ---------- Maker: Ajukan Export ----------
    @Transactional
    public String submitExport(AusyUserDetails me) {
        List<GlTabel> rows = gl.findExportable();
        if (rows.isEmpty()) {
            if (pendingCount() > 0) return "Export sebelumnya sedang menunggu approval checker.";
            return "Tidak ada GL aktif yang belum di-export.";
        }
        BigDecimal d = BigDecimal.ZERO, c = BigDecimal.ZERO;
        for (GlTabel g : rows) {
            if ("D".equalsIgnoreCase(g.getGltrcd())) d = d.add(nz(g.getGltram())); else c = c.add(nz(g.getGltram()));
        }
        GlExportRequest req = new GlExportRequest();
        req.setRequestedBy(me.getUsername()); req.setRequesterGroup(me.getIdGroup());
        req.setStatus("PENDING"); req.setRowsCount(rows.size()); req.setDebit(d); req.setCredit(c);
        req.setCreatedAt(LocalDateTime.now());
        requests.save(req);
        gl.markExportable(req.getId());
        audit.transaction(me.getUsername(), "EXPORT_REQ",
                "Ajukan export GL aktif: " + rows.size() + " baris (CR#" + req.getId() + ")");
        return "Permintaan export diajukan (" + rows.size() + " baris) — menunggu otorisasi checker.";
    }

    // ---------- Checker: Otorisasi Export ----------
    public List<GlExportRequest> pendingExportFor(Integer approverGroup) {
        List<GlExportRequest> out = new ArrayList<>();
        for (GlExportRequest r : requests.findByStatusOrderByCreatedAtDesc("PENDING"))
            if (approval.canApprove(approverGroup, r.getRequestedBy())) out.add(r);
        return out;
    }

    @Transactional
    public String approveExport(Long reqId, Integer approverGroup, String checker) {
        GlExportRequest req = requests.findById(reqId).orElse(null);
        if (req == null || !"PENDING".equals(req.getStatus())) return "Permintaan export tidak ditemukan / sudah diproses.";
        if (!approval.canApprove(approverGroup, req.getRequestedBy()))
            return "Group Anda tidak berwenang meng-approve export dari group tersebut.";
        List<GlTabel> rows = gl.findByExportReqIdOrderById(reqId);
        if (rows.isEmpty()) return "Tidak ada baris GL untuk permintaan ini.";

        BigDecimal d = BigDecimal.ZERO, c = BigDecimal.ZERO;
        StringBuilder content = new StringBuilder();
        for (GlTabel g : rows) {
            content.append(GltrrfFormatter.line(g)).append(System.lineSeparator());
            if ("D".equalsIgnoreCase(g.getGltrcd())) d = d.add(nz(g.getGltram())); else c = c.add(nz(g.getGltram()));
        }
        if (d.compareTo(c) != 0) return "Total tidak balance (Debit != Credit). Otorisasi export dibatalkan.";

        String ftpNote;
        try {
            Path dir = Paths.get(txtDir);
            Files.createDirectories(dir);
            // Nama file AS400: ACCTXT.<YYMMDD>. Hindari overwrite bila ada export lain di hari yang sama.
            String yymmdd = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
            String fileName = "ACCTXT." + yymmdd;
            Path out = dir.resolve(fileName);
            if (Files.exists(out)) { fileName = "ACCTXT." + yymmdd + "_" + reqId; out = dir.resolve(fileName); }
            Files.writeString(out, content.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            ExportTransfer t = new ExportTransfer();
            t.setFileName(fileName); t.setPaket("EXPORT#" + reqId); t.setInputer(req.getRequestedBy());
            t.setChecker(checker); t.setRowsCount(rows.size()); t.setCreatedAt(LocalDateTime.now());
            if (ftp.enabled()) {
                try {
                    String remote = ftp.push(out);
                    t.setStatus("SENT"); t.setRemotePath(remote); t.setSentAt(LocalDateTime.now()); t.setAttempts(1);
                    audit.transaction(checker, "FTP_SENT", "Export CR#" + reqId + " -> FTP: " + remote);
                    ftpNote = " Terkirim ke FTP: " + remote + ".";
                } catch (Exception fe) {
                    t.setStatus("FAILED"); t.setError(fe.getMessage()); t.setAttempts(1);
                    audit.transaction(checker, "FTP_FAILED", "Export CR#" + reqId + " gagal kirim FTP: " + fe.getMessage());
                    ftpNote = " (Gagal kirim ke FTP — masuk antrian; Admin dapat kirim ulang dari Konfigurasi FTP.)";
                }
            } else {
                t.setStatus("PENDING"); t.setAttempts(0);
                ftpNote = " (FTP belum dikonfigurasi/aktif — file masuk antrian; Admin dapat mengirim setelah konfigurasi.)";
            }
            transfers.save(t);

            gl.markExported(reqId);
            req.setStatus("APPROVED"); req.setChecker(checker); req.setDecidedAt(LocalDateTime.now());
            req.setFileName(fileName); req.setTransferId(t.getId());
            requests.save(req);
            audit.transaction(checker, "EXPORT_APPROVE",
                    "Export CR#" + reqId + " (maker=" + req.getRequestedBy() + ") di-approve, " + rows.size() + " baris -> " + fileName);
            return "Otorisasi export berhasil — " + rows.size() + " baris diproses." + ftpNote;
        } catch (Exception e) {
            return "Gagal menulis file ACCTXT: " + e.getMessage();
        }
    }

    @Transactional
    public String rejectExport(Long reqId, Integer approverGroup, String checker, String note) {
        GlExportRequest req = requests.findById(reqId).orElse(null);
        if (req == null || !"PENDING".equals(req.getStatus())) return "Permintaan export tidak ditemukan / sudah diproses.";
        if (!approval.canApprove(approverGroup, req.getRequestedBy()))
            return "Group Anda tidak berwenang menolak export dari group tersebut.";
        if (note == null || note.isBlank()) return "Catatan penolakan wajib diisi.";
        gl.revertExport(reqId);
        req.setStatus("REJECTED"); req.setChecker(checker); req.setDecidedAt(LocalDateTime.now()); req.setNoteChecker(note);
        requests.save(req);
        audit.transaction(checker, "EXPORT_REJECT",
                "Export CR#" + reqId + " (maker=" + req.getRequestedBy() + ") DITOLAK. Catatan: " + note);
        return "Permintaan export ditolak. GL dikembalikan agar bisa diajukan lagi; alasan tercatat di audit trail.";
    }

    // ---------- Admin: resend a queued/failed transfer ----------
    /** Resolve a generated text file inside the txt dir (basename only — no path traversal). */
    public Path txtFile(String name) {
        String safe = Paths.get(name == null ? "" : name).getFileName().toString();
        return Paths.get(txtDir).resolve(safe);
    }

    @Transactional
    public String resend(Long transferId, String admin) {
        ExportTransfer t = transfers.findById(transferId).orElse(null);
        if (t == null) return "Data transfer tidak ditemukan.";
        if ("SENT".equals(t.getStatus())) return "File sudah terkirim sebelumnya.";
        if (!ftp.enabled()) return "FTP belum dikonfigurasi/aktif. Isi & aktifkan konfigurasi FTP dulu.";
        Path f = txtFile(t.getFileName());
        t.setAttempts((t.getAttempts()==null?0:t.getAttempts()) + 1);
        if (!Files.exists(f)) {
            t.setStatus("FAILED"); t.setError("File lokal tidak ditemukan: " + t.getFileName());
            transfers.save(t);
            return "Gagal: file lokal '" + t.getFileName() + "' tidak ditemukan.";
        }
        try {
            String remote = ftp.push(f);
            t.setStatus("SENT"); t.setRemotePath(remote); t.setSentAt(LocalDateTime.now()); t.setError(null);
            transfers.save(t);
            audit.transaction(admin, "FTP_SENT", "Resend " + t.getFileName() + " -> FTP: " + remote);
            return "Berhasil dikirim ke FTP: " + remote + ".";
        } catch (Exception fe) {
            t.setStatus("FAILED"); t.setError(fe.getMessage());
            transfers.save(t);
            audit.transaction(admin, "FTP_FAILED", "Resend " + t.getFileName() + " gagal: " + fe.getMessage());
            return "Gagal kirim ke FTP: " + fe.getMessage();
        }
    }
}
