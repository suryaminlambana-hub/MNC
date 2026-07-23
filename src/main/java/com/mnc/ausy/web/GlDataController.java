package com.mnc.ausy.web;

import com.mnc.ausy.domain.GlTabel;
import com.mnc.ausy.repo.GlCodeRepository;
import com.mnc.ausy.repo.GlTabelRepository;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.AuditService;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Controller
public class GlDataController {
    private final GlTabelRepository gl;
    private final GlCodeRepository glCode;
    private final AuditService audit;

    public GlDataController(GlTabelRepository gl, GlCodeRepository glCode, AuditService audit) {
        this.gl = gl; this.glCode = glCode; this.audit = audit;
    }

    /** A GL row is "registered" when its NoGL + D/C exists in the GL Code master. */
    private Set<String> registeredKeys() {
        return glCode.findAll().stream()
                .map(c -> c.getCode() + "|" + c.getPosition())
                .collect(Collectors.toSet());
    }
    private static boolean isRegistered(Set<String> reg, GlTabel g) {
        return reg.contains(n(g.getGltrac()) + "|" + n(g.getGltrcd()));
    }
    private static String n(String s) { return s == null ? "" : s; }

    @GetMapping("/gl-legacy")
    public String list(Model model) {
        List<GlTabel> rows = gl.findAll();
        Set<String> reg = registeredKeys();
        long registeredCount = rows.stream().filter(r -> isRegistered(reg, r)).count();
        BigDecimal totalAmount = rows.stream()
                .map(r -> r.getGltram() == null ? BigDecimal.ZERO : r.getGltram())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        model.addAttribute("rows", rows);
        model.addAttribute("registered", reg);
        model.addAttribute("total", rows.size());
        model.addAttribute("totalRegistered", registeredCount);
        model.addAttribute("totalAmount", totalAmount);
        return "gl-legacy/list";
    }

    /** Export List GL to Excel — only rows whose GL is registered; unregistered auto-filtered out. */
    @GetMapping("/gl-legacy/export")
    public void export(@AuthenticationPrincipal AusyUserDetails me, HttpServletResponse resp) throws Exception {
        List<GlTabel> all = gl.findAll();
        Set<String> reg = registeredKeys();
        List<GlTabel> rows = all.stream().filter(r -> isRegistered(reg, r)).collect(Collectors.toList());

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("List GL");
            CellStyle bold = wb.createCellStyle();
            Font bf = wb.createFont(); bf.setBold(true); bold.setFont(bf);

            String[] head = {"No","Paket","NoGL","D/C","GL Name","Description","Amount","Tgl","Uploader","GLTRRF","Registered"};
            Row hr = sh.createRow(0);
            for (int i = 0; i < head.length; i++) { Cell c = hr.createCell(i); c.setCellValue(head[i]); c.setCellStyle(bold); }

            BigDecimal total = BigDecimal.ZERO;
            int r = 1;
            for (GlTabel g : rows) {
                Row row = sh.createRow(r);
                row.createCell(0).setCellValue(r);
                row.createCell(1).setCellValue(n(g.getNoPaket()));
                row.createCell(2).setCellValue(n(g.getGltrac()));
                row.createCell(3).setCellValue(n(g.getGltrcd()));
                row.createCell(4).setCellValue(n(g.getGlName()));
                row.createCell(5).setCellValue(n(g.getGltrds()));
                row.createCell(6).setCellValue(g.getGltram() == null ? 0d : g.getGltram().doubleValue());
                row.createCell(7).setCellValue(n(g.getGltrpd()));
                row.createCell(8).setCellValue(n(g.getGltrus()));
                row.createCell(9).setCellValue(n(g.getGltrrf()));
                row.createCell(10).setCellValue("Terdaftar");
                total = total.add(g.getGltram() == null ? BigDecimal.ZERO : g.getGltram());
                r++;
            }
            Row tr = sh.createRow(r);
            Cell tl = tr.createCell(5); tl.setCellValue("TOTAL (" + rows.size() + " data terdaftar)"); tl.setCellStyle(bold);
            Cell tv = tr.createCell(6); tv.setCellValue(total.doubleValue()); tv.setCellStyle(bold);
            for (int i = 0; i < head.length; i++) sh.autoSizeColumn(i);

            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            resp.setHeader("Content-Disposition", "attachment; filename=\"List_GL_" + ts + ".xlsx\"");
            wb.write(resp.getOutputStream());
            resp.getOutputStream().flush();
        }
        audit.transaction(me.getUsername(), "EXPORT_GL",
                "Export List GL ke Excel: " + rows.size() + " baris terdaftar (dari " + all.size() + " total)");
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
