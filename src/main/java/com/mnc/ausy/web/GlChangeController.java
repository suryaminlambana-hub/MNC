package com.mnc.ausy.web;

import com.mnc.ausy.domain.GlTabel;
import com.mnc.ausy.repo.GlTabelRepository;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.Nominal;
import com.mnc.ausy.service.DataScopeService;
import com.mnc.ausy.service.ExcelExportService;
import com.mnc.ausy.service.GlChangeService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.apache.poi.ss.usermodel.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

/** Workflow 8.3 — Update GL: maker page (/gl-data) + checker review (/gl-change/review). */
@Controller
public class GlChangeController {
    private static final List<String> BULK_HEAD = List.of("ID","GL Code","D/C","Amount","Description","Batch");
    private final GlChangeService service;
    private final GlTabelRepository gl;
    private final ExcelExportService excel;
    private final DataScopeService scope;

    public GlChangeController(GlChangeService service, GlTabelRepository gl, ExcelExportService excel,
                              DataScopeService scope) {
        this.service = service; this.gl = gl; this.excel = excel; this.scope = scope;
    }

    // ---- Maker ----
    /**
     * Kelola Data GL (REQ_AUSY_00046): hanya data manual yang BELUM final.
     *  - pengajuan Tambah Data GL milik maker ini yang masih menunggu checker;
     *  - data manual yang dikembalikan checker lewat Return Data (bisa diubah lewat checker, atau
     *    dihapus langsung).
     * Setelah checker meng-approve Tambah/Ubah, datanya masuk penampungan EXECUTE dan hilang dari
     * sini. Data hasil upload diurus di List Data GL, bukan di halaman ini.
     */
    @GetMapping("/gl-data")
    public String dataPage(@AuthenticationPrincipal AusyUserDetails me, Model model) {
        model.addAttribute("rows", gl.findReturnedManual(scope.visibleMakers(me)));
        model.addAttribute("pendingAdds", service.pendingAddsOf(me));
        model.addAttribute("fields", GlChangeService.FIELDS);
        // BUG_AUSY_00099 follow-up: kunci baris + alasannya, bukan cuma "Menunggu Otorisasi"
        model.addAttribute("pendingLabels", service.pendingGlRowLabels());
        return "gl-data/index";
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
    public void exportSide(@PathVariable Long crId, @PathVariable String side, HttpServletResponse resp) throws Exception {
        boolean after = "after".equalsIgnoreCase(side);
        List<List<Object>> data = new ArrayList<>();
        for (Map<String,String> m : service.bulkRows(crId, after)) {
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
