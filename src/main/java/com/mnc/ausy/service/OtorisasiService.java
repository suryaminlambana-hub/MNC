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
    private final FtpService ftp;

    @Value("${ausy.txt-dir:data/txt}") private String txtDir;

    public OtorisasiService(GlTabelRepository gl, ApprovalService approval, AuditService audit, FtpService ftp) {
        this.gl = gl; this.approval = approval; this.audit = audit; this.ftp = ftp;
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

    /** Resolve a generated text file inside the txt dir (basename only — no path traversal). */
    public Path txtFile(String name) {
        String safe = Paths.get(name == null ? "" : name).getFileName().toString();
        return Paths.get(txtDir).resolve(safe);
    }

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
            // Nama file sesuai contoh AS400: ACCTXT.<YYMMDD> — tanggal di extension, TANPA jam & TANPA .txt.
            String yymmdd = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
            String fileName = "ACCTXT." + yymmdd;
            Path out = dir.resolve(fileName);
            Files.writeString(out, content.toString(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            // Transfer Out to GL FTP server (Data Flow §9.g) — only when ausy.ftp.host is set
            String ftpNote = "";
            if (ftp.enabled()) {
                try {
                    String remote = ftp.push(out);
                    audit.transaction(checker, "FTP_SENT", "Paket " + paket + " -> FTP: " + remote);
                    ftpNote = " Terkirim ke FTP: " + remote + ".";
                } catch (Exception fe) {
                    audit.transaction(checker, "FTP_FAILED", "Paket " + paket + " gagal kirim FTP: " + fe.getMessage());
                    ftpNote = " (PERINGATAN: gagal kirim ke FTP — " + fe.getMessage() + "; file tetap tersimpan lokal.)";
                }
            }

            // Approve: JANGAN hapus dari gl_tabel — tandai sudah diotorisasi & ter-export supaya
            // datanya tetap tampil sebagai "GL Aktif" di sisi Maker (bukan hilang setelah approve).
            for (GlTabel g : rows) {
                g.setStatusDual("APPROVED");
                g.setStatusPrint("SUDAH UPLOAD");
            }
            gl.saveAll(rows);
            audit.transaction(checker, "OTORISASI_APPROVE",
                    "Paket " + paket + " (maker=" + inputer + ") di-approve & export, " + rows.size() + " baris -> " + fileName);
            return new ExportResult(true, "Otorisasi berhasil — Paket " + paket + " diproses (" + rows.size() + " baris). File: " + fileName + "." + ftpNote, rows.size(), fileName);
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
