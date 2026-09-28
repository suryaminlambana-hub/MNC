package com.mnc.ausy.web;

import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.GlExportService;
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

    public GlExportController(GlExportService glExport) { this.glExport = glExport; }

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
    public String returnList(Model model) {
        model.addAttribute("items", glExport.returnable());
        return "gl-export/return-list";
    }

    @PostMapping("/gl-export/return")
    public String returnExport(@RequestParam Long reqId, @RequestParam(required = false) String note,
                               @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        String msg = glExport.returnExport(reqId, me, note);
        ra.addFlashAttribute(msg.startsWith("Export ditarik kembali") ? "ok" : "warn", msg);
        return "redirect:/gl-export/return-list";
    }
}
