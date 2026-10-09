package com.mnc.ausy.web;

import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.domain.GlExportRequest;
import com.mnc.ausy.service.AcctxtGenerateService;
import com.mnc.ausy.service.AuditService;
import com.mnc.ausy.service.GlExportService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PathVariable;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Checker (④) authorizes maker-initiated GL export → ACCTXT written to the export folder. Part of Manajemen Otorisasi GL. */
@Controller
public class GlExportController {
    private final GlExportService glExport;
    private final AcctxtGenerateService acctxt;
    private final AuditService audit;

    public GlExportController(GlExportService glExport, AcctxtGenerateService acctxt, AuditService audit) {
        this.glExport = glExport; this.acctxt = acctxt; this.audit = audit;
    }

    @PostMapping("/gl-export/approve")
    public String approve(@RequestParam Long reqId, @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        GlExportService.ApproveResult r = glExport.approveExport(reqId, me);
        ra.addFlashAttribute(r.ok() ? "ok" : "warn", r.message());
        return "redirect:/otorisasi-gl";
    }

    @PostMapping("/gl-export/reject")
    public String reject(@RequestParam Long reqId, @RequestParam(required=false) String note,
                         @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        String msg = glExport.rejectExport(reqId, me, note);
        ra.addFlashAttribute(msg.startsWith("Permintaan export ditolak") ? "ok" : "warn", msg);
        return "redirect:/otorisasi-gl";
    }

    /**
     * Daftar export yang sudah di-approve tapi filenya belum digenerate admin, dengan tombol
     * tarik kembali (BUG_AUSY_00157). Sengaja halaman sendiri, bukan tombol di Audit Trail:
     * audit trail tetap murni catatan, tidak dipakai mengubah data.
     */
    @GetMapping("/gl-export/return-list")
    public String returnList(@AuthenticationPrincipal AusyUserDetails me, Model model) {
        // Disaring sesuai wewenang checker ini (BUG_AUSY_00176) — daftarnya harus memperlihatkan
        // persis apa yang tombolnya sanggup kerjakan.
        model.addAttribute("items", glExport.returnable(me));
        return "gl-export/return-list";
    }

    /**
     * REQ_AUSY_00028 — unduh pratinjau ACCTXT satu export dari halaman Return Export GL.
     *
     * Hanya export yang memang tampil di daftar checker ini (disaring {@code returnable(me)}),
     * jadi nomor CR milik maker lain menghasilkan 404. Alamatnya di bawah
     * {@code /gl-export/return-list} supaya hak aksesnya mengikuti menu Return Export GL.
     */
    @GetMapping("/gl-export/return-list/{reqId}/acctxt")
    public void previewAcctxt(@PathVariable Long reqId, @AuthenticationPrincipal AusyUserDetails me,
                              HttpServletResponse resp) throws Exception {
        GlExportRequest req = glExport.returnable(me).stream()
                .filter(r -> reqId.equals(r.getId())).findFirst().orElse(null);
        if (req == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Export CR#" + reqId + " tidak ada di daftar Return Anda.");
            return;
        }
        byte[] isi = acctxt.previewOf(req).getBytes(StandardCharsets.UTF_8);
        resp.setContentType("text/plain; charset=UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"ACCTXT_pratinjau_CR" + reqId + ".txt\"");
        resp.setContentLength(isi.length);
        resp.getOutputStream().write(isi);
        audit.transaction(me.getUsername(), "EXPORT_ACCTXT_PREVIEW",
                "Unduh pratinjau ACCTXT export CR#" + reqId + " (maker=" + req.getRequestedBy() + ")");
    }

    @PostMapping("/gl-export/return")
    public String returnExport(@RequestParam Long reqId, @RequestParam(required = false) String note,
                               @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        String msg = glExport.returnExport(reqId, me, note);
        ra.addFlashAttribute(msg.startsWith("Data dikembalikan") ? "ok" : "warn", msg);
        return "redirect:/gl-export/return-list";
    }
}
