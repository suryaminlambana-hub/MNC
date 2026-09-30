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

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Pembuatan file ACCTXT oleh admin (BUG_AUSY_00155 & BUG_AUSY_00158).
 *
 * Sejak BUG_AUSY_00156 otorisasi checker berhenti di status: approve menandai data disetujui,
 * tidak lagi menerbitkan file. Semua export yang sudah disetujui menunggu di sini sampai admin
 * menggabungkannya jadi SATU file ACCTXT untuk rentang tanggal yang dipilih.
 *
 * Alasan digabung: core banking membaca satu berkas per hari. Waktu tiap approve menulis
 * filenya sendiri, approve kedua di hari yang sama menimpa yang pertama dan core hanya
 * menerima sebagian data.
 */
@Service
public class AcctxtGenerateService {

    /** Sejauh mana ke belakang admin boleh memilih tanggal (BUG_AUSY_00158). */
    public static final int MAX_DAYS_BACK = 3;

    private final GlExportRequestRepository requests;
    private final GlTabelRepository gl;
    private final ExportTransferRepository transfers;
    private final ExportFileStore files;
    private final AuditService audit;

    public AcctxtGenerateService(GlExportRequestRepository requests, GlTabelRepository gl,
                                 ExportTransferRepository transfers, ExportFileStore files,
                                 AuditService audit) {
        this.requests = requests; this.gl = gl; this.transfers = transfers;
        this.files = files; this.audit = audit;
    }

    public record Result(boolean ok, String message, String fileName, int rows, int exportCount) {
        static Result fail(String m) { return new Result(false, m, null, 0, 0); }
    }

    /** Tanggal paling awal yang boleh dipilih hari ini. */
    public LocalDate earliestSelectable() { return LocalDate.now().minusDays(MAX_DAYS_BACK); }

    /** Tanggal paling akhir yang boleh dipilih. */
    public LocalDate latestSelectable() { return LocalDate.now(); }

    /** Export yang sudah disetujui checker tapi belum pernah ikut file ACCTXT mana pun. */
    public List<GlExportRequest> pending(LocalDate from, LocalDate to) {
        return requests.findApprovedNotGenerated(from.atStartOfDay(), to.plusDays(1).atStartOfDay());
    }

    /** Semua yang menunggu digenerate, tanpa batas tanggal — untuk angka di layar. */
    public List<GlExportRequest> allPending() { return requests.findApprovedNotGenerated(); }

    /**
     * File ACCTXT yang sudah terbit pada rentang tanggal terpilih (BUG_AUSY_00167).
     *
     * Tanpa ini, memilih tanggal lampau selalu berakhir layar kosong: yang ditampilkan hanya
     * export yang BELUM dibuatkan file, padahal begitu filenya jadi export itu justru hilang
     * dari daftar. Admin jadi tidak punya cara melihat hasil generate hari-hari sebelumnya.
     */
    public List<ExportTransfer> generated(LocalDate from, LocalDate to) {
        return transfers.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                from.atStartOfDay(), to.plusDays(1).atStartOfDay());
    }

    /**
     * Gabungkan seluruh export approved pada rentang tanggal jadi satu file ACCTXT.
     *
     * Tanggal yang dipakai adalah tanggal PERSETUJUAN checker, bukan tanggal pengajuan maker:
     * yang menentukan sebuah data boleh dikirim ke core adalah kapan ia disetujui.
     */
    @Transactional
    public Result generate(LocalDate from, LocalDate to, AusyUserDetails me) {
        if (from == null || to == null) return Result.fail("Tanggal awal dan tanggal akhir wajib diisi.");
        if (to.isBefore(from)) return Result.fail("Tanggal akhir tidak boleh lebih awal dari tanggal awal.");

        LocalDate min = earliestSelectable(), max = latestSelectable();
        if (from.isBefore(min) || to.isAfter(max))
            return Result.fail("Tanggal hanya boleh dalam " + MAX_DAYS_BACK + " hari terakhir ("
                    + fmt(min) + " s/d " + fmt(max) + "). Pilihan di luar itu tidak diproses.");

        List<GlExportRequest> approved = pending(from, to);
        if (approved.isEmpty())
            return Result.fail("Tidak ada export berstatus approved pada " + rangeText(from, to)
                    + " yang belum dibuatkan file.");

        StringBuilder content = new StringBuilder();
        int rowCount = 0;
        for (GlExportRequest req : approved) {
            List<GlTabel> rows = gl.findByExportReqIdOrderById(req.getId());
            for (GlTabel g : rows) {
                content.append(GltrrfFormatter.line(g)).append(System.lineSeparator());
                rowCount++;
            }
        }
        if (rowCount == 0)
            return Result.fail("Export approved pada " + rangeText(from, to) + " tidak punya baris GL.");

        // Nama file AS400: ACCTXT.<YYMMDD> — dinamai menurut hari file ini dibuat, karena itu
        // yang dicari core banking saat mengambil berkas hari berjalan.
        String fileName = "ACCTXT." + LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
        Path out;
        try {
            out = files.write(fileName, content.toString());
        } catch (Exception e) {
            return Result.fail("Gagal menulis file ACCTXT: " + e.getMessage());
        }

        ExportTransfer t = new ExportTransfer();
        t.setFileName(fileName); t.setPaket("GENERATE " + rangeText(from, to));
        t.setInputer(me.getUsername()); t.setChecker(me.getUsername());
        t.setRowsCount(rowCount); t.setCreatedAt(LocalDateTime.now());
        t.setStatus("SENT"); t.setRemotePath(out.toString());
        t.setSentAt(LocalDateTime.now()); t.setAttempts(1);
        transfers.save(t);

        StringBuilder ids = new StringBuilder();
        for (GlExportRequest req : approved) {
            gl.markExported(req.getId());
            req.setFileName(fileName);
            req.setTransferId(t.getId());
            requests.save(req);
            if (!ids.isEmpty()) ids.append(", ");
            ids.append("CR#").append(req.getId());
        }

        audit.transaction(me.getUsername(), "ACCTXT_GENERATE",
                "Generate " + fileName + " untuk " + rangeText(from, to) + ": "
                + approved.size() + " export (" + ids + "), " + rowCount + " baris -> " + out);

        return new Result(true, "File " + fileName + " dibuat dari " + approved.size()
                + " export approved (" + rowCount + " baris) pada " + rangeText(from, to) + ".",
                fileName, rowCount, approved.size());
    }

    private static String fmt(LocalDate d) { return d.format(DateTimeFormatter.ofPattern("dd-MM-yyyy")); }

    private static String rangeText(LocalDate from, LocalDate to) {
        return from.equals(to) ? fmt(from) : fmt(from) + " s/d " + fmt(to);
    }
}
