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
 *
 * <h2>Satu berkas per hari eksekusi (BUG_AUSY_00169)</h2>
 *
 * Sebuah berkas ACCTXT dikenali dari TANGGAL EKSEKUSI — kapan admin menekan tombolnya — bukan
 * dari tanggal data di dalamnya. Rentang tanggal yang dipilih admin menentukan data mana yang
 * ikut, tidak pernah menentukan nama berkas.
 *
 * Dari situ dua hal mengikuti sendirinya:
 * <ul>
 *   <li>berapa kali pun tombol ditekan dalam satu hari, hasilnya satu berkas — isinya selalu
 *       disusun ulang dari nol, tidak ditambal;</li>
 *   <li>menerbitkan ulang berkas lama berarti memindahkan isinya ke berkas hari ini, karena
 *       berkas bertanggal lampau tidak akan diambil core lagi.</li>
 * </ul>
 *
 * <h2>Rentang tanggal menentukan isi, bukan tambahan</h2>
 *
 * Isi berkas hari ini adalah export yang <b>disetujui checker dalam rentang yang dipilih
 * admin</b> — tidak lebih. Yang sempat masuk berkas ini dari penekanan sebelumnya tapi jatuh di
 * luar rentang baru akan dikeluarkan dan dikembalikan ke daftar tunggu, lengkap sampai penanda
 * statusnya, supaya tidak ada data yang hilang diam-diam: tidak ada di berkas tapi tercatat
 * sudah terkirim.
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

        LocalDateTime awal = from.atStartOfDay(), akhir = to.plusDays(1).atStartOfDay();
        List<GlExportRequest> terpilih = requests.findForFileInRange(todayFile(), awal, akhir);
        if (terpilih.isEmpty())
            return Result.fail("Tidak ada export berstatus approved pada " + rangeText(from, to)
                    + " yang bisa dibuatkan file.");

        // Rentang tanggal menentukan ISI berkas, bukan sekadar apa yang ditambahkan. Penghuni
        // berkas hari ini yang jatuh di luar rentang baru harus keluar — dan keluarnya sampai ke
        // penanda statusnya, supaya mereka muncul lagi di daftar tunggu. Tanpa ini datanya
        // menghilang diam-diam: tidak ada di berkas, tapi tercatat sudah terkirim.
        List<GlExportRequest> dilepas = requests.findInFileOutsideRange(todayFile(), awal, akhir);
        for (GlExportRequest req : dilepas) {
            gl.unmarkExported(req.getId());
            req.setFileName(null);
            req.setTransferId(null);
            requests.save(req);
        }

        for (GlExportRequest req : terpilih) {
            gl.markExported(req.getId());
            req.setFileName(todayFile());
            requests.save(req);
        }

        Result r = publish(terpilih, me, "ACCTXT_GENERATE",
                "Generate untuk " + rangeText(from, to) + (dilepas.isEmpty() ? ""
                        : ", " + dilepas.size() + " export di luar rentang dilepas kembali"));
        if (r.ok() && !dilepas.isEmpty())
            return new Result(true, r.message() + " " + dilepas.size() + " export di luar rentang itu"
                    + " dikeluarkan dari file dan kembali menunggu digenerate.",
                    r.fileName(), r.rows(), r.exportCount());
        return r;
    }

    /**
     * Tulis berkas ACCTXT hari ini dari daftar export yang diberikan, lalu perbarui catatannya.
     *
     * Inilah satu-satunya jalan berkas ACCTXT ditulis, dan sengaja begitu (BUG_AUSY_00169).
     * Sebelumnya tiap penekanan tombol menulis berkas berisi hanya export yang ikut penekanan
     * ITU. Karena namanya sama-sama ACCTXT.&lt;hari ini&gt;, penekanan kedua di hari yang sama
     * menimpa berkas yang pertama — export dari penekanan pertama sudah ditandai "sudah
     * dibuatkan file" sehingga tidak ikut disusun lagi, dan isinya lenyap dari berkas tanpa
     * satu pun pesan galat. Persis keluhan "file yang keluar hanya memuat salah satu tanggal".
     *
     * Berkasnya selalu disusun ulang dari nol, tidak pernah ditambal. Daftarnya DITENTUKAN
     * PEMANGGIL, bukan dibaca ulang dari "semua penghuni berkas": versi yang membaca ulang ikut
     * menyeret export dari penekanan sebelumnya, sehingga memilih 27-29 tetap menghasilkan
     * berkas yang memuat tanggal 30.
     */
    private Result publish(List<GlExportRequest> isi, AusyUserDetails me, String auditAction, String alasan) {
        String fileName = todayFile();
        if (isi.isEmpty()) return Result.fail("Tidak ada export yang bisa mengisi " + fileName + ".");

        String content = contentOf(isi);
        int rowCount = lineCount(content);
        if (rowCount == 0)
            return Result.fail("Export yang menghuni " + fileName + " tidak punya baris GL.");

        Path out;
        try {
            out = files.write(fileName, content);
        } catch (Exception e) {
            return Result.fail("Gagal menulis file ACCTXT: " + e.getMessage());
        }

        // Satu nama berkas = satu baris catatan. Penekanan berikutnya memperbarui baris yang
        // sama, jadi daftar di layar tidak memuat dua baris untuk berkas yang sebenarnya satu.
        ExportTransfer t = transfers.findFirstByFileNameOrderByIdAsc(fileName).orElseGet(() -> {
            ExportTransfer baru = new ExportTransfer();
            baru.setFileName(fileName);
            baru.setCreatedAt(LocalDateTime.now());
            baru.setInputer(me.getUsername());
            baru.setAttempts(0);
            return baru;
        });
        t.setChecker(me.getUsername());
        t.setPaket(cakupan(isi));
        t.setRowsCount(rowCount);
        t.setStatus("SENT");
        t.setRemotePath(out.toString());
        t.setSentAt(LocalDateTime.now());
        t.setAttempts((t.getAttempts() == null ? 0 : t.getAttempts()) + 1);
        transfers.save(t);

        StringBuilder ids = new StringBuilder();
        for (GlExportRequest req : isi) {
            req.setTransferId(t.getId());
            requests.save(req);
            if (!ids.isEmpty()) ids.append(", ");
            ids.append("CR#").append(req.getId());
        }

        audit.transaction(me.getUsername(), auditAction,
                alasan + " -> " + fileName + " berisi " + isi.size() + " export (" + ids + "), "
                + rowCount + " baris di " + out);

        return new Result(true, "File " + fileName + " berisi " + isi.size()
                + " export (" + rowCount + " baris), mencakup " + cakupan(isi) + ".",
                fileName, rowCount, isi.size());
    }

    /**
     * Nama berkas AS400 menurut TANGGAL EKSEKUSI, bukan tanggal data (BUG_AUSY_00169).
     *
     * Rentang tanggal yang dipilih admin menentukan data mana yang ikut, bukan nama berkasnya.
     * Admin yang menekan tombol pada 4 September untuk data 1–3 September tetap menghasilkan
     * ACCTXT.260904 — itu nama yang dicari core banking saat mengambil berkas hari berjalan.
     */
    private static String todayFile() { return "ACCTXT." + todayYymmdd(); }

    /** Bagian YYMMDD nama berkas hari ini — ditampilkan di layar supaya aturannya tidak ditebak. */
    public static String todayYymmdd() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
    }

    /** Rentang tanggal persetujuan yang benar-benar ada di dalam berkas — untuk kolom Cakupan. */
    private static String cakupan(List<GlExportRequest> isi) {
        LocalDate min = null, max = null;
        for (GlExportRequest r : isi) {
            if (r.getDecidedAt() == null) continue;
            LocalDate d = r.getDecidedAt().toLocalDate();
            if (min == null || d.isBefore(min)) min = d;
            if (max == null || d.isAfter(max)) max = d;
        }
        return min == null ? "-" : rangeText(min, max);
    }

    /**
     * Terbitkan ulang satu file ACCTXT yang sudah pernah dibuat (BUG_AUSY_00168).
     *
     * Dipakai kalau berkasnya hilang dari folder hasil export, terhapus, atau terlanjur diambil
     * core dalam keadaan tidak lengkap.
     *
     * Hasilnya selalu berkas BERTANGGAL HARI INI (BUG_AUSY_00169), bukan nama lama berkas yang
     * dipilih. Berkas lama sudah lewat harinya dan tidak akan diambil core lagi; supaya isinya
     * sampai, ia harus terbit sebagai berkas hari berjalan. Jadi export yang dulu menghuni
     * berkas itu dipindahkan ke berkas hari ini — dipindah, bukan disalin, supaya satu export
     * tidak pernah berada di dua berkas sekaligus dan core tidak menerima jurnal dobel.
     *
     * Kalau berkas hari ini sudah berisi export lain, semuanya bergabung: penyusunannya lewat
     * {@link #publish} yang selalu membaca seluruh penghuni berkas.
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

        String asal = t.getFileName();
        boolean pindah = !asal.equals(todayFile());
        if (pindah) {
            for (GlExportRequest r : isi) {
                r.setFileName(todayFile());
                requests.save(r);
            }
            // Catatan berkas lama dibiarkan ada sebagai jejak, tapi ditandai supaya jelas isinya
            // sudah tidak di situ lagi — daftar di layar tidak boleh menjanjikan isi yang pindah.
            t.setStatus("MOVED");
            t.setRowsCount(0);
            transfers.save(t);
        }

        // Berbeda dari generate, di sini tidak ada rentang tanggal: admin menunjuk satu berkas
        // dan minta isinya terbit lagi. Jadi yang ditulis seluruh penghuni berkas hari ini —
        // yang baru dipindah ditambah yang memang sudah ada di situ.
        Result r = publish(requests.findByFileNameOrderById(todayFile()), me, "ACCTXT_REGENERATE",
                "Populate ulang " + asal + (pindah ? " (isinya dipindah ke berkas hari ini)" : ""));
        if (r.ok() && pindah)
            return new Result(true, "Isi " + asal + " diterbitkan ulang sebagai " + r.fileName()
                    + " (" + r.rows() + " baris dari " + r.exportCount() + " export).",
                    r.fileName(), r.rows(), r.exportCount());
        return r;
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
