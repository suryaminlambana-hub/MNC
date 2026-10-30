package com.mnc.ausy.web;

import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.ExcelExportService;
import com.mnc.ausy.service.GlChangeService;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

/** Workflow 8.3 — Update GL: maker page (/gl-data) + checker review (/gl-change/review). */
@Controller
public class GlChangeController {
    private static final List<String> BULK_HEAD = List.of("ID","GL Code","D/C","Amount","Description","Batch");
    private final GlChangeService service;
    private final ExcelExportService excel;

    public GlChangeController(GlChangeService service, ExcelExportService excel) {
        this.service = service; this.excel = excel;
    }

    // ---- Maker ----
    /**
     * Kelola Data GL (REQ_AUSY_00046): hanya data manual yang BELUM final, dalam SATU tabel
     * Pengajuan (feedback): pengajuan Tambah/Ubah milik maker ini yang menunggu checker — termasuk
     * data yang dikembalikan dari penampungan, yang otomatis diajukan ulang. Selama menunggu, maker
     * tetap bisa Ubah (isi pengajuan langsung berganti di sisi checker) atau Hapus.
     * Setelah checker meng-approve, datanya masuk penampungan EXECUTE dan hilang dari sini.
     */
    @GetMapping("/gl-data")
    public String dataPage(@AuthenticationPrincipal AusyUserDetails me, Model model) {
        model.addAttribute("items", service.pengajuanOf(me));
        model.addAttribute("fields", GlChangeService.FIELDS);
        return "gl-data/index";
    }

    /** REQ_AUSY_00046 (feedback): ubah pengajuan yang masih menunggu checker. */
    @PostMapping("/gl-data/pending/update")
    public String updatePending(@RequestParam Long crId, @RequestParam Map<String,String> form,
                                @RequestParam(required=false) String note,
                                @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        GlChangeService.Res r = service.updatePending(crId, form, me, note);
        ra.addFlashAttribute(r.ok() ? "ok" : "warn", r.message());
        return "redirect:/gl-data";
    }

    /** REQ_AUSY_00046 (feedback): hapus pengajuan yang masih menunggu checker. */
    @PostMapping("/gl-data/pending/delete")
    public String cancelPending(@RequestParam Long crId, @AuthenticationPrincipal AusyUserDetails me,
                                RedirectAttributes ra) {
        GlChangeService.Res r = service.cancelPending(crId, me);
        ra.addFlashAttribute(r.ok() ? "ok" : "warn", r.message());
        return "redirect:/gl-data";
    }

    @PostMapping("/gl-data/add")
    public String add(@RequestParam Map<String,String> form, @RequestParam(required=false) String note,
                      @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        GlChangeService.Res r = service.submitAdd(form, me, note);
            ra.addFlashAttribute(r.ok() ? "ok" : "warn", r.message());
        return "redirect:/gl-data";
    }

    @PostMapping("/gl-data/edit")
    public String edit(@RequestParam Long glId, @RequestParam Map<String,String> form, @RequestParam(required=false) String note,
                       @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        GlChangeService.Res r = service.submitEdit(glId, form, me, note);
            ra.addFlashAttribute(r.ok() ? "ok" : "warn", r.message());
        return "redirect:/gl-data";
    }

    /** REQ_AUSY_00046: Hapus oleh maker langsung dieksekusi, tanpa otorisasi checker. */
    @PostMapping("/gl-data/delete")
    public String delete(@RequestParam Long glId, @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        GlChangeService.Res r = service.deleteDirect(glId, me);
        ra.addFlashAttribute(r.ok() ? "ok" : "warn", r.message());
        return "redirect:/gl-data";
    }

    // Bulk Edit & Export Excel di halaman ini dicabut (REQ_AUSY_00039): Bulk Edit pindah ke List
    // Data GL dan berlaku per batch (REQ_AUSY_00037). Endpoint lamanya ikut dihapus supaya tidak
    // ada jalur tersembunyi yang mengubah data yang sudah di penampungan.

    // ---- Checker ----
    @GetMapping("/gl-change/review")
    public String review(@AuthenticationPrincipal AusyUserDetails me, Model model) {
        model.addAttribute("items", service.pendingReviewFor(me));
        return "gl-change/review";
    }

    /** Checker downloads the bulk-edit data — before or after — as Excel to compare. */
    @GetMapping("/gl-change/{crId}/export/{side}")
    public void exportSide(@PathVariable Long crId, @PathVariable String side,
                           @AuthenticationPrincipal AusyUserDetails me, HttpServletResponse resp) throws Exception {
        boolean after = "after".equalsIgnoreCase(side);
        List<List<Object>> data = new ArrayList<>();
        // REQ_AUSY_00071 (IDOR): hanya maker pengajuannya atau checker yang berwenang atasnya.
        List<Map<String,String>> rows = service.bulkRows(crId, after, me);
        if (rows == null) { resp.sendError(HttpServletResponse.SC_NOT_FOUND); return; }
        for (Map<String,String> m : rows) {
            data.add(Arrays.asList(m.get("id"), m.get("gltrac"), m.get("gltrcd"),
                    num(m.get("gltram")), m.get("gltrds"), m.get("noPaket")));
        }
        excel.download(resp, after?"After":"Before", "BulkEdit_CR" + crId + "_" + (after?"after":"before"), BULK_HEAD, data, false);
    }

    @PostMapping("/gl-change/approve")
    public String approve(@RequestParam Long crId, @RequestParam(required=false) String note,
                          @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        GlChangeService.Res r = service.approve(crId, me, note);
        ra.addFlashAttribute(r.ok() ? "ok" : "warn", r.message());
        return "redirect:/otorisasi-gl";
    }

    @PostMapping("/gl-change/reject")
    public String reject(@RequestParam Long crId, @RequestParam(required=false) String note,
                         @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("warn", service.reject(crId, me, note));
        return "redirect:/otorisasi-gl";
    }

    private static String s(String v){ return v==null?"":v; }
    private static Object num(String v){ try { return Double.parseDouble(v); } catch (Exception e){ return s(v); } }
    private static String cell(Row r, int i) {
        Cell c = r.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (c == null) return "";
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue().trim();
            case NUMERIC -> { double d = c.getNumericCellValue(); yield (d==Math.floor(d)) ? String.valueOf((long)d) : String.valueOf(d); }
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            default -> "";
        };
    }
}
