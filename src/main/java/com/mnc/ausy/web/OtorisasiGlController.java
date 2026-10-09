package com.mnc.ausy.web;

import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.GlChangeService;
import com.mnc.ausy.service.GlExportService;
import com.mnc.ausy.service.OtorisasiService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Manajemen Otorisasi GL — satu halaman: otorisasi upload paket + otorisasi perubahan GL + otorisasi export GL. */
@Controller
public class OtorisasiGlController {
    private final OtorisasiService otorisasi;
    private final GlChangeService glChange;
    private final GlExportService glExport;

    public OtorisasiGlController(OtorisasiService otorisasi, GlChangeService glChange, GlExportService glExport) {
        this.otorisasi = otorisasi; this.glChange = glChange; this.glExport = glExport;
    }

    @GetMapping("/otorisasi-gl")
    public String page(@AuthenticationPrincipal AusyUserDetails me, Model model) {
        model.addAttribute("pakets", otorisasi.pendingFor(me));
        // REQ_AUSY_00049: otorisasi Kode GL dipisah dari Kelola Data GL, masing-masing tab sendiri.
        var items = glChange.pendingReviewFor(me);
        model.addAttribute("itemsGl", items.stream()
                .filter(it -> !GlChangeService.T_GL_CODE.equals(it.cr().getTarget())).toList());
        model.addAttribute("itemsCode", items.stream()
                .filter(it -> GlChangeService.T_GL_CODE.equals(it.cr().getTarget())).toList());
        model.addAttribute("exportReqs", glExport.pendingExportFor(me));
        return "otorisasi-gl/index";
    }
}
