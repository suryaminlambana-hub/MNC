package com.mnc.ausy.service;

import com.mnc.ausy.domain.GlTabel;
import com.mnc.ausy.repo.GlTabelRepository;
import com.mnc.ausy.security.AusyUserDetails;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Otorisasi UPLOAD GL: checker approves an uploaded paket → GL becomes active.
 * NOTE: approving an upload NO LONGER generates ACCTXT — that now lives in the
 * separate maker-initiated export flow (GlExportService), authorized in "Otorisasi Export GL".
 */
@Service
public class OtorisasiService {
    private final GlTabelRepository gl;
    private final ApprovalService approval;
    private final AuditService audit;
    private final DataScopeService scope;
    private final StagingService staging;
    private final GlCodeService glCodes;
    private final BatchStatusService batchStatus;
    private final UploadService uploads;

    public OtorisasiService(GlTabelRepository gl, ApprovalService approval, AuditService audit,
                            DataScopeService scope, StagingService staging, GlCodeService glCodes,
                            BatchStatusService batchStatus, UploadService uploads) {
        this.gl = gl; this.approval = approval; this.audit = audit; this.scope = scope; this.staging = staging;
        this.glCodes = glCodes; this.batchStatus = batchStatus; this.uploads = uploads;
    }

    public record Paket(String paket, String inputer, long jml, BigDecimal debit, BigDecimal credit, boolean balanced,
                        LocalDateTime uploadedAt, LocalDate tanggalPosting, boolean hariIni) {}

    /**
     * Pending pakets this checker may authorize: role-nya berwenang (matriks approval_group) DAN
     * maker-nya memang dipasangkan padanya di menu Pasangan Checker - Maker (BUG_AUSY_00139).
     */
    public List<Paket> pendingFor(AusyUserDetails me) {
        List<Paket> out = new ArrayList<>();
        for (Object[] r : gl.pendingPakets()) {
            String paket = (String) r[0];
            String inputer = (String) r[1];
            if (!approval.canApprove(me == null ? null : me.getIdGroup(), inputer)) continue;
            if (!scope.canSee(me, inputer)) continue;
            long jml = ((Number) r[2]).longValue();
            BigDecimal d = (BigDecimal) r[3], c = (BigDecimal) r[4];
            LocalDate tgl = r.length > 6 ? tanggalPosting((String) r[6]) : null;
            out.add(new Paket(paket, inputer, jml, d, c, d.compareTo(c) == 0, toLocalDateTime(r[5]),
                    tgl, LocalDate.now().equals(tgl)));
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

    /** Otorisasi UPLOAD: approve a pending paket → mark active (status_dual=APPROVED). No export here. */
    @Transactional
    public ExportResult export(String paket, String inputer, AusyUserDetails me) {
        String checker = me.getUsername();
        if (!approval.canApprove(me.getIdGroup(), inputer))
            return new ExportResult(false, "Group Anda tidak berwenang meng-approve batch dari group tersebut.", 0, null);
        if (!scope.canSee(me, inputer)) return new ExportResult(false, DataScopeService.NOT_MY_MAKER, 0, null);

        List<GlTabel> rows = gl.findBatchRows(paket, inputer);
        if (rows.isEmpty()) return new ExportResult(false, "Batch tidak ditemukan.", 0, null);
        if (!menunggu(rows))
            return new ExportResult(false, "Batch " + BatchLabel.label(paket) + " tidak sedang menunggu otorisasi.", 0, null);
        // REQ_AUSY_00056: batch hanya bisa di-approve pada hari tanggal posting-nya. Batch hari
        // sebelumnya hanya bisa ditolak; maker memperbaruinya lewat Bulk Edit (tanggal posting
        // menjadi hari ini) atau menghapusnya.
        String hariIni = LocalDate.now().format(DDMMYY);
        if (!rows.stream().allMatch(g -> hariIni.equals(g.getGltrpd())))
            return new ExportResult(false, "Batch " + BatchLabel.label(paket) + " bertanggal posting "
                    + tanggalTampil(rows.get(0).getGltrpd()) + ", bukan hari ini — tidak bisa di-approve."
                    + " Tolak batch ini, atau minta maker memperbaruinya lewat Bulk Edit.", 0, null);

        BigDecimal d = BigDecimal.ZERO, c = BigDecimal.ZERO;
        for (GlTabel g : rows) {
            if ("D".equalsIgnoreCase(g.getGltrcd())) d = d.add(nz(g.getGltram())); else c = c.add(nz(g.getGltram()));
        }
        if (d.compareTo(c) != 0)
            return new ExportResult(false, "Batch tidak balance (Debit != Credit). Otorisasi dibatalkan.", 0, null);

        // Tandai lolos otorisasi upload, lalu langsung masukkan ke penampungan role EXECUTE
        // (REQ_AUSY_00038). Langkah lama "maker ajukan export → checker approve export" tidak ada
        // lagi: approve upload sudah cukup.
        for (GlTabel g : rows) {
            g.setStatusDual("APPROVED");
            g.setStatusPrint("BELUM UPLOAD");
        }
        gl.saveAll(rows);
        staging.stage(rows, inputer, checker, "Batch " + BatchLabel.label(paket));
        batchStatus.clear(paket, inputer);                 // REQ_AUSY_00053: tidak lagi Dikembalikan
        audit.transaction(checker, "OTORISASI_APPROVE",
                "Batch " + BatchLabel.label(paket) + " (maker=" + inputer + ") di-approve, " + rows.size()
                + " baris masuk penampungan Generate File ACCTXT.");
        return new ExportResult(true, "Otorisasi upload berhasil — Batch " + BatchLabel.label(paket) + " (" + rows.size()
                + " baris) masuk penampungan dan siap digenerate oleh role EXECUTE.", rows.size(), null);
    }

    /**
     * Reject a pending paket (Workflow 8.1.e / 8.2.d). REQ_AUSY_00053: barisnya TIDAK dihapus lagi —
     * ditandai REJECTED (active=0, jadi keluar dari antrian checker dan dari GL Aktif) supaya maker
     * masih bisa membuka PDF-nya, memperbaikinya lewat Bulk Edit (lalu otomatis diajukan ulang),
     * atau menghapusnya.
     */
    @Transactional
    public ExportResult reject(String paket, String inputer, AusyUserDetails me, String note) {
        String checker = me.getUsername();
        if (!approval.canApprove(me.getIdGroup(), inputer))
            return new ExportResult(false, "Group Anda tidak berwenang menolak batch dari group tersebut.", 0, null);
        if (!scope.canSee(me, inputer)) return new ExportResult(false, DataScopeService.NOT_MY_MAKER, 0, null);
        if (note == null || note.isBlank())
            return new ExportResult(false, "Catatan penolakan wajib diisi.", 0, null);

        List<GlTabel> rows = gl.findBatchRows(paket, inputer);
        if (rows.isEmpty()) return new ExportResult(false, "Batch tidak ditemukan.", 0, null);
        if (!menunggu(rows))
            return new ExportResult(false, "Batch " + BatchLabel.label(paket) + " tidak sedang menunggu otorisasi.", 0, null);

        // REQ_AUSY_00060 — otorisasi yang menginap tidak dikembalikan ke maker. Batch yang
        // diunggah hari sebelumnya dan baru ditolak sekarang langsung hilang: barisnya dihapus,
        // tidak ada catatan status, sehingga tidak muncul di List Data GL maupun Rekapitulasi.
        // Aturan bisnis yang disengaja — maker tidak boleh memperbaiki dan mengajukan ulang
        // data yang tanggal posting-nya sudah lewat.
        boolean menginap = BatchStatusService.lintasHari(rows.get(0).getAdddt());
        if (menginap) {
            gl.deletePaket(paket, inputer);
            batchStatus.clear(paket, inputer);
            audit.transaction(checker, "OTORISASI_REJECT",
                    "Batch " + BatchLabel.label(paket) + " (maker=" + inputer + ") DITOLAK, " + rows.size()
                    + " baris DIHAPUS — otorisasi dari hari sebelumnya tidak dikembalikan ke maker"
                    + " (REQ_AUSY_00060). Catatan: " + note);
            return new ExportResult(true, "Batch " + BatchLabel.label(paket) + " ditolak. Karena otorisasinya"
                    + " berasal dari hari sebelumnya, datanya tidak dikembalikan ke maker dan langsung dihapus."
                    + " Alasan tercatat di audit trail.", rows.size(), null);
        }

        for (GlTabel g : rows) { g.setStatusDual(REJECTED); g.setActive((short) 0); }
        gl.saveAll(rows);
        batchStatus.rejected(paket, inputer, checker, note);
        audit.transaction(checker, "OTORISASI_REJECT",
                "Batch " + BatchLabel.label(paket) + " (maker=" + inputer + ") DITOLAK, " + rows.size()
                + " baris dikembalikan ke maker berstatus Rejected. Catatan: " + note);
        return new ExportResult(true, "Batch " + BatchLabel.label(paket) + " ditolak dan keluar dari antrian. Maker melihatnya"
                + " berstatus Rejected di List Data GL, dapat memperbaikinya lalu mengajukan ulang. Alasan tercatat di"
                + " audit trail.", rows.size(), null);
    }

    /** Status baris batch upload yang ditolak checker (REQ_AUSY_00053). */
    public static final String REJECTED = "REJECTED";

    /** Seluruh baris batch menunggu otorisasi checker. */
    private static boolean menunggu(List<GlTabel> rows) {
        return rows.stream().allMatch(g -> "PENDING".equals(g.getStatusDual()) && Short.valueOf((short) 1).equals(g.getActive()));
    }

    /** Seluruh baris batch ditolak checker (REQ_AUSY_00053). */
    private static boolean ditolak(List<GlTabel> rows) {
        return rows.stream().allMatch(g -> REJECTED.equals(g.getStatusDual()));
    }

    /**
     * REQ_AUSY_00022 — maker menghapus batch upload MILIKNYA SENDIRI yang belum diotorisasi.
     *
     * Tanpa persetujuan checker: datanya belum pernah disetujui, jadi tidak ada yang perlu dijaga
     * dua orang — sama seperti batch yang ditolak checker, barisnya dibuang dari gl_tabel. Hanya
     * batch yang SELURUH barisnya masih menunggu otorisasi yang bisa dihapus, dan hanya oleh
     * pengunggahnya; checker pasangannya pun tidak bisa.
     */
    @Transactional
    public ExportResult withdraw(String paket, AusyUserDetails me) {
        if (me == null) return new ExportResult(false, "Sesi tidak valid.", 0, null);
        String maker = me.getUsername();
        List<GlTabel> rows = gl.findBatchRows(paket, maker);
        if (rows.isEmpty())
            return new ExportResult(false, "Batch " + BatchLabel.label(paket) + " tidak ditemukan di upload milik Anda.", 0, null);
        if (!menunggu(rows) && !ditolak(rows))
            return new ExportResult(false, "Batch " + BatchLabel.label(paket) + " sudah diproses checker dan tidak bisa dihapus dari sini.", 0, null);

        gl.deletePaket(paket, maker);
        batchStatus.clear(paket, maker);
        audit.transaction(maker, "UPLOAD_DELETE",
                "Batch " + BatchLabel.label(paket) + " dihapus pengunggahnya sebelum diotorisasi, " + rows.size() + " baris dibuang.");
        return new ExportResult(true, "Batch " + BatchLabel.label(paket) + " dihapus (" + rows.size()
                + " baris). Batch ini tidak lagi menunggu otorisasi checker.", rows.size(), null);
    }

    /**
     * REQ_AUSY_00037, 00047 &amp; 00057 — Bulk Edit per batch upload MILIK maker sendiri yang belum
     * diotorisasi atau ditolak checker. Berkasnya template MNC yang sama dengan Upload Excel (unduhan
     * Bulk Edit sudah terisi baris batch itu) dan dibaca dengan aturan yang SAMA
     * ({@link UploadService#baca}): kolom A wajib tanggal hari ini (REQ_AUSY_00056), nominal angka &amp; tidak
     * minus, kode angka, IDR, tanpa sel #N/A, dan total Debit = Credit.
     *
     * Template MNC tidak punya kolom ID, jadi isi batch DIGANTI seluruhnya oleh isi berkas (baris
     * boleh ditambah atau dihapus); nomor batch tetap. Berkas yang tidak lolos ditolak utuh — tidak
     * ada yang tersimpan dan tidak ada yang sampai ke checker. Langsung diterapkan tanpa checker:
     * datanya belum pernah di-approve, dan checker tetap meng-approve batch-nya sesudah ini. Batch
     * yang ditolak checker otomatis diajukan ulang (REQ_AUSY_00053).
     */
    @Transactional
    public ExportResult bulkEditPending(String paket, AusyUserDetails me, InputStream berkas) {
        if (me == null) return new ExportResult(false, "Sesi tidak valid.", 0, null);
        String maker = me.getUsername();
        String label = "Batch " + BatchLabel.label(paket);
        List<GlTabel> lama = gl.findBatchRows(paket, maker);
        if (lama.isEmpty())
            return new ExportResult(false, label + " tidak ditemukan di upload milik Anda.", 0, null);
        boolean dariReject = ditolak(lama);
        if (!menunggu(lama) && !dariReject)
            return new ExportResult(false, label + " sudah diproses checker; Bulk Edit hanya untuk batch"
                    + " yang belum diotorisasi atau ditolak checker.", 0, null);

        // REQ_AUSY_00056: Bulk Edit = unggah ulang, jadi tanggal posting wajib hari ini (sama seperti
        // Upload Excel). Batch hari sebelumnya yang diperbarui ikut bertanggal hari ini, sehingga bisa
        // di-approve lagi.
        LocalDate tanggal = LocalDate.now();
        UploadService.Bacaan b;
        try (Workbook wb = WorkbookFactory.create(berkas)) {
            b = uploads.baca(wb, tanggal, "hari ini (" 
                    + tanggal.format(DateTimeFormatter.ofPattern("d-MMM-yy", Locale.ENGLISH)).toLowerCase() + ")");
        } catch (Exception e) {
            return new ExportResult(false, "File tidak terbaca sebagai Excel. " + PAKAI_TEMPLATE, 0, null);
        }
        if (!b.ok()) {
            // Kesalahan per baris disebut langsung (maks. 5); pesan umum hanya bila tidak ada rinciannya.
            List<String> salah = new ArrayList<>();
            for (String[] e : b.errors)
                salah.add(("Total".equals(e[0]) ? "" : "baris " + e[0] + ": ") + e[1].replaceAll("\\.$", ""));
            String rincian = !salah.isEmpty() ? ringkasSalah(salah) + "."
                    : n(b.message).replace("Upload gagal: ", "");
            boolean bukanTemplate = n(b.message).startsWith("Format file salah");
            return new ExportResult(false, "Bulk Edit ditolak, tidak ada data yang diubah: " + rincian
                    + (bukanTemplate ? " " + PAKAI_TEMPLATE : " Perbaiki file lalu unggah lagi."), 0, null);
        }

        List<String> sebelum = lama.stream().map(OtorisasiService::sidik).toList();
        List<String> sesudah = b.rows.stream().map(OtorisasiService::sidik).toList();
        if (sebelum.equals(sesudah))
            return new ExportResult(false, "Tidak ada perubahan pada " + label + ".", 0, null);

        BigDecimal dLama = BigDecimal.ZERO;
        for (GlTabel g : lama) if ("D".equalsIgnoreCase(g.getGltrcd())) dLama = dLama.add(nz(g.getGltram()));
        LocalDateTime diupload = gl.uploadedAt(paket, maker);

        // REQ_AUSY_00066: baris batch ini dinomori ulang melanjutkan running number tanggal
        // batchnya. Kapasitas diperiksa SEBELUM baris lama dihapus, supaya penolakan tidak
        // meninggalkan batch kosong.
        uploads.kunciPenomoran(Referensi.tanggalReferensi(paket));
        String penuh = uploads.cekKapasitasHarian(paket, b.rows.size());
        if (penuh != null)
            return new ExportResult(false, "Bulk Edit ditolak, tidak ada data yang diubah: "
                    + penuh.replace("Upload gagal: ", ""), 0, null);

        gl.deletePaket(paket, maker);
        uploads.simpanBaris(b.rows, paket, maker);
        // Waktu upload batch tetap waktu upload aslinya, bukan waktu Bulk Edit.
        if (diupload != null) gl.setUploadedAt(paket, maker, diupload);
        // Rejected maupun Dikembalikan: setelah diperbaiki, statusnya kembali Menunggu Otorisasi
        // (REQ_AUSY_00053, 00058).
        batchStatus.clear(paket, maker);

        audit.transaction(maker, "BULK_EDIT_PENDING",
                "Bulk Edit " + label + (dariReject ? " (Rejected, diajukan ulang ke checker)" : " (belum diotorisasi)")
                + ": " + lama.size() + " baris (D=C=" + Nominal.format(dLama) + ") diganti " + b.rows.size()
                + " baris (D=C=" + Nominal.format(b.debit) + ") dari template MNC.");
        return new ExportResult(true, "Bulk Edit " + label + " diterapkan: " + b.rows.size() + " baris (sebelumnya "
                + lama.size() + "). Debit = Credit = " + Nominal.format(b.debit) + " (balance)."
                + (dariReject ? " Batch diajukan ulang dan kembali menunggu otorisasi checker." : " Status batch: Menunggu Otorisasi."), b.rows.size(), null);
    }

    /** Isi satu baris GL yang bisa diubah lewat template — untuk mendeteksi "tidak ada perubahan". */
    private static String sidik(GlTabel g) {
        return String.join("|", n(g.getGltrcd()), n(g.getGlprdc()), n(g.getGltrac()), n(g.getGlName()),
                n(g.getGltrds()), n(g.getGltrbr()), n(g.getBrName()), n(g.getGltrcc()), n(g.getCsName()),
                n(g.getGltrcy()), n(g.getGltrfy()), nz(g.getGltram()).stripTrailingZeros().toPlainString(),
                n(g.getGlflag()), n(g.getGlresp()), n(g.getGltrpd()));
    }

    private static final DateTimeFormatter DDMMYY = DateTimeFormatter.ofPattern("ddMMyy");

    /** Tanggal posting baris GL ({@code ddMMyy}) sebagai dd-MM-yyyy untuk pesan. */
    private static String tanggalTampil(String ddmmyy) {
        LocalDate t = tanggalPosting(ddmmyy);
        return t == null ? "-" : t.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
    }

    private static LocalDate tanggalPosting(String ddmmyy) {
        try { return LocalDate.parse(ddmmyy, DateTimeFormatter.ofPattern("ddMMyy")); } catch (Exception e) { return null; }
    }

    public static final String PAKAI_TEMPLATE = "Gunakan file dari tombol Download di popup Bulk Edit batch"
            + " tersebut (template MNC), ubah isinya tanpa mengubah susunan kolom, lalu unggah lagi.";

    /** Maksimal 5 kesalahan pertama + jumlah sisanya, supaya pesannya tidak memenuhi layar. */
    public static String ringkasSalah(List<String> salah) {
        if (salah.size() <= 5) return String.join("; ", salah);
        return String.join("; ", salah.subList(0, 5)) + "; dan " + (salah.size() - 5) + " kesalahan lainnya";
    }

    private static String n(String s) { return s == null ? "" : s; }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
