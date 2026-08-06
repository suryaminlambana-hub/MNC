package com.mnc.ausy.web;

import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.OtorisasiService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.file.Files;
import java.nio.file.Path;

@Controller
@RequestMapping("/otorisasi")
public class OtorisasiController {
    private final OtorisasiService service;
    public OtorisasiController(OtorisasiService service) { this.service = service; }

    @GetMapping
    public String list(@AuthenticationPrincipal AusyUserDetails me, Model model) {
        model.addAttribute("pakets", service.pendingFor(me.getIdGroup()));
        return "otorisasi/index";
    }

    @PostMapping("/process")
    public String process(@RequestParam String paket, @RequestParam String inputer,
                          @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        OtorisasiService.ExportResult r = service.export(paket, inputer, me.getIdGroup(), me.getUsername());
        ra.addFlashAttribute(r.ok() ? "ok" : "warn", r.message());
        if (r.ok() && r.file() != null) ra.addFlashAttribute("file", r.file());   // enable Download button
        return "redirect:/otorisasi-gl";
    }

    @PostMapping("/reject")
    public String reject(@RequestParam String paket, @RequestParam String inputer, @RequestParam(required=false) String note,
                         @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        OtorisasiService.ExportResult r = service.reject(paket, inputer, me.getIdGroup(), me.getUsername(), note);
        ra.addFlashAttribute(r.ok() ? "ok" : "warn", r.message());
        return "redirect:/otorisasi-gl";
    }

    /** Download a generated GLTRRF/ACCTXT text file from the txt dir. */
    @GetMapping("/download/{name:.+}")
    public ResponseEntity<Resource> download(@PathVariable String name) throws Exception {
        Path p = service.txtFile(name);
        if (!Files.exists(p)) return ResponseEntity.notFound().build();
        Resource res = new UrlResource(p.toUri());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + p.getFileName() + "\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(res);
    }
}
