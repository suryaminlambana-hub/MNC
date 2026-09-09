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

    /**
     * Dual control wajib untuk semua perubahan Kode GL. Kalau group pengaju belum punya
     * Checker aktif, pengajuannya ditolak GlChangeService dengan pesan sendiri — tidak ada lagi
     * jalur "langsung terapkan" yang dulu aktif ketika group tidak punya approver (BUG_AUSY_00116).
     */
    private boolean needsApproval(AusyUserDetails me) { return true; }

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
        flash(ra, changes.submitGlCodeAdd(code, position, description, status, me, note));
        return "redirect:/gl-code";
    }

    @PostMapping("/edit")
    public String edit(@RequestParam Long id, @RequestParam(required=false) String code, @RequestParam(required=false) String position,
                       @RequestParam(required=false) String description, @RequestParam(defaultValue="1") Short status,
                       @RequestParam(required=false) String note,
                       @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        flash(ra, changes.submitGlCodeEdit(id, code, position, description, status, me, note));
        return "redirect:/gl-code";
    }

    @PostMapping("/delete")
    public String delete(@RequestParam Long id, @RequestParam(required=false) String note,
                         @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        flash(ra, changes.submitGlCodeDelete(id, me, note));
        return "redirect:/gl-code";
    }

    @PostMapping("/import")
    public String importCsv(@RequestParam("file") MultipartFile file, @RequestParam(required=false) String note,
                            @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        if (file == null || file.isEmpty()) {
            ra.addFlashAttribute("warn", "File CSV belum dipilih.");
            return "redirect:/gl-code";
        }
        try {
            GlCodeService.CsvParse p = service.parseCsv(file.getInputStream());
            // BUG_AUSY_00103: kalau tidak ada satu pun baris yang bisa dipakai, sebutkan sebabnya
            // (template salah / semua duplikat / file kosong) — jangan cuma "tidak ada baris baru".
            String issue = csvIssue(p);
            if (issue != null) {
                ra.addFlashAttribute("warn", issue);
                return "redirect:/gl-code";
            }
            GlChangeService.Res r = changes.submitGlCodeImport(p.rows(), me, note);
            // hitungan duplikat/invalid hanya relevan kalau pengajuannya diterima
            flash(ra, r.ok() ? new GlChangeService.Res(true, r.message()
                    + " (" + p.dup() + " duplikat, " + p.bad() + " tidak valid diabaikan)") : r);
            return "redirect:/gl-code";
        } catch (Exception e) {
            ra.addFlashAttribute("warn", "Gagal membaca CSV — pastikan file berformat CSV sesuai template"
                    + " (kolom: NoGL, Debit/Credit, Description).");
        }
        return "redirect:/gl-code";
    }

    /**
     * Why a CSV produced nothing importable, or null when it did. Splitting the cases is the whole
     * point of BUG_AUSY_00103: an invalid template used to be reported as "tidak ada baris baru".
     */
    private static String csvIssue(GlCodeService.CsvParse p) {
        if (!p.rows().isEmpty()) return null;
        if (p.bad() == 0 && p.dup() == 0)
            return "File CSV tidak berisi baris data — pastikan memakai template Kode GL"
                    + " (kolom: NoGL, Debit/Credit, Description).";
        if (p.dup() == 0)
            return "Format CSV tidak sesuai template: " + p.bad() + " baris tidak valid (NoGL harus angka dan"
                    + " Debit/Credit harus D atau C). Unduh template Kode GL lalu coba lagi.";
        if (p.bad() == 0)
            return "Semua " + p.dup() + " baris pada CSV sudah terdaftar sebagai Kode GL — tidak ada yang diimport.";
        return "Tidak ada baris yang bisa diimport: " + p.bad() + " baris tidak sesuai template dan "
                + p.dup() + " baris sudah terdaftar.";
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
        else if (err.equals("nocode")) ra.addFlashAttribute("warn", "NoGL wajib diisi.");
        else if (err.equals("nopos")) ra.addFlashAttribute("warn", "Debit/Credit wajib dipilih.");
        else if (err.equals("badcode")) ra.addFlashAttribute("warn",
                "NoGL hanya boleh berisi angka (tanpa huruf, spasi, atau tanda baca).");
        else ra.addFlashAttribute("warn", "Terjadi kesalahan.");
    }
}
