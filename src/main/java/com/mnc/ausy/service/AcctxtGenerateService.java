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

        String content = contentOf(approved);
        int rowCount = lineCount(content);
        if (rowCount == 0)
            return Result.fail("Export approved pada " + rangeText(from, to) + " tidak punya baris GL.");

        // Nama file AS400: ACCTXT.<YYMMDD> — dinamai menurut hari file ini dibuat, karena itu
        // yang dicari core banking saat mengambil berkas hari berjalan.
        String fileName = "ACCTXT." + LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
        Path out;
        try {
            out = files.write(fileName, content);
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

    /**
     * Terbitkan ulang satu file ACCTXT yang sudah pernah dibuat (BUG_AUSY_00168).
     *
     * Dipakai kalau berkasnya hilang dari folder hasil export, terhapus, atau terlanjur diambil
     * core dalam keadaan tidak lengkap. Yang ditulis ulang isi yang sama persis — export yang
     * dulu ikut file itu, urut id seperti semula — dengan NAMA FILE YANG SAMA. Namanya tidak
     * diganti tanggal hari ini: yang diminta menerbitkan kembali berkas tanggal itu, bukan
     * membuat berkas baru, dan core mencarinya dengan nama itu.
     *
     * Tidak ada export yang berpindah file: {@code file_name} dan {@code transfer_id} pada
     * permintaan export tidak disentuh, jadi menerbitkan ulang tidak bisa membuat satu export
     * ikut dua berkas.
     */
    @Transactional
    public Result regenerate(Long transferId, AusyUserDetails me) {
        if (transferId == null) return Result.fail("File ACCTXT yang mau diterbitkan ulang belum dipilih.");
        ExportTransfer t = transfers.findById(transferId).orElse(null);
        if (t == null) return Result.fail("File ACCTXT tersebut tidak ditemukan.");

        // Batas 3 hari berlaku sama seperti saat membuat: yang boleh diterbitkan ulang hanya
        // berkas yang masih dalam jendela itu (BUG_AUSY_00158).
        LocalDate made = t.getCreatedAt() == null ? null : t.getCreatedAt().toLocalDate();
        LocalDate min = earliestSelectable(), max = latestSelectable();
        if (made == null || made.isBefore(min) || made.isAfter(max))
            return Result.fail("File " + t.getFileName() + " dibuat di luar " + MAX_DAYS_BACK
                    + " hari terakhir (" + fmt(min) + " s/d " + fmt(max) + "), jadi tidak bisa diterbitkan ulang.");

        List<GlExportRequest> isi = requests.findByTransferIdOrderById(transferId);
        if (isi.isEmpty())
            return Result.fail("File " + t.getFileName() + " tidak punya catatan export, isinya tidak bisa"
                    + " disusun ulang.");

        String content = contentOf(isi);
        int rowCount = lineCount(content);
        if (rowCount == 0)
            return Result.fail("Export yang membentuk " + t.getFileName() + " tidak punya baris GL lagi.");

        Path out;
        try {
            out = files.write(t.getFileName(), content);
        } catch (Exception e) {
            return Result.fail("Gagal menulis ulang file ACCTXT: " + e.getMessage());
        }

        t.setRowsCount(rowCount);
        t.setRemotePath(out.toString());
        t.setSentAt(LocalDateTime.now());
        t.setAttempts(t.getAttempts() == null ? 1 : t.getAttempts() + 1);
        t.setStatus("SENT");
        transfers.save(t);

        StringBuilder ids = new StringBuilder();
        for (GlExportRequest r : isi) {
            if (!ids.isEmpty()) ids.append(", ");
            ids.append("CR#").append(r.getId());
        }
        audit.transaction(me.getUsername(), "ACCTXT_REGENERATE",
                "Populate ulang " + t.getFileName() + ": " + isi.size() + " export (" + ids + "), "
                + rowCount + " baris -> " + out);

        return new Result(true, "File " + t.getFileName() + " diterbitkan ulang dari " + isi.size()
                + " export (" + rowCount + " baris).", t.getFileName(), rowCount, isi.size());
    }

    /** Isi berkas ACCTXT untuk sekumpulan export, urut seperti yang diberikan. */
    private String contentOf(List<GlExportRequest> reqs) {
        StringBuilder content = new StringBuilder();
        for (GlExportRequest req : reqs)
            for (GlTabel g : gl.findByExportReqIdOrderById(req.getId()))
                content.append(GltrrfFormatter.line(g)).append(System.lineSeparator());
        return content.toString();
    }

    private static int lineCount(String content) {
        if (content.isEmpty()) return 0;
        int n = 0, i = 0;
        while ((i = content.indexOf('\n', i) + 1) > 0) n++;
        return n;
    }

    private static String fmt(LocalDate d) { return d.format(DateTimeFormatter.ofPattern("dd-MM-yyyy")); }

    private static String rangeText(LocalDate from, LocalDate to) {
        return from.equals(to) ? fmt(from) : fmt(from) + " s/d " + fmt(to);
    }
}
