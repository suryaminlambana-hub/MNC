package com.mnc.ausy.web;

import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.GlExportService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Checker (④) authorizes maker-initiated GL export → ACCTXT sent to FTP. Separate menu from otorisasi upload. */
@Controller
public class GlExportController {
    private final GlExportService glExport;

    public GlExportController(GlExportService glExport) { this.glExport = glExport; }

    @GetMapping("/otorisasi-export")
    public String page(@AuthenticationPrincipal AusyUserDetails me, Model model) {
        model.addAttribute("exportReqs", glExport.pendingExportFor(me.getIdGroup()));
        return "otorisasi-export/index";
    }

    @PostMapping("/gl-export/approve")
    public String approve(@RequestParam Long reqId, @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        String msg = glExport.approveExport(reqId, me.getIdGroup(), me.getUsername());
        ra.addFlashAttribute(msg.startsWith("Otorisasi export berhasil") ? "ok" : "warn", msg);
        return "redirect:/otorisasi-export";
    }

    @PostMapping("/gl-export/reject")
    public String reject(@RequestParam Long reqId, @RequestParam(required=false) String note,
                         @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        String msg = glExport.rejectExport(reqId, me.getIdGroup(), me.getUsername(), note);
        ra.addFlashAttribute(msg.startsWith("Permintaan export ditolak") ? "ok" : "warn", msg);
        return "redirect:/otorisasi-export";
    }
}
