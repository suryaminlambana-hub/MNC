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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
    /**
     * Tanggal paling akhir yang boleh dipilih: KEMARIN, bukan hari ini (BUG_AUSY_00172).
     *
     * "3 hari terakhir" dihitung tidak termasuk hari berjalan — hari ini 30 Sep berarti
     * pilihannya 27 s/d 29 Sep. Export yang disetujui hari ini tidak lewat sini, melainkan
     * lewat tombol pada daftar "Menunggu digenerate" (BUG_AUSY_00174).
     */
    public LocalDate latestSelectable() { return LocalDate.now().minusDays(1); }

    /** Export yang sudah disetujui checker tapi belum pernah ikut file ACCTXT mana pun. */
    public List<GlExportRequest> pending(LocalDate from, LocalDate to) {
        return requests.findApprovedNotGenerated(from.atStartOfDay(), to.plusDays(1).atStartOfDay());
    }

    /** Semua yang menunggu digenerate, tanpa batas tanggal — untuk angka di layar. */
    public List<GlExportRequest> allPending() { return requests.findApprovedNotGenerated(); }

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
        // SELURUH export yang disetujui pada rentang itu, termasuk yang sudah pernah masuk berkas
        // hari lain (BUG_AUSY_00176). Menyusun ulang berarti menyusun ulang: export tanggal 29
        // yang kemarin sudah dibuatkan berkas tetap ikut kalau tanggal 29 yang dipilih. Syarat
        // lama — file_name kosong atau sama dengan berkas hari ini — membuat export semacam itu
        // tidak pernah terjangkau, dan admin yang memilih tanggal 29 justru dijawab "tidak ada
        // export pada tanggal itu" padahal exportnya jelas ada.
        List<GlExportRequest> terpilih = requests.findApprovedBetween(awal, akhir);
        if (terpilih.isEmpty())
            // Dibedakan tegas dari kegagalan menulis (BUG_AUSY_00176): tidak ada yang cocok bukan
            // berarti penyimpanannya bermasalah, dan berkas yang sudah ada tidak ikut disentuh.
            return Result.fail("Tidak ada export berstatus approved pada " + rangeText(from, to)
                    + ". Tidak ada file yang ditulis, dan isi file hari ini tidak berubah."
                    + " Export yang disetujui hari ini tidak termasuk di sini — pakai tombol"
                    + " \"Generate File ACCTXT Hari Ini\".");

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

        // Berkas lama yang ditinggalkan export-exportnya dicatat dulu, sebelum penandanya ditimpa.
        Set<String> berkasLama = new LinkedHashSet<>();
        for (GlExportRequest req : terpilih) {
            String f = req.getFileName();
            if (f != null && !f.equals(todayFile())) berkasLama.add(f);
        }

        for (GlExportRequest req : terpilih) {
            gl.markExported(req.getId());
            req.setFileName(todayFile());
            requests.save(req);
        }
        rapikanBerkasLama(berkasLama);

        Result r = publish(terpilih, me, "ACCTXT_GENERATE",
                "Generate untuk " + rangeText(from, to)
                + (berkasLama.isEmpty() ? "" : ", isi " + String.join(", ", berkasLama) + " ditarik ke berkas hari ini")
                + (dilepas.isEmpty() ? "" : ", " + dilepas.size() + " export di luar rentang dilepas kembali"));
        if (!r.ok()) return r;

        StringBuilder tambahan = new StringBuilder();
        if (!berkasLama.isEmpty())
            tambahan.append(" Isi ").append(String.join(", ", berkasLama))
                    .append(" ikut ditarik ke file hari ini, jadi satu file.");
        if (!dilepas.isEmpty())
            tambahan.append(' ').append(dilepas.size())
                    .append(" export di luar rentang itu dikeluarkan dari file dan kembali menunggu digenerate.");
        return tambahan.isEmpty() ? r
                : new Result(true, r.message() + tambahan, r.fileName(), r.rows(), r.exportCount());
    }

    /**
     * Catatan berkas yang penghuninya pindah ke berkas hari ini (BUG_AUSY_00176).
     *
     * Kalau tidak dirapikan, catatannya tetap mengaku memuat sekian baris padahal isinya sudah
     * pindah — dan berkas lamanya sendiri memang tidak ditulis ulang. Yang kosong ditandai MOVED;
     * yang masih bersisa cukup disesuaikan jumlah exportnya.
     */
    private void rapikanBerkasLama(Set<String> namaBerkas) {
        for (String nama : namaBerkas) {
            ExportTransfer t = transfers.findFirstByFileNameOrderByIdAsc(nama).orElse(null);
            if (t == null) continue;
            List<GlExportRequest> sisa = requests.findByFileNameOrderById(nama);
            if (sisa.isEmpty()) {
                t.setStatus("MOVED");
                t.setRowsCount(0);
            } else {
                t.setRowsCount(lineCount(contentOf(sisa)));
            }
            transfers.save(t);
        }
    }

    /**
     * Susun berkas ACCTXT hari ini dari export yang disetujui HARI INI (BUG_AUSY_00174).
     *
     * Tombol kedua, pasangan dari {@link #generate}. Bedanya tegas: yang di atas memilih isi
     * berkas menurut rentang tanggal lampau dan karenanya bisa MELEPAS penghuni yang jatuh di
     * luar rentang; yang ini tidak pernah melepas apa pun, hanya menarik masuk persetujuan
     * hari ini. Export yang disetujui hari ini memang hanya bisa lewat sini, karena sejak
     * BUG_AUSY_00172 hari ini tidak bisa dipilih pada rentang tanggal.
     *
     * <p><b>Ada celah yang disengaja.</b> Export yang disetujui lebih dari {@value #MAX_DAYS_BACK}
     * hari lalu dan belum pernah dibuatkan berkas tidak terjangkau tombol mana pun: terlalu tua
     * untuk rentang tanggal, bukan hari ini untuk tombol ini. Karena itu daftar di layar sengaja
     * menampilkan SELURUH yang menunggu, bukan hanya hari ini, dan menandai yang sudah lewat
     * jangkauan — supaya kelihatan, bukan hilang diam-diam. Lihat {@link #strandedPending()}.
     */
    @Transactional
    public Result generateToday(AusyUserDetails me) {
        LocalDate today = LocalDate.now();
        List<GlExportRequest> isi = requests.findForFileOrApprovedBetween(
                todayFile(), today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        if (isi.isEmpty())
            return Result.fail("Tidak ada export yang disetujui hari ini dan menunggu digenerate.");

        for (GlExportRequest req : isi) {
            gl.markExported(req.getId());
            req.setFileName(todayFile());
            requests.save(req);
        }
        return publish(isi, me, "ACCTXT_GENERATE_TODAY",
                "Generate export yang disetujui hari ini (" + isi.size() + " export)");
    }

    /**
     * Folder tempat berkas ACCTXT terbit, untuk ditampilkan di layar (BUG_AUSY_00176).
     *
     * Admin perlu tahu ke mana hasilnya pergi tanpa membuka Explorer — apalagi folder yang
     * berlaku belum tentu sama dengan yang tertulis di setelan: ExportFileStore mengabaikan
     * setelan yang tidak bisa dipakai dan memperbaiki salah tulis sendiri.
     */
    public String exportDirText() { return files.dir().toString(); }

    /** Berapa yang menunggu dan disetujui hari ini — angka yang dijanjikan tombol Hari Ini. */
    public long todayPendingCount() {
        LocalDate today = LocalDate.now();
        return allPending().stream()
                .filter(r -> r.getDecidedAt() != null && !r.getDecidedAt().toLocalDate().isBefore(today))
                .count();
    }

    /**
     * Export menunggu yang sudah lewat jangkauan kedua tombol: disetujui sebelum
     * {@link #earliestSelectable()} sehingga tidak bisa dipilih di rentang tanggal, dan bukan
     * hari ini sehingga tidak terbawa tombol Hari Ini. Ditampilkan sebagai peringatan.
     */
    public List<GlExportRequest> strandedPending() {
        LocalDate min = earliestSelectable();
        return allPending().stream()
                .filter(r -> r.getDecidedAt() != null && r.getDecidedAt().toLocalDate().isBefore(min))
                .toList();
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

        // Lokasinya disebutkan, bukan hanya nama berkasnya (BUG_AUSY_00176). Sejak bagian "File
        // ACCTXT yang sudah dibuat" di-take out (BUG_AUSY_00173), layar tidak lagi punya tempat
        // lain yang memperlihatkan berkasnya mendarat di mana — "tersimpan atau tidak" jadi tidak
        // bisa dipastikan dari aplikasi, dan penguji melaporkannya sebagai gagal tersimpan.
        return new Result(true, "File " + out + " berisi " + isi.size()
                + " export (" + rowCount + " baris), mencakup " + cakupan(isi) + ". "
                + rincian(isi),
                fileName, rowCount, isi.size());
    }

    /**
     * Export mana saja yang masuk, beserta tanggal persetujuannya (BUG_AUSY_00178).
     *
     * Hasilnya sempat dibaca sebagai "seluruh data yang pernah diexport" karena yang terlihat
     * hanya jumlahnya. Dengan disebut satu per satu, admin bisa langsung mencocokkan apa yang
     * masuk dengan daftar di layar — dan terlihat bahwa yang dipakai memang tanggal persetujuan
     * Checker. Dipotong supaya pesannya tetap terbaca kalau exportnya banyak.
     */
    private static String rincian(List<GlExportRequest> isi) {
        int tampil = Math.min(isi.size(), 8);
        StringBuilder sb = new StringBuilder("Yang masuk: ");
        for (int i = 0; i < tampil; i++) {
            GlExportRequest r = isi.get(i);
            if (i > 0) sb.append(", ");
            sb.append("CR#").append(r.getId());
            if (r.getDecidedAt() != null) sb.append(" (disetujui ").append(fmt(r.getDecidedAt().toLocalDate())).append(')');
        }
        if (isi.size() > tampil) sb.append(", dan ").append(isi.size() - tampil).append(" lainnya");
        return sb.append('.').toString();
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
