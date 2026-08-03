package com.mnc.ausy.web;

import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.GlChangeService;
import com.mnc.ausy.service.OtorisasiService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Manajemen Otorisasi GL — satu halaman: otorisasi upload paket (export GLTRRF) + otorisasi perubahan GL. */
@Controller
public class OtorisasiGlController {
    private final OtorisasiService otorisasi;
    private final GlChangeService glChange;

    public OtorisasiGlController(OtorisasiService otorisasi, GlChangeService glChange) {
        this.otorisasi = otorisasi; this.glChange = glChange;
    }

    @GetMapping("/otorisasi-gl")
    public String page(@AuthenticationPrincipal AusyUserDetails me, Model model) {
        model.addAttribute("pakets", otorisasi.pendingFor(me.getIdGroup()));
        model.addAttribute("items", glChange.pendingReviewFor(me.getIdGroup()));
        return "otorisasi-gl/index";
    }
}
