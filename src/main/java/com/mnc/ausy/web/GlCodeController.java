package com.mnc.ausy.web;

import com.mnc.ausy.domain.GlCode;
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
 * Manajemen Kode GL — master nomor akun GL, seluruhnya di bawah dual control.
 *
 * BUG_AUSY_00170 &amp; BUG_AUSY_00171: kolom Debit/Credit dilepas. Sebuah kode dikenali dari
 * nomornya saja, mengikuti daftar COA milik MNC; sisi D/C ditentukan baris transaksinya.
 *
 * BUG_AUSY_00140 sempat menghapus halaman ini beserta aksi tambah/ubah/hapus/import.
 * BUG_AUSY_00159 mengembalikannya seperti semula: migrasi V35 menyalakan lagi baris menu 21
 * ({@code /gl-code}), dan menu yang hidup tanpa handler membuat klik dari sidebar berakhir di
 * halaman error bawaan Spring — jadi halaman beserta aksinya harus ikut kembali.
 *
 * Add/Edit/Delete/Import hanya membuat permintaan perubahan; tabel master baru ditulis saat
 * Checker menyetujui (tab Otorisasi Perubahan GL).
 *
 * BUG_AUSY_00165 &amp; BUG_AUSY_00166: import CSV pindah ke halamannya sendiri, "Upload Kode GL"
 * ({@code /gl-code/upload}). Halaman manajemen tinggal mengurus kode satu per satu; pemuatan
 * massal saat migrasi data berdiri terpisah dan punya baris menunya sendiri, sehingga haknya
 * bisa diberikan tanpa ikut membuka tambah/ubah/hapus.
 */
@Controller
@RequestMapping("/gl-code")
public class GlCodeController {
    private final GlCodeService service;
    private final ExcelExportService excel;
    private final AuditService audit;
    private final GlChangeService changes;

    public GlCodeController(GlCodeService service, ExcelExportService excel, AuditService audit,
                            GlChangeService changes) {
        this.service = service; this.excel = excel; this.audit = audit; this.changes = changes;
    }

    /**
     * Dual control wajib untuk semua perubahan Kode GL. Kalau group pengaju belum punya Checker
     * aktif, pengajuannya ditolak GlChangeService dengan pesannya sendiri — tidak ada lagi jalur
     * "langsung terapkan" seperti dulu (BUG_AUSY_00116).
     */
    private boolean needsApproval(AusyUserDetails me) { return true; }

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

    /**
     * Export master Kode GL ke Excel — satu alamat saja, di bawah {@code /gl-code/list} supaya
     * hak aksesnya ikut menu List Kode GL.
     *
     * Alamat kedua {@code /gl-code/export} dilepas bersama tombolnya (BUG_AUSY_00177): unduhannya
     * kini hanya ada di List Kode GL, dan endpoint yang tidak dipakai tombol mana pun tidak perlu
     * ditinggalkan menganggur.
     */
    @GetMapping("/list/export")
    public void export(@AuthenticationPrincipal AusyUserDetails me, HttpServletResponse resp) throws Exception {
        List<GlCode> codes = service.listWithUsage();
        List<String> head = List.of("No","NoGL","Description","Status","Pemakaian");
        List<List<Object>> data = new ArrayList<>();
        int no = 1;
        for (GlCode g : codes) {
            data.add(Arrays.asList(no++, g.getCode(),
                    g.getDescription() == null ? "" : g.getDescription(),
                    (g.getStatus() != null && g.getStatus() == 1) ? "Active" : "Non Active",
                    g.isUsed() ? "Terpakai" : "Belum dipakai"));
        }
        excel.download(resp, "GL Code", "GL_Code", head, data, false);
        audit.transaction(me.getUsername(), "EXPORT_GLCODE", "Export master GL Code ke Excel: " + codes.size() + " kode");
    }

    @PostMapping("/add")
    public String add(@RequestParam String code,
                      @RequestParam(required=false) String description, @RequestParam(defaultValue="1") Short status,
                      @RequestParam(required=false) String note,
                      @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        flash(ra, changes.submitGlCodeAdd(code, description, status, me, note));
        return "redirect:/gl-code";
    }

    @PostMapping("/edit")
    public String edit(@RequestParam Long id, @RequestParam(required=false) String code,
                       @RequestParam(required=false) String description, @RequestParam(defaultValue="1") Short status,
                       @RequestParam(required=false) String note,
                       @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        flash(ra, changes.submitGlCodeEdit(id, code, description, status, me, note));
        return "redirect:/gl-code";
    }

    @PostMapping("/delete")
    public String delete(@RequestParam Long id, @RequestParam(required=false) String note,
                         @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        flash(ra, changes.submitGlCodeDelete(id, me, note));
        return "redirect:/gl-code";
    }

    /** Halaman "Upload Kode GL" — pemuatan massal dari CSV, terpisah dari halaman manajemen. */
    @GetMapping("/upload")
    public String uploadPage(@AuthenticationPrincipal AusyUserDetails me, Model model) {
        model.addAttribute("needApproval", needsApproval(me));
        model.addAttribute("total", service.listWithUsage().size());
        return "gl-code/upload";
    }

    @PostMapping("/upload/import")
    public String importCsv(@RequestParam("file") MultipartFile file, @RequestParam(required=false) String note,
                            @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        if (file == null || file.isEmpty()) {
            ra.addFlashAttribute("warn", "File CSV belum dipilih.");
            return "redirect:/gl-code/upload";
        }
        try {
            GlCodeService.CsvParse p = service.parseCsv(file.getInputStream());
            // BUG_AUSY_00103: kalau tidak ada satu pun baris yang bisa dipakai, sebutkan sebabnya
            // (template salah / semua duplikat / file kosong) — jangan cuma "tidak ada baris baru".
            String issue = csvIssue(p);
            if (issue != null) {
                ra.addFlashAttribute("warn", issue);
                return "redirect:/gl-code/upload";
            }
            GlChangeService.Res r = changes.submitGlCodeImport(p.rows(), me, note);
            // hitungan duplikat/invalid hanya relevan kalau pengajuannya diterima
            flash(ra, r.ok() ? new GlChangeService.Res(true, r.message()
                    + " (" + p.dup() + " duplikat, " + p.bad() + " tidak valid diabaikan)") : r);
            return "redirect:/gl-code/upload";
        } catch (Exception e) {
            ra.addFlashAttribute("warn", "Gagal membaca CSV — pastikan file berformat CSV sesuai template"
                    + " (kolom: NoGL, Description).");
        }
        return "redirect:/gl-code/upload";
    }

    /**
     * Why a CSV produced nothing importable, or null when it did. Splitting the cases is the whole
     * point of BUG_AUSY_00103: an invalid template used to be reported as "tidak ada baris baru".
     */
    private static String csvIssue(GlCodeService.CsvParse p) {
        if (!p.rows().isEmpty()) return null;
        if (p.bad() == 0 && p.dup() == 0)
            return "File CSV tidak berisi baris data — pastikan memakai template Kode GL"
                    + " (kolom: NoGL, Description).";
        if (p.dup() == 0)
            return "Format CSV tidak sesuai template: " + p.bad() + " baris tidak valid (NoGL harus angka)."
                    + " Unduh template Kode GL lalu coba lagi.";
        if (p.bad() == 0)
            return "Semua " + p.dup() + " baris pada CSV sudah terdaftar sebagai Kode GL — tidak ada yang diimport.";
        return "Tidak ada baris yang bisa diimport: " + p.bad() + " baris tidak sesuai template dan "
                + p.dup() + " baris sudah terdaftar.";
    }

    /** Template CSV, di bawah /gl-code/upload supaya haknya ikut menu Upload Kode GL. */
    @GetMapping("/upload/template")
    public void template(HttpServletResponse resp) throws Exception {
        resp.setContentType("text/csv; charset=utf-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"template_gl_code.csv\"");
        OutputStream o = resp.getOutputStream();
        // Dua kolom saja sejak BUG_AUSY_00171 — tanpa Debit/Credit.
        o.write(("NoGL,Description\n900,KANTOR PUSAT OPERASIONAL\n901,BIAYA OPERASIONAL\n100,KAS KANTOR PUSAT\n")
                .getBytes(StandardCharsets.UTF_8));
        o.flush();
    }

    private void flash(RedirectAttributes ra, GlChangeService.Res r) {
        ra.addFlashAttribute(r.ok() ? "ok" : "warn", r.message());
    }
}
