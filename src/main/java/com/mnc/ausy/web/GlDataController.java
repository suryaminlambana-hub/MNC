package com.mnc.ausy.web;

import com.mnc.ausy.service.BatchLabel;
import com.mnc.ausy.domain.GlTabel;
import com.mnc.ausy.domain.TUpload;
import com.mnc.ausy.repo.GlTabelRepository;
import com.mnc.ausy.repo.TUploadRepository;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.BatchStatusService;
import com.mnc.ausy.service.DataScopeService;
import com.mnc.ausy.service.UploadTemplateService;
import com.mnc.ausy.service.OtorisasiService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

@Controller
public class GlDataController {
    private final GlTabelRepository gl;
    private final DataScopeService scope;
    private final OtorisasiService otorisasi;
    private final TUploadRepository uploads;
    private final UploadTemplateService templates;
    private final BatchStatusService batchStatus;

    public GlDataController(GlTabelRepository gl, DataScopeService scope, OtorisasiService otorisasi,
                            TUploadRepository uploads, UploadTemplateService templates, BatchStatusService batchStatus) {
        this.gl = gl; this.scope = scope; this.otorisasi = otorisasi;
        this.uploads = uploads; this.templates = templates; this.batchStatus = batchStatus;
    }

    /** Maker (GROUP_3): pemilik batch upload — hanya ia yang mendapat tombol Bulk Edit/Hapus. */
    private static boolean isMaker(AusyUserDetails me) {
        return me != null && me.getAuthorities().stream()
                .anyMatch(a -> "GROUP_3".equals(a.getAuthority()));
    }


    /**
     * List Data GL: batch upload yang BELUM diotorisasi (REQ_AUSY_00036 &amp; 00044). Batch yang sudah
     * di-approve pindah ke penampungan EXECUTE dan hilang dari sini. Tabel "Data GL Sudah
     * Diotorisasi" untuk checker dicabut (REQ_AUSY_00036 feedback), jadi maker dan checker melihat
     * tabel yang sama — masing-masing dibatasi per pasangan maker (BUG_AUSY_00138 / 00139).
     */
    @GetMapping("/gl-legacy")
    public String list(@AuthenticationPrincipal AusyUserDetails me, Model model) {
        model.addAttribute("isMaker", isMaker(me));
        List<Object[]> pending = pendingUploads(me);
        model.addAttribute("pendingUploads", pending);
        // REQ_AUSY_00053: kolom Status — Dikembalikan / Rejected (beserta catatan checker) per batch.
        model.addAttribute("statusBatch", batchStatus.statusMap());
        Set<String> pakets = new HashSet<>();
        pending.forEach(p -> pakets.add(String.valueOf(p[0])));
        model.addAttribute("uploadFiles", uploadFiles(pakets));
        return "gl-legacy/list";
    }

    /**
     * Batch upload yang menunggu otorisasi, disaring per maker (REQ_AUSY_00014). Kolom waktunya
     * (max(adddt)) bisa datang sebagai java.sql.Timestamp tergantung driver, sedangkan
     * #temporals di template hanya menerima LocalDateTime — diseragamkan di sini.
     */
    private List<Object[]> pendingUploads(AusyUserDetails me) {
        List<Object[]> out = new ArrayList<>(scope.filterArrays(me, gl.pendingPakets(), 1));
        // REQ_AUSY_00053: batch milik maker ini yang ditolak checker tetap tampil (PDF, Bulk Edit, Hapus).
        if (isMaker(me)) out.addAll(gl.rejectedPakets(me.getUsername()));
        for (Object[] p : out)
            if (p.length > 5 && p[5] instanceof java.sql.Timestamp ts) p[5] = ts.toLocalDateTime();
        return out;
    }

    /** Nama file Excel per nomor batch (REQ_AUSY_00036); upload lama tanpa tautan tidak muncul. */
    private Map<String, String> uploadFiles(Collection<String> pakets) {
        Map<String, String> out = new HashMap<>();
        if (pakets.isEmpty()) return out;
        for (TUpload t : uploads.findByNoPaketIn(pakets))
            if (t.getNoPaket() != null && t.getFileName() != null) out.put(t.getNoPaket(), t.getFileName());
        return out;
    }

    /** REQ_AUSY_00022 — maker menghapus batch upload miliknya yang belum diotorisasi checker. */
    @PostMapping("/gl-legacy/pending/{paket}/delete")
    public String withdrawPending(@PathVariable String paket, @AuthenticationPrincipal AusyUserDetails me,
                                  RedirectAttributes ra) {
        OtorisasiService.ExportResult r = otorisasi.withdraw(paket, me);
        ra.addFlashAttribute(r.ok() ? "ok" : "warn", r.message());
        return "redirect:/gl-legacy";
    }

    /**
     * REQ_AUSY_00037 &amp; 00057 — Bulk Edit per batch, langkah 1: unduh batch (milik maker sendiri,
     * belum diotorisasi atau ditolak checker) dalam template MNC yang sama dengan Upload Excel,
     * Sheet1 sudah terisi baris batch itu.
     */
    @GetMapping("/gl-legacy/pending/{paket}/bulk-export")
    public void bulkExportPending(@PathVariable String paket, @AuthenticationPrincipal AusyUserDetails me,
                                  HttpServletResponse resp) throws Exception {
        List<GlTabel> rows = me == null ? List.of() : gl.findBatchRows(paket, me.getUsername()).stream()
                .filter(GlDataController::bisaBulkEdit)
                .toList();
        if (rows.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Batch " + BatchLabel.label(paket) + " tidak ada di upload Anda yang belum diotorisasi.");
            return;
        }
        String tgl = paket.length() >= 8 && paket.substring(0, 8).chars().allMatch(Character::isDigit)
                ? "_" + paket.substring(6, 8) + "-" + paket.substring(4, 6) + "-" + paket.substring(0, 4) : "";
        templates.downloadBatch(resp, rows, "BulkEdit_Batch_" + BatchLabel.label(paket) + tgl + ".xlsx");
    }

    /**
     * REQ_AUSY_00037, 00047 &amp; 00057 — Bulk Edit per batch, langkah 2: unggah template MNC hasil
     * edit. Diperiksa dengan aturan yang sama dengan Upload Excel, lalu isi batch diganti (lihat
     * {@link OtorisasiService#bulkEditPending}).
     */
    @PostMapping("/gl-legacy/pending/{paket}/bulk-upload")
    public String bulkUploadPending(@PathVariable String paket, @RequestParam("file") MultipartFile file,
                                    @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        if (file == null || file.isEmpty()) { ra.addFlashAttribute("warn", "File belum dipilih."); return "redirect:/gl-legacy"; }
        try {
            OtorisasiService.ExportResult r = otorisasi.bulkEditPending(paket, me, file.getInputStream());
            ra.addFlashAttribute(r.ok() ? "ok" : "warn", r.message());
        } catch (Exception e) {
            ra.addFlashAttribute("warn", "Gagal membaca file Excel: " + e.getMessage());
        }
        return "redirect:/gl-legacy";
    }

    /** Baris batch yang menunggu otorisasi, atau yang ditolak checker (REQ_AUSY_00053). */
    static boolean bisaBulkEdit(GlTabel g) {
        return ("PENDING".equals(g.getStatusDual()) && Short.valueOf((short) 1).equals(g.getActive()))
                || OtorisasiService.REJECTED.equals(g.getStatusDual());
    }
}
