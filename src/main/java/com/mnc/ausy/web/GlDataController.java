package com.mnc.ausy.web;

import com.mnc.ausy.repo.GlTabelRepository;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.AuditService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class GlDataController {
    private final GlTabelRepository gl;
    private final AuditService audit;
    public GlDataController(GlTabelRepository gl, AuditService audit) { this.gl = gl; this.audit = audit; }

    @GetMapping("/gl-legacy")
    public String list(Model model) {
        model.addAttribute("rows", gl.findAll());
        return "gl-legacy/list";
    }

    @GetMapping("/gl-delete")
    public String deletePage(Model model) {
        model.addAttribute("pakets", gl.pendingPakets());
        return "gl-delete/index";
    }

    @PostMapping("/gl-delete/process")
    public String delete(@RequestParam String paket, @RequestParam String inputer,
                         @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        gl.deletePaket(paket, inputer);
        audit.transaction(me.getUsername(), "DELETE_PAKET", "Paket " + paket + " (maker=" + inputer + ") dihapus");
        ra.addFlashAttribute("ok", "Paket " + paket + " berhasil dihapus.");
        return "redirect:/gl-delete";
    }
}
