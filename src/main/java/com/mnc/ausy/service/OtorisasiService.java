package com.mnc.ausy.service;

import com.mnc.ausy.domain.GlTabel;
import com.mnc.ausy.repo.GlTabelRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class OtorisasiService {
    private final GlTabelRepository gl;
    private final ApprovalService approval;
    private final AuditService audit;

    @Value("${ausy.txt-dir:data/txt}") private String txtDir;
    @Value("${ausy.ftp.host:}")        private String ftpHost;   // empty -> FTP skipped

    public OtorisasiService(GlTabelRepository gl, ApprovalService approval, AuditService audit) {
        this.gl = gl; this.approval = approval; this.audit = audit;
    }

    public record Paket(String paket, String inputer, long jml, BigDecimal debit, BigDecimal credit, boolean balanced) {}

    /** Pending pakets this approver-group is allowed to authorize. */
    public List<Paket> pendingFor(Integer approverGroup) {
        List<Paket> out = new ArrayList<>();
        for (Object[] r : gl.pendingPakets()) {
            String paket = (String) r[0];
            String inputer = (String) r[1];
            if (!approval.canApprove(approverGroup, inputer)) continue;
            long jml = ((Number) r[2]).longValue();
            BigDecimal d = (BigDecimal) r[3], c = (BigDecimal) r[4];
            out.add(new Paket(paket, inputer, jml, d, c, d.compareTo(c) == 0));
        }
        return out;
    }

    public record ExportResult(boolean ok, String message, int rows, String file) {}

    @Transactional
    public ExportResult export(String paket, String inputer, Integer approverGroup, String checker) {
        if (!approval.canApprove(approverGroup, inputer))
            return new ExportResult(false, "Group Anda tidak berwenang meng-approve paket dari group tersebut.", 0, null);

        List<GlTabel> rows = gl.findByNoPaketAndGltrusOrderById(paket, inputer);
        if (rows.isEmpty()) return new ExportResult(false, "Paket tidak ditemukan.", 0, null);

        BigDecimal d = BigDecimal.ZERO, c = BigDecimal.ZERO;
        StringBuilder content = new StringBuilder();
        for (GlTabel g : rows) {
            content.append(GltrrfFormatter.line(g)).append(System.lineSeparator());
            if ("D".equalsIgnoreCase(g.getGltrcd())) d = d.add(nz(g.getGltram())); else c = c.add(nz(g.getGltram()));
        }
        if (d.compareTo(c) != 0)
            return new ExportResult(false, "Paket tidak balance (Debit != Credit). Otorisasi dibatalkan.", 0, null);

        try {
            Path dir = Paths.get(txtDir);
            Files.createDirectories(dir);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            String yymmdd = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
            Path pkt = dir.resolve("ACCTXT_" + ts + "_" + paket + ".txt");
            Files.writeString(pkt, content.toString(), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("ACCTXT." + yymmdd), content.toString(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            // FTP push only when configured (skipped in dev — file still generated)
            if (ftpHost != null && !ftpHost.isBlank()) { /* FtpService.push(...) — wired when configured */ }

            gl.deletePaket(paket, inputer);   // transaksi done -> migrate out of gl_tabel
            audit.transaction(checker, "OTORISASI_APPROVE",
                    "Paket " + paket + " (maker=" + inputer + ") di-approve & export, " + rows.size() + " baris -> " + pkt.getFileName());
            return new ExportResult(true, "Otorisasi berhasil — Paket " + paket + " diproses (" + rows.size() + " baris). File: " + pkt.getFileName(), rows.size(), pkt.getFileName().toString());
        } catch (Exception e) {
            return new ExportResult(false, "Gagal menulis file teks: " + e.getMessage(), 0, null);
        }
    }

    /** Reject a pending paket (Workflow 8.1.e / 8.2.d): data removed from temp, decision note recorded. */
    @Transactional
    public ExportResult reject(String paket, String inputer, Integer approverGroup, String checker, String note) {
        if (!approval.canApprove(approverGroup, inputer))
            return new ExportResult(false, "Group Anda tidak berwenang menolak paket dari group tersebut.", 0, null);
        if (note == null || note.isBlank())
            return new ExportResult(false, "Catatan penolakan wajib diisi.", 0, null);

        List<GlTabel> rows = gl.findByNoPaketAndGltrusOrderById(paket, inputer);
        if (rows.isEmpty()) return new ExportResult(false, "Paket tidak ditemukan.", 0, null);

        gl.deletePaket(paket, inputer);   // reject -> removed from temp table (gl_tabel)
        audit.transaction(checker, "OTORISASI_REJECT",
                "Paket " + paket + " (maker=" + inputer + ") DITOLAK, " + rows.size() + " baris dibuang. Catatan: " + note);
        return new ExportResult(true, "Paket " + paket + " ditolak. Data dihapus dari antrian dan alasan tercatat di audit trail.", rows.size(), null);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
