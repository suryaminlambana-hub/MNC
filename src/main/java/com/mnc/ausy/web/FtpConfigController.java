package com.mnc.ausy.web;

import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.FtpAdminService;
import com.mnc.ausy.service.OtorisasiService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Admin (①) manages FTP server settings and the export transfer queue. */
@Controller
@RequestMapping("/admin/ftp")
public class FtpConfigController {
    private final FtpAdminService ftpAdmin;
    private final OtorisasiService otorisasi;

    public FtpConfigController(FtpAdminService ftpAdmin, OtorisasiService otorisasi) {
        this.ftpAdmin = ftpAdmin; this.otorisasi = otorisasi;
    }

    @GetMapping
    public String index(Model model) {
        model.addAttribute("cfg", ftpAdmin.getConfig());
        model.addAttribute("transfers", ftpAdmin.recentTransfers());
        return "admin/ftp";
    }

    @PostMapping("/save")
    public String save(@RequestParam(required=false) String host,
                       @RequestParam(required=false) Integer port,
                       @RequestParam(required=false) String user,
                       @RequestParam(required=false) String pass,
                       @RequestParam(required=false) String dir,
                       @RequestParam(defaultValue="false") boolean secure,
                       @RequestParam(defaultValue="false") boolean passive,
                       @RequestParam(defaultValue="false") boolean enabled,
                       @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", ftpAdmin.saveConfig(host, port, user, pass, dir, secure, passive, enabled, me.getUsername()));
        return "redirect:/admin/ftp";
    }

    @PostMapping("/test")
    public String test(RedirectAttributes ra) {
        String msg = ftpAdmin.testConnection();
        ra.addFlashAttribute(msg.startsWith("Koneksi FTP berhasil") ? "ok" : "warn", msg);
        return "redirect:/admin/ftp";
    }

    @PostMapping("/resend/{id}")
    public String resend(@PathVariable Long id, @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        String msg = otorisasi.resend(id, me.getUsername());
        ra.addFlashAttribute(msg.startsWith("Berhasil") ? "ok" : "warn", msg);
        return "redirect:/admin/ftp";
    }
}
