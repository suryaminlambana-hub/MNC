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
import java.util.ArrayList;
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
 * ikut, tidak pernah menentukan nama berkas. Berapa kali pun tombol ditekan dalam satu hari,
 * yang ada tetap satu berkas: isinya disusun ulang dari nol, tidak pernah ditambal.
 *
 * <h2>Dua tombol, dua satuan kerja (BUG_AUSY_00178)</h2>
 *
 * Aturannya ditetapkan pengguna, dan keduanya menimpa berkas hari ini — bukan menambahinya:
 *
 * <ul>
 *   <li><b>Generate Hari Ini</b> bekerja atas EXPORT. Isinya persis daftar "Menunggu digenerate":
 *       disetujui hari ini dan belum pernah dibuatkan berkas. Penekanan kedua pada hari yang sama
 *       berisi yang baru disetujui sejak penekanan pertama, dan berkasnya menimpa yang pertama.</li>
 *   <li><b>Susun Ulang</b> bekerja atas BERKAS HARIAN. Memilih 29 s/d 30 September berarti
 *       menggabungkan isi ACCTXT tanggal 29 dan ACCTXT tanggal 30 — masing-masing versi
 *       terakhirnya — menjadi satu berkas bertanggal hari ini.</li>
 * </ul>
 *
 * Susun Ulang tidak memindahkan kepemilikan: export tetap tercatat sebagai penghuni berkas
 * tanggal asalnya, sehingga rentang yang sama bisa disusun ulang berkali-kali dan daftar
 * "Menunggu digenerate" tidak pernah ikut bergerak karena ulahnya.
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
     * Susun ulang: gabungkan isi berkas ACCTXT pada rentang tanggal jadi satu berkas hari ini.
     *
     * Satuannya berkas harian, bukan export satu per satu (BUG_AUSY_00178) — lihat keterangan
     * kelas. Berkas hari ini ditimpa, bukan ditambahi.
     */
    @Transactional
    public Result generate(LocalDate from, LocalDate to, AusyUserDetails me) {
        if (from == null || to == null) return Result.fail("Tanggal awal dan tanggal akhir wajib diisi.");
        if (to.isBefore(from)) return Result.fail("Tanggal akhir tidak boleh lebih awal dari tanggal awal.");

        LocalDate min = earliestSelectable(), max = latestSelectable();
        if (from.isBefore(min) || to.isAfter(max))
            return Result.fail("Tanggal hanya boleh dalam " + MAX_DAYS_BACK + " hari terakhir ("
                    + fmt(min) + " s/d " + fmt(max) + "). Pilihan di luar itu tidak diproses.");

        // Satuan tombol ini BERKAS PER TANGGAL, bukan export satu per satu (BUG_AUSY_00178).
        // Memilih 29 s/d 30 September berarti: ambil isi ACCTXT tanggal 29 dan isi ACCTXT tanggal
        // 30 — masing-masing versi terakhirnya, karena generate berikutnya pada hari yang sama
        // menimpa yang sebelumnya — lalu gabungkan jadi satu berkas bertanggal hari ini.
        List<GlExportRequest> isi = new ArrayList<>();
        Set<String> sumber = new LinkedHashSet<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            String berkas = fileOf(d);
            // Penulisan TERAKHIR atas berkas tanggal itu — kalau hari itu digenerate dua kali,
            // yang berlaku isi penekanan kedua, bukan gabungan keduanya.
            ExportTransfer t = transfers.findFirstByFileNameOrderByIdDesc(berkas).orElse(null);
            if (t == null) continue;
            List<GlExportRequest> bagian = requests.findByTransferIdOrderById(t.getId());
            if (bagian.isEmpty()) continue;
            isi.addAll(bagian);
            sumber.add(berkas);
        }
        if (isi.isEmpty())
            // Dibedakan tegas dari kegagalan menulis (BUG_AUSY_00176): tidak ada yang cocok bukan
            // berarti penyimpanannya bermasalah, dan berkas yang sudah ada tidak ikut disentuh.
            return Result.fail("Tidak ada file ACCTXT pada " + rangeText(from, to)
                    + " yang bisa disusun ulang. Tidak ada file yang ditulis, dan isi file hari ini"
                    + " tidak berubah. Export yang disetujui hari ini tidak termasuk di sini —"
                    + " pakai tombol \"Generate File ACCTXT Hari Ini\".");

        // Penanda berkas asalnya SENGAJA tidak diubah. Export tetap tercatat sebagai penghuni
        // berkas tanggal itu, sehingga rentang yang sama bisa disusun ulang lagi kapan pun —
        // memang itu gunanya tombol ini — dan daftar "Menunggu digenerate" tidak ikut bergerak
        // sedikit pun, karena tidak ada yang dilepas maupun ditarik ke berkas lain.
        Result r = publish(isi, me, "ACCTXT_GENERATE",
                "Susun ulang " + rangeText(from, to) + " dari " + String.join(", ", sumber));
        if (!r.ok()) return r;
        return new Result(true, r.message() + " Digabung dari " + String.join(" + ", sumber) + ".",
                r.fileName(), r.rows(), r.exportCount());
    }

    /** Nama berkas ACCTXT untuk sebuah tanggal. */
    private static String fileOf(LocalDate d) {
        return "ACCTXT." + d.format(DateTimeFormatter.ofPattern("yyMMdd"));
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
        // Persis isi daftar "Menunggu digenerate": disetujui hari ini DAN belum pernah dibuatkan
        // berkas (BUG_AUSY_00178). Yang sudah ikut generate sebelumnya tidak diambil lagi, jadi
        // penekanan kedua pada hari yang sama menghasilkan berkas berisi yang baru saja disetujui
        // — menimpa berkas penekanan pertama, sesuai aturan satu berkas per hari.
        List<GlExportRequest> isi = requests.findApprovedNotGeneratedBetween(
                today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        if (isi.isEmpty())
            return Result.fail("Tidak ada export yang disetujui hari ini dan belum digenerate."
                    + " Daftar \"Menunggu digenerate\" sedang kosong untuk hari ini.");

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

        // Tiap penulisan dapat barisnya sendiri (BUG_AUSY_00178). Isi berkas yang berlaku adalah
        // isi penulisan TERAKHIR; penulisan sebelumnya tetap tercatat supaya bisa dibedakan mana
        // export yang benar-benar ada di berkas hari itu dan mana yang sempat masuk lalu
        // tertimpa. Tombol Susun Ulang mengambil yang terakhir saja.
        int keBerapa = transfers.findFirstByFileNameOrderByIdDesc(fileName)
                .map(lama -> (lama.getAttempts() == null ? 1 : lama.getAttempts()) + 1).orElse(1);
        ExportTransfer t = new ExportTransfer();
        t.setFileName(fileName);
        t.setCreatedAt(LocalDateTime.now());
        t.setInputer(me.getUsername());
        t.setChecker(me.getUsername());
        t.setPaket(cakupan(isi));
        t.setRowsCount(rowCount);
        t.setStatus("SENT");
        t.setRemotePath(out.toString());
        t.setSentAt(LocalDateTime.now());
        t.setAttempts(keBerapa);
        transfers.save(t);

        // transfer_id hanya diperbarui untuk export yang memang MENGHUNI berkas ini. Export yang
        // ikut karena disalin dari berkas tanggal lain (tombol Susun Ulang) tidak boleh dialihkan:
        // penandanya itulah yang menyimpan "penulisan terakhir berkas tanggal tersebut". Kalau
        // dialihkan ke penulisan hari ini, rentang yang sama tidak bisa disusun ulang untuk kedua
        // kalinya — pencariannya berakhir kosong.
        StringBuilder ids = new StringBuilder();
        for (GlExportRequest req : isi) {
            if (fileName.equals(req.getFileName())) {
                req.setTransferId(t.getId());
                requests.save(req);
            }
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
