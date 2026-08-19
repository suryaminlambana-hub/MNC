package com.mnc.ausy.service;

import com.mnc.ausy.domain.ExportTransfer;
import com.mnc.ausy.domain.GlTabel;
import com.mnc.ausy.repo.ExportTransferRepository;
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
    private final ExportTransferRepository transfers;

    @Value("${ausy.txt-dir:data/txt}") private String txtDir;

    public OtorisasiService(GlTabelRepository gl, ApprovalService approval, AuditService audit,
                            FtpService ftp, ExportTransferRepository transfers) {
        this.gl = gl; this.approval = approval; this.audit = audit; this.ftp = ftp; this.transfers = transfers;
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
            LocalDateTime uploadedAt = (r[5] instanceof java.sql.Timestamp ts) ? ts.toLocalDateTime() : null;
            out.add(new Paket(paket, inputer, jml, d, c, d.compareTo(c) == 0, uploadedAt));
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

            // Transfer-out ke GL FTP server (Data Flow §9.g). Setelah approve, data TIDAK diunduh ke
            // komputer maker/checker — hanya dikirim ke FTP untuk diproses core. File disimpan server-side
            // agar bisa dikirim ulang (Resend) oleh Admin bila FTP belum aktif / gagal.
            ExportTransfer t = new ExportTransfer();
            t.setFileName(fileName); t.setPaket(paket); t.setInputer(inputer);
            t.setChecker(checker); t.setRowsCount(rows.size()); t.setCreatedAt(LocalDateTime.now());
            String ftpNote;
            if (ftp.enabled()) {
                try {
                    String remote = ftp.push(out);
                    t.setStatus("SENT"); t.setRemotePath(remote); t.setSentAt(LocalDateTime.now()); t.setAttempts(1);
                    audit.transaction(checker, "FTP_SENT", "Paket " + paket + " -> FTP: " + remote);
                    ftpNote = " Terkirim ke FTP: " + remote + ".";
                } catch (Exception fe) {
                    t.setStatus("FAILED"); t.setError(fe.getMessage()); t.setAttempts(1);
                    audit.transaction(checker, "FTP_FAILED", "Paket " + paket + " gagal kirim FTP: " + fe.getMessage());
                    ftpNote = " (Gagal kirim ke FTP — masuk antrian; Admin dapat kirim ulang dari Konfigurasi FTP.)";
                }
            } else {
                t.setStatus("PENDING"); t.setAttempts(0);
                ftpNote = " (FTP belum dikonfigurasi/aktif — file masuk antrian; Admin dapat mengirim setelah konfigurasi.)";
            }
            transfers.save(t);

            // Approve: JANGAN hapus dari gl_tabel — tandai sudah diotorisasi & ter-export supaya
            // datanya tetap tampil sebagai "GL Aktif" di sisi Maker (bukan hilang setelah approve).
            for (GlTabel g : rows) {
                g.setStatusDual("APPROVED");
                g.setStatusPrint("SUDAH UPLOAD");
            }
            gl.saveAll(rows);
            audit.transaction(checker, "OTORISASI_APPROVE",
                    "Paket " + paket + " (maker=" + inputer + ") di-approve & export, " + rows.size() + " baris -> " + fileName);
            // file() dikosongkan: tidak ada tombol download — data hanya ke FTP.
            return new ExportResult(true, "Otorisasi berhasil — Paket " + paket + " diproses (" + rows.size() + " baris)." + ftpNote, rows.size(), null);
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

    /** Admin resend of a queued/failed transfer: re-push the server-side file to FTP. */
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

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
