package com.mnc.ausy.web;

import com.mnc.ausy.domain.GlTabel;
import com.mnc.ausy.repo.GlCodeRepository;
import com.mnc.ausy.repo.GlTabelRepository;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.AuditService;
import com.mnc.ausy.service.ExcelExportService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Controller
public class GlDataController {
    private final GlTabelRepository gl;
    private final GlCodeRepository glCode;
    private final ExcelExportService excel;
    private final AuditService audit;

    public GlDataController(GlTabelRepository gl, GlCodeRepository glCode, ExcelExportService excel, AuditService audit) {
        this.gl = gl; this.glCode = glCode; this.excel = excel; this.audit = audit;
    }

    /** A GL row is "registered" when its NoGL + D/C exists in the GL Code master. */
    private Set<String> registeredKeys() {
        return glCode.findAll().stream().map(c -> c.getCode() + "|" + c.getPosition()).collect(Collectors.toSet());
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

        List<String> head = List.of("No","ID","Paket","NoGL","D/C","GL Name","Description","Amount","Tgl","Uploader","GLTRRF","Registered");
        List<List<Object>> data = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int no = 1;
        for (GlTabel g : rows) {
            data.add(Arrays.asList(no++, g.getId(), n(g.getNoPaket()), n(g.getGltrac()), n(g.getGltrcd()), n(g.getGlName()),
                    n(g.getGltrds()), g.getGltram() == null ? 0d : g.getGltram(), n(g.getGltrpd()),
                    n(g.getGltrus()), n(g.getGltrrf()), "Terdaftar"));
            total = total.add(g.getGltram() == null ? BigDecimal.ZERO : g.getGltram());
        }
        data.add(Arrays.asList("", "", "", "", "", "", "TOTAL (" + rows.size() + " data terdaftar)", total, "", "", "", ""));
        excel.download(resp, "List GL", "List_GL", head, data, true);
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
