package com.mnc.ausy.web;

import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.GlExportService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Checker (④) authorizes maker-initiated GL export → ACCTXT sent to FTP. Part of Manajemen Otorisasi GL. */
@Controller
public class GlExportController {
    private final GlExportService glExport;

    public GlExportController(GlExportService glExport) { this.glExport = glExport; }

    @PostMapping("/gl-export/approve")
    public String approve(@RequestParam Long reqId, @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        String msg = glExport.approveExport(reqId, me);
        boolean ok = msg.startsWith("Otorisasi export berhasil") && !msg.contains("Gagal kirim ke FTP");
        ra.addFlashAttribute(ok ? "ok" : "warn", msg);
        return "redirect:/otorisasi-gl";
    }

    @PostMapping("/gl-export/reject")
    public String reject(@RequestParam Long reqId, @RequestParam(required=false) String note,
                         @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        String msg = glExport.rejectExport(reqId, me, note);
        ra.addFlashAttribute(msg.startsWith("Permintaan export ditolak") ? "ok" : "warn", msg);
        return "redirect:/otorisasi-gl";
    }
}
