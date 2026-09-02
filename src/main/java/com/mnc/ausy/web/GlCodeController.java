package com.mnc.ausy.web;

import com.mnc.ausy.domain.GlCode;
import com.mnc.ausy.service.ApprovalService;
import com.mnc.ausy.service.AuditService;
import com.mnc.ausy.service.ExcelExportService;
import com.mnc.ausy.service.GlChangeService;
import com.mnc.ausy.service.GlCodeService;
import com.mnc.ausy.security.AusyUserDetails;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Manage GL Code. Under dual control (BUG_AUSY_00080): when the maker's group has a checker on
 * the Approval Group matrix, Add/Edit/Delete/Import only create a change request — the master
 * table is written when the Checker approves it (tab Otorisasi Perubahan GL).
 */
@Controller
@RequestMapping("/gl-code")
public class GlCodeController {
    private final GlCodeService service;
    private final ExcelExportService excel;
    private final AuditService audit;
    private final GlChangeService changes;
    private final ApprovalService approval;
    public GlCodeController(GlCodeService service, ExcelExportService excel, AuditService audit,
                            GlChangeService changes, ApprovalService approval) {
        this.service = service; this.excel = excel; this.audit = audit;
        this.changes = changes; this.approval = approval;
    }

    private boolean needsApproval(AusyUserDetails me) {
        return approval.requiresApproval(me == null ? null : me.getIdGroup());
    }

    /** Export the GL Code master (registered GL codes) to Excel. */
    @GetMapping("/export")
    public void export(@AuthenticationPrincipal AusyUserDetails me, HttpServletResponse resp) throws Exception {
        List<GlCode> codes = service.listWithUsage();
        List<String> head = List.of("No","NoGL","Debit/Credit","Description","Status","Pemakaian");
        List<List<Object>> data = new ArrayList<>();
        int no = 1;
        for (GlCode g : codes) {
            data.add(Arrays.asList(no++, g.getCode(),
                    "C".equals(g.getPosition()) ? "Credit" : "Debit",
                    g.getDescription() == null ? "" : g.getDescription(),
                    (g.getStatus() != null && g.getStatus() == 1) ? "Active" : "Non Active",
                    g.isUsed() ? "Terpakai" : "Belum dipakai"));
        }
        excel.download(resp, "GL Code", "GL_Code", head, data, false);
        audit.transaction(me.getUsername(), "EXPORT_GLCODE", "Export master GL Code ke Excel: " + codes.size() + " kode");
    }

    @GetMapping
    public String manage(@AuthenticationPrincipal AusyUserDetails me, Model model) {
        model.addAttribute("rows", service.listWithUsage());
        model.addAttribute("needApproval", needsApproval(me));
        model.addAttribute("pendingIds", changes.pendingGlCodeIds());
        return "gl-code/manage";
    }

    @GetMapping("/list")
    public String list(Model model) {
        model.addAttribute("rows", service.listWithUsage());
        return "gl-code/list";
    }

    @PostMapping("/add")
    public String add(@RequestParam String code, @RequestParam String position,
                      @RequestParam(required=false) String description, @RequestParam(defaultValue="1") Short status,
                      @RequestParam(required=false) String note,
                      @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        if (needsApproval(me)) {
            flash(ra, changes.submitGlCodeAdd(code, position, description, status, me, note));
        } else {
            String err = service.add(code, position, description, status, me.getUsername());
            flash(ra, err, "Kode GL ditambahkan.");
        }
        return "redirect:/gl-code";
    }

    @PostMapping("/edit")
    public String edit(@RequestParam Long id, @RequestParam(required=false) String code, @RequestParam(required=false) String position,
                       @RequestParam(required=false) String description, @RequestParam(defaultValue="1") Short status,
                       @RequestParam(required=false) String note,
                       @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        if (needsApproval(me)) {
            flash(ra, changes.submitGlCodeEdit(id, code, position, description, status, me, note));
            return "redirect:/gl-code";
        }
        try {
            String err = service.edit(id, code, position, description, status, me.getUsername());
            flash(ra, err, "Kode GL diperbarui.");
        } catch (GlCodeService.DuplicateGl e) {
            ra.addFlashAttribute("warn", "Kombinasi NoGL + Debit/Credit sudah terdaftar.");
        }
        return "redirect:/gl-code";
    }

    @PostMapping("/delete")
    public String delete(@RequestParam Long id, @RequestParam(required=false) String note,
                         @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        if (needsApproval(me)) flash(ra, changes.submitGlCodeDelete(id, me, note));
        else flash(ra, service.delete(id), "Kode GL dihapus.");
        return "redirect:/gl-code";
    }

    @PostMapping("/import")
    public String importCsv(@RequestParam("file") MultipartFile file, @RequestParam(required=false) String note,
                            @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        try {
            if (needsApproval(me)) {
                GlCodeService.CsvParse p = service.parseCsv(file.getInputStream());
                GlChangeService.Res r = changes.submitGlCodeImport(p.rows(), me, note);
                // hitungan duplikat/invalid hanya relevan kalau pengajuannya diterima
                flash(ra, r.ok() ? new GlChangeService.Res(true, r.message()
                        + " (" + p.dup() + " duplikat, " + p.bad() + " tidak valid diabaikan)") : r);
                return "redirect:/gl-code";
            }
            int[] r = service.importCsv(file.getInputStream(), me.getUsername());
            ra.addFlashAttribute("ok", "Import selesai — " + r[0] + " ditambahkan, " + r[1] + " duplikat, " + r[2] + " tidak valid.");
        } catch (Exception e) {
            ra.addFlashAttribute("warn", "Gagal membaca CSV.");
        }
        return "redirect:/gl-code";
    }

    @GetMapping("/template")
    public void template(HttpServletResponse resp) throws Exception {
        resp.setContentType("text/csv; charset=utf-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"template_gl_code.csv\"");
        OutputStream o = resp.getOutputStream();
        o.write(("NoGL,Debit/Credit,Description\n900,D,KANTOR PUSAT OPERASIONAL\n901,C,BIAYA OPERASIONAL\n100,D,KAS KANTOR PUSAT\n100,C,KAS KANTOR PUSAT\n").getBytes(StandardCharsets.UTF_8));
        o.flush();
    }

    private void flash(RedirectAttributes ra, GlChangeService.Res r) {
        ra.addFlashAttribute(r.ok() ? "ok" : "warn", r.message());
    }

    private void flash(RedirectAttributes ra, String err, String okMsg) {
        if (err == null) ra.addFlashAttribute("ok", okMsg);
        else if (err.equals("exists")) ra.addFlashAttribute("warn", "Kombinasi NoGL + Debit/Credit sudah terdaftar.");
        else if (err.equals("inuse")) ra.addFlashAttribute("warn", "Kode GL sudah dipakai pada transaksi, tidak dapat dihapus.");
        else ra.addFlashAttribute("warn", "Terjadi kesalahan.");
    }
}
