package com.mnc.ausy.web;

import com.mnc.ausy.domain.GlTabel;
import com.mnc.ausy.repo.GlTabelRepository;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.ExcelExportService;
import com.mnc.ausy.service.GlChangeService;
import jakarta.servlet.http.HttpServletResponse;
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

    public GlChangeController(GlChangeService service, GlTabelRepository gl, ExcelExportService excel) {
        this.service = service; this.gl = gl; this.excel = excel;
    }

    // ---- Maker ----
    @GetMapping("/gl-data")
    public String dataPage(@AuthenticationPrincipal AusyUserDetails me, Model model) {
        // hanya GL yang sudah lolos otorisasi (baris PENDING belum boleh dikelola/tampil sebagai aktif)
        model.addAttribute("rows", gl.findTop200ByActiveAndStatusDualNotOrderByIdDesc((short)1, "PENDING"));
        model.addAttribute("fields", GlChangeService.FIELDS);
        return "gl-data/index";
    }

    @PostMapping("/gl-data/add")
    public String add(@RequestParam Map<String,String> form, @RequestParam(required=false) String note,
                      @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", service.submitAdd(form, me, note));
        return "redirect:/gl-data";
    }

    @PostMapping("/gl-data/edit")
    public String edit(@RequestParam Long glId, @RequestParam Map<String,String> form, @RequestParam(required=false) String note,
                       @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", service.submitEdit(glId, form, me, note));
        return "redirect:/gl-data";
    }

    @PostMapping("/gl-data/delete")
    public String delete(@RequestParam Long glId, @RequestParam(required=false) String note,
                         @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", service.submitDelete(glId, me, note));
        return "redirect:/gl-data";
    }

    /** Bulk edit step 1 — maker downloads current GL data as an editable Excel (ID + editable fields). */
    @GetMapping("/gl-data/bulk-export")
    public void bulkExport(HttpServletResponse resp) throws Exception {
        List<GlTabel> rows = gl.findTop200ByActiveAndStatusDualNotOrderByIdDesc((short)1, "PENDING");
        List<List<Object>> data = new ArrayList<>();
        for (GlTabel g : rows) data.add(Arrays.asList(
                g.getId(), s(g.getGltrac()), s(g.getGltrcd()),
                g.getGltram()==null?0d:g.getGltram(), s(g.getGltrds()), s(g.getNoPaket())));
        excel.download(resp, "Data GL", "DataGL_untuk_edit", BULK_HEAD, data, false);
    }

    /** Bulk edit step 3 — maker uploads the edited Excel; changed rows become one bulk change request. */
    @PostMapping("/gl-data/bulk-upload")
    public String bulkUpload(@RequestParam("file") MultipartFile file, @RequestParam(required=false) String note,
                             @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        if (file == null || file.isEmpty()) { ra.addFlashAttribute("warn", "File belum dipilih."); return "redirect:/gl-data"; }
        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            Sheet sh = wb.getSheetAt(0);
            List<Map<String,String>> rows = new ArrayList<>();
            for (int r = 1; r <= sh.getLastRowNum(); r++) {
                Row row = sh.getRow(r);
                if (row == null) continue;
                String id = cell(row, 0);
                if (id.isBlank()) continue;
                Map<String,String> m = new LinkedHashMap<>();
                m.put("id", id);
                m.put("gltrac", cell(row, 1));
                m.put("gltrcd", cell(row, 2).toUpperCase());
                m.put("gltram", cell(row, 3));
                m.put("gltrds", cell(row, 4));
                m.put("noPaket", cell(row, 5));
                rows.add(m);
            }
            ra.addFlashAttribute("ok", service.submitBulkEditExcel(rows, me, note));
        } catch (Exception e) {
            ra.addFlashAttribute("warn", "Gagal membaca file Excel: " + e.getMessage());
        }
        return "redirect:/gl-data";
    }

    // ---- Checker ----
    @GetMapping("/gl-change/review")
    public String review(@AuthenticationPrincipal AusyUserDetails me, Model model) {
        model.addAttribute("items", service.pendingReviewFor(me.getIdGroup()));
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
