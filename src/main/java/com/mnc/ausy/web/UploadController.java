package com.mnc.ausy.web;

import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.UploadTemplateService;
import com.mnc.ausy.service.UploadResult;
import com.mnc.ausy.service.UploadService;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.OutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Controller
@RequestMapping("/upload")
public class UploadController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UploadController.class);
    private final UploadTemplateService templates;
    private final UploadService service;
    public UploadController(UploadService service, UploadTemplateService templates) {
        this.templates = templates; this.service = service; }

    @GetMapping
    public String page() { return "upload/index"; }

    @PostMapping("/process")
    public String process(@RequestParam("file") MultipartFile file,
                          @AuthenticationPrincipal AusyUserDetails me, Model model) {
        try {
            UploadResult r = service.process(file.getInputStream(), file.getOriginalFilename(), me.getUsername());
            model.addAttribute("result", r);
        } catch (Exception e) {
            UploadResult r = new UploadResult();
            // REQ_AUSY_00067: galat asli (bisa memuat SQL) hanya ke log, bukan ke layar.
            log.error("Upload Excel gagal: user={} file={}", me.getUsername(), file.getOriginalFilename(), e);
            r.message = UploadService.pesanGagal(e);
            model.addAttribute("result", r);
        }
        return "upload/index";
    }

    @GetMapping("/template")
    public void template(HttpServletResponse resp) throws Exception { templates.download(resp); }
}
