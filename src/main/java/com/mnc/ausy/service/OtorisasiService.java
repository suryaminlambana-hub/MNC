package com.mnc.ausy.service;

import com.mnc.ausy.domain.GlTabel;
import com.mnc.ausy.repo.GlTabelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Otorisasi UPLOAD GL: checker approves an uploaded paket → GL becomes active.
 * NOTE: approving an upload NO LONGER generates ACCTXT / sends to FTP — that now lives in the
 * separate maker-initiated export flow (GlExportService), authorized in "Otorisasi Export GL".
 */
@Service
public class OtorisasiService {
    private final GlTabelRepository gl;
    private final ApprovalService approval;
    private final AuditService audit;

    public OtorisasiService(GlTabelRepository gl, ApprovalService approval, AuditService audit) {
        this.gl = gl; this.approval = approval; this.audit = audit;
    }

    public record Paket(String paket, String inputer, long jml, BigDecimal debit, BigDecimal credit, boolean balanced,
                        LocalDateTime uploadedAt) {}

    /** Pending pakets this approver-group is allowed to authorize. */
    public List<Paket> pendingFor(Integer approverGroup) {
        List<Paket> out = new ArrayList<>();
        for (Object[] r : gl.pendingPakets()) {
            String paket = (String) r[0];
            String inputer = (String) r[1];
            if (!approval.canApprove(approverGroup, inputer)) continue;
            long jml = ((Number) r[2]).longValue();
            BigDecimal d = (BigDecimal) r[3], c = (BigDecimal) r[4];
            out.add(new Paket(paket, inputer, jml, d, c, d.compareTo(c) == 0, toLocalDateTime(r[5])));
        }
        return out;
    }

    /** Native queries may hand back max(adddt) as java.sql.Timestamp or java.time.LocalDateTime
     *  depending on driver/Hibernate version — accept either. */
    private static LocalDateTime toLocalDateTime(Object o) {
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        if (o instanceof LocalDateTime ldt) return ldt;
        return null;
    }

    public record ExportResult(boolean ok, String message, int rows, String file) {}

    /** Otorisasi UPLOAD: approve a pending paket → mark active (status_dual=APPROVED). No export/FTP here. */
    @Transactional
    public ExportResult export(String paket, String inputer, Integer approverGroup, String checker) {
        if (!approval.canApprove(approverGroup, inputer))
            return new ExportResult(false, "Group Anda tidak berwenang meng-approve paket dari group tersebut.", 0, null);

        List<GlTabel> rows = gl.findByNoPaketAndGltrusOrderById(paket, inputer);
        if (rows.isEmpty()) return new ExportResult(false, "Paket tidak ditemukan.", 0, null);

        BigDecimal d = BigDecimal.ZERO, c = BigDecimal.ZERO;
        for (GlTabel g : rows) {
            if ("D".equalsIgnoreCase(g.getGltrcd())) d = d.add(nz(g.getGltram())); else c = c.add(nz(g.getGltram()));
        }
        if (d.compareTo(c) != 0)
            return new ExportResult(false, "Paket tidak balance (Debit != Credit). Otorisasi dibatalkan.", 0, null);

        // Tandai lolos otorisasi upload → jadi GL Aktif. status_print tetap 'BELUM UPLOAD'
        // (artinya: belum di-export ke core). Export ke FTP dilakukan lewat flow export maker.
        for (GlTabel g : rows) {
            g.setStatusDual("APPROVED");
            g.setStatusPrint("BELUM UPLOAD");
        }
        gl.saveAll(rows);
        audit.transaction(checker, "OTORISASI_APPROVE",
                "Paket " + paket + " (maker=" + inputer + ") di-approve, " + rows.size() + " baris jadi GL Aktif.");
        return new ExportResult(true, "Otorisasi upload berhasil — Paket " + paket + " kini menjadi GL Aktif ("
                + rows.size() + " baris). Export ke core dilakukan lewat menu Export oleh maker.", rows.size(), null);
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
