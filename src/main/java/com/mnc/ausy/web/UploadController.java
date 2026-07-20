package com.mnc.ausy.web;

import com.mnc.ausy.security.AusyUserDetails;
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
    private final UploadService service;
    public UploadController(UploadService service) { this.service = service; }

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
            r.message = "Gagal membaca file Excel: " + e.getMessage();
            model.addAttribute("result", r);
        }
        return "upload/index";
    }

    @GetMapping("/template")
    public void template(HttpServletResponse resp) throws Exception {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("d-MMM-yy", Locale.ENGLISH));
        try (Workbook wb = new XSSFWorkbook()) {
            wb.createSheet("Cover"); wb.createSheet("Info1"); wb.createSheet("Info2"); wb.createSheet("Info3");
            Sheet d = wb.createSheet("DATA");
            String[] h = {"Posting Date","D/C","Product Code","Product Name","Transaction Account","GL Name",
                "Transaction Description","Branch","Branch Name","Cost Center","Cost Center Name","Currency",
                "Foreign Curr","Transaction Amount","Flag","Responding Branch","Responding Branch Name"};
            Row hr = d.createRow(0);
            for (int i = 0; i < h.length; i++) hr.createCell(i).setCellValue(h[i]);
            String[][] rows = {
                {today,"D","20999154","BY KOMUNIKASI DATA","900","KANTOR PUSAT OPERASIONAL","BIAYA KOMUNIKASI DATA","44444","KANTOR PUSAT","01","FRONT LINE","IDR","IDR","5000000","","",""},
                {today,"D","20999155","BY LISTRIK DAN AIR","901","KANTOR PUSAT OPERASIONAL","BIAYA LISTRIK","44444","KANTOR PUSAT","01","FRONT LINE","IDR","IDR","2500000","","",""},
                {today,"C","10001000","KAS KANTOR PUSAT","100","KANTOR PUSAT OPERASIONAL","PEMBAYARAN KAS","44444","KANTOR PUSAT","01","FRONT LINE","IDR","IDR","4000000","","",""},
                {today,"C","10001000","KAS KANTOR PUSAT","100","KANTOR PUSAT OPERASIONAL","PEMBAYARAN KAS TAMBAHAN","44444","KANTOR PUSAT","01","FRONT LINE","IDR","IDR","3500000","","",""}
            };
            for (int r = 0; r < rows.length; r++) {
                Row rr = d.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) rr.createCell(c).setCellValue(rows[r][c]);
            }
            resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            resp.setHeader("Content-Disposition", "attachment; filename=\"template_upload.xlsx\"");
            OutputStream o = resp.getOutputStream();
            wb.write(o);
            o.flush();
        }
    }
}
