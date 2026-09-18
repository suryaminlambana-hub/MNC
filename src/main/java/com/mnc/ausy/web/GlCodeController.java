package com.mnc.ausy.web;

import com.mnc.ausy.domain.GlCode;
import com.mnc.ausy.service.AuditService;
import com.mnc.ausy.service.ExcelExportService;
import com.mnc.ausy.service.GlCodeService;
import com.mnc.ausy.security.AusyUserDetails;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Kode GL — <b>baca saja</b>.
 *
 * BUG_AUSY_00140: menu "Manajemen Kode GL" beserta seluruh aksinya (tambah/ubah/hapus/import)
 * dihapus. Maker tidak berwenang mengubah parameter Kode GL, dan setelah menunya ditiadakan
 * endpoint-endpoint itu tidak boleh ditinggalkan menganggur: baris menu pemiliknya sudah tidak
 * aktif, sehingga URL yang tersisa tanpa pemilik justru akan terbuka untuk semua yang login.
 * Yang tersisa hanya halaman "List Kode GL" dan unduhan Excel-nya.
 *
 * Permintaan perubahan Kode GL yang sudah terlanjur diajukan tetap bisa diputuskan Checker —
 * penerapannya ada di GlChangeService, bukan di sini.
 */
@Controller
@RequestMapping("/gl-code")
public class GlCodeController {
    private final GlCodeService service;
    private final ExcelExportService excel;
    private final AuditService audit;

    public GlCodeController(GlCodeService service, ExcelExportService excel, AuditService audit) {
        this.service = service; this.excel = excel; this.audit = audit;
    }

    @GetMapping("/list")
    public String list(Model model) {
        model.addAttribute("rows", service.listWithUsage());
        return "gl-code/list";
    }

    /**
     * Export master Kode GL ke Excel. Alamatnya di bawah {@code /gl-code/list} supaya hak aksesnya
     * ikut menu List Kode GL — di bawah {@code /gl-code} saja endpoint ini tidak lagi dimiliki
     * menu mana pun setelah menu 21 dinonaktifkan.
     */
    @GetMapping("/list/export")
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
}
