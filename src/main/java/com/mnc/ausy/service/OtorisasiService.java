package com.mnc.ausy.service;

import com.mnc.ausy.domain.GlTabel;
import com.mnc.ausy.repo.GlTabelRepository;
import com.mnc.ausy.security.AusyUserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

    public OtorisasiService(GlTabelRepository gl, ApprovalService approval, AuditService audit,
                            DataScopeService scope, StagingService staging, GlCodeService glCodes,
                            BatchStatusService batchStatus) {
        this.gl = gl; this.approval = approval; this.audit = audit; this.scope = scope; this.staging = staging;
        this.glCodes = glCodes; this.batchStatus = batchStatus;
    }

    public record Paket(String paket, String inputer, long jml, BigDecimal debit, BigDecimal credit, boolean balanced,
                        LocalDateTime uploadedAt) {}

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
     * REQ_AUSY_00037 &amp; 00047 — Bulk Edit per batch untuk batch upload MILIK maker sendiri yang
     * BELUM diotorisasi. Perubahan langsung diterapkan tanpa checker: datanya belum pernah
     * di-approve, dan checker tetap meng-approve batch-nya sesudah ini (sama seperti Hapus batch
     * pending, REQ_AUSY_00022). Karena langsung tersimpan, PDF batch ikut berubah.
     *
     * Baris dari berkas: id, gltrac, gltrcd, gltram (sudah berupa angka), gltrds. Berkas ditolak
     * UTUH bila ada satu pun baris yang tidak valid, Amount minus, atau hasil akhirnya tidak balance
     * (REQ_AUSY_00037 feedback) — dalam hal itu tidak ada yang tersimpan dan tidak sampai ke checker.
     */
    @Transactional
    public ExportResult bulkEditPending(String paket, AusyUserDetails me, List<Map<String, String>> isi) {
        if (me == null) return new ExportResult(false, "Sesi tidak valid.", 0, null);
        String maker = me.getUsername();
        String label = "Batch " + BatchLabel.label(paket);
        Map<Long, GlTabel> batch = new LinkedHashMap<>();
        for (GlTabel g : gl.findBatchRows(paket, maker)) batch.put(g.getId(), g);
        if (batch.isEmpty())
            return new ExportResult(false, label + " tidak ditemukan di upload milik Anda.", 0, null);
        // REQ_AUSY_00053: batch yang ditolak checker juga boleh diperbaiki; setelah berhasil, batch itu
        // otomatis diajukan ulang (kembali menunggu otorisasi checker).
        boolean dariReject = ditolak(new ArrayList<>(batch.values()));
        if (!menunggu(new ArrayList<>(batch.values())) && !dariReject)
            return new ExportResult(false, label + " sudah diproses checker; Bulk Edit hanya untuk batch"
                    + " yang belum diotorisasi atau ditolak checker.", 0, null);
        if (isi == null || isi.isEmpty())
            return new ExportResult(false, "File tidak berisi baris data. " + PAKAI_TEMPLATE, 0, null);

        // Berkas batch lain / berkas lain sama sekali: tidak ada satu ID pun yang milik batch ini.
        // Ditolak dengan satu kalimat, bukan daftar kesalahan per baris (REQ_AUSY_00037 feedback).
        boolean adaMilikBatch = isi.stream().anyMatch(m -> {
            try { return batch.containsKey(Long.valueOf(n(m.get("id")).trim())); } catch (Exception e) { return false; }
        });
        if (!adaMilikBatch)
            return new ExportResult(false, "File ini bukan hasil Export Excel " + label + ". " + PAKAI_TEMPLATE, 0, null);

        // Semua baris divalidasi dan direncanakan DULU tanpa menyentuh entitas — kalau ditolak
        // (tidak valid / minus / tidak balance), tidak ada satu pun perubahan yang tersimpan dan
        // tidak ada yang sampai ke antrian checker.
        record Ubah(GlTabel g, String acc, String dc, String ds, BigDecimal amt) {}
        List<String> salah = new ArrayList<>();
        Map<Long, Ubah> rencana = new LinkedHashMap<>();
        for (Map<String, String> m : isi) {
            String baris = m.getOrDefault("baris", "?");
            Long id;
            try { id = Long.valueOf(n(m.get("id")).trim()); } catch (Exception e) { salah.add("baris " + baris + ": ID tidak valid"); continue; }
            GlTabel g = batch.get(id);
            if (g == null) { salah.add("baris " + baris + ": ID " + id + " bukan milik " + label); continue; }
            if (rencana.containsKey(id)) { salah.add("baris " + baris + ": ID " + id + " muncul lebih dari sekali"); continue; }
            String acc = n(m.get("gltrac")).trim(), dc = n(m.get("gltrcd")).trim().toUpperCase();
            String ds = GlTextRule.trim(n(m.get("gltrds")));
            BigDecimal amt;
            try { amt = new BigDecimal(n(m.get("gltram")).trim()); } catch (Exception e) { amt = null; }
            boolean ok = true;
            if (!acc.matches("[0-9]{1,50}")) { salah.add("baris " + baris + ": GL Code hanya boleh angka"); ok = false; }
            if (!dc.equals("D") && !dc.equals("C")) { salah.add("baris " + baris + ": D/C harus D atau C"); ok = false; }
            if (amt == null) { salah.add("baris " + baris + ": Amount tidak terbaca"); ok = false; }
            else if (amt.signum() < 0) { salah.add("baris " + baris + ": Amount minus (" + Nominal.format(amt) + ")"); ok = false; }
            if (!ok) continue;
            if (ds.length() > 40) ds = ds.substring(0, 40);                    // lebar kolom ACCTXT
            rencana.put(id, new Ubah(g, acc, dc, ds, amt));
        }
        if (!salah.isEmpty())
            return new ExportResult(false, "Bulk Edit ditolak, tidak ada data yang diubah: " + ringkasSalah(salah)
                    + ". Perbaiki file lalu unggah lagi.", 0, null);

        // Balance dihitung dari hasil akhir batch (baris yang tidak ada di file tetap nilai lamanya).
        BigDecimal d = BigDecimal.ZERO, c = BigDecimal.ZERO;
        for (GlTabel g : batch.values()) {
            Ubah u = rencana.get(g.getId());
            String dc = u != null ? u.dc() : n(g.getGltrcd());
            BigDecimal amt = u != null ? u.amt() : nz(g.getGltram());
            if ("D".equalsIgnoreCase(dc)) d = d.add(amt); else c = c.add(amt);
        }
        if (d.compareTo(c) != 0)
            return new ExportResult(false, "Bulk Edit ditolak, tidak ada data yang diubah: hasil edit " + label
                    + " tidak balance (Debit " + Nominal.format(d) + ", Credit " + Nominal.format(c) + ", selisih "
                    + Nominal.format(d.subtract(c).abs()) + "). Perbaiki file lalu unggah lagi.", 0, null);

        List<GlTabel> berubah = new ArrayList<>();
        for (Ubah u : rencana.values()) {
            GlTabel g = u.g();
            boolean sama = u.acc().equals(n(g.getGltrac())) && u.dc().equalsIgnoreCase(n(g.getGltrcd()))
                    && g.getGltram() != null && u.amt().compareTo(g.getGltram()) == 0 && u.ds().equals(n(g.getGltrds()));
            if (sama) continue;
            if (!u.acc().equals(n(g.getGltrac()))) {
                String nama = glCodes.descriptionOf(u.acc());
                if (!nama.isEmpty()) g.setGlName(nama);
            }
            g.setGltrac(u.acc()); g.setGltrcd(u.dc()); g.setGltrds(u.ds());
            // Ketiga kolom nominal dipakai berkas ACCTXT; diisi sama seperti saat upload.
            g.setGltram(u.amt()); g.setGleqam(u.amt()); g.setGlotam(u.amt());
            berubah.add(g);
        }
        if (berubah.isEmpty())
            return new ExportResult(false, "Tidak ada perubahan pada " + label + ".", 0, null);

        gl.saveAll(berubah);
        if (dariReject) {
            for (GlTabel g : batch.values()) { g.setStatusDual("PENDING"); g.setActive((short) 1); }
            gl.saveAll(batch.values());
            batchStatus.clear(paket, maker);
        }
        audit.transaction(maker, "BULK_EDIT_PENDING",
                "Bulk Edit " + label + (dariReject ? " (Rejected, diajukan ulang ke checker), " : " (belum diotorisasi), ")
                + berubah.size() + " baris diubah langsung. Debit = Credit = " + Nominal.format(d) + ".");
        return new ExportResult(true, "Bulk Edit " + label + " diterapkan: " + berubah.size()
                + " baris diubah. Debit = Credit (balance)."
                + (dariReject ? " Batch diajukan ulang dan kembali menunggu otorisasi checker." : ""), berubah.size(), null);
    }

    public static final String PAKAI_TEMPLATE = "Gunakan file dari tombol Export Excel pada batch tersebut,"
            + " ubah isinya tanpa mengubah susunan kolom, lalu unggah lagi.";

    /** Maksimal 5 kesalahan pertama + jumlah sisanya, supaya pesannya tidak memenuhi layar. */
    public static String ringkasSalah(List<String> salah) {
        if (salah.size() <= 5) return String.join("; ", salah);
        return String.join("; ", salah.subList(0, 5)) + "; dan " + (salah.size() - 5) + " kesalahan lainnya";
    }

    private static String n(String s) { return s == null ? "" : s; }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
