package com.mnc.ausy.web;

import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.OtorisasiService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/otorisasi")
public class OtorisasiController {
    private final OtorisasiService service;
    public OtorisasiController(OtorisasiService service) { this.service = service; }

    @GetMapping
    public String list(@AuthenticationPrincipal AusyUserDetails me, Model model) {
        model.addAttribute("pakets", service.pendingFor(me));
        return "otorisasi/index";
    }

    @PostMapping("/process")
    public String process(@RequestParam String paket, @RequestParam String inputer,
                          @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        OtorisasiService.ExportResult r = service.export(paket, inputer, me);
        ra.addFlashAttribute(r.ok() ? "ok" : "warn", r.message());
        return "redirect:/otorisasi-gl";
    }

    @PostMapping("/reject")
    public String reject(@RequestParam String paket, @RequestParam String inputer, @RequestParam(required=false) String note,
                         @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        OtorisasiService.ExportResult r = service.reject(paket, inputer, me, note);
        ra.addFlashAttribute(r.ok() ? "ok" : "warn", r.message());
        return "redirect:/otorisasi-gl";
    }
}
