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

    /** Sama dengan List GL Aktif — dua halaman ini harus menampilkan hal yang sama. */
    private static final int PAGE_SIZE = 10;

    // ---- Maker ----
    @GetMapping("/gl-data")
    public String dataPage(@AuthenticationPrincipal AusyUserDetails me, Model model,
                           @RequestParam(defaultValue = "0") int page) {
        // Persis daftar yang sama dengan List GL Aktif: satu definisi "GL Aktif" dipakai berdua
        // (BUG_AUSY_00150) dan dipaginasi dengan cara yang sama. Dulu halaman ini memakai query
        // turunan yang menjatuhkan baris ber-status_dual NULL dan berhenti di 200 baris, sehingga
        // isinya tidak pernah sama dengan List GL Aktif. Dibatasi per maker (BUG_AUSY_00138).
        Pageable req = PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.by(Sort.Direction.DESC, "id"));
        Page<GlTabel> result = gl.pageGlAktif(scope.visibleMakers(me), req);
        model.addAttribute("rows", result.getContent());
        model.addAttribute("total", result.getTotalElements());
        model.addAttribute("page", result.getNumber());
        model.addAttribute("totalPages", Math.max(result.getTotalPages(), 1));
        model.addAttribute("fields", GlChangeService.FIELDS);
        // BUG_AUSY_00099 follow-up: kunci baris + alasannya ("… Hapus Batch 01"), bukan cuma "Menunggu Otorisasi"
        model.addAttribute("pendingLabels", service.pendingGlRowLabels());
        model.addAttribute("activeBatches", scope.filterArrays(me, gl.approvedPakets(), 1));   // REQ_AUSY_00029
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

    @PostMapping("/gl-data/delete")
    public String delete(@RequestParam Long glId, @RequestParam(required=false) String note,
                         @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        GlChangeService.Res r = service.submitDelete(glId, me, note);
            ra.addFlashAttribute(r.ok() ? "ok" : "warn", r.message());
        return "redirect:/gl-data";
    }

    /** Bulk edit step 1 — maker downloads current GL data as an editable Excel (ID + editable fields). */
    @GetMapping("/gl-data/bulk-export")
    public void bulkExport(@RequestParam(required = false) String batch,
                           @AuthenticationPrincipal AusyUserDetails me, HttpServletResponse resp) throws Exception {
        // Unduhan harus memuat SELURUH baris yang dikelola maker ini, bukan sepotong halaman —
        // definisi "GL Aktif" yang sama dengan layarnya (BUG_AUSY_00150), batas per maker tetap
        // berlaku (BUG_AUSY_00138). Sebelumnya berhenti di 200 baris, jadi file Excel-nya diam-diam
        // terpotong dan bulk edit tidak pernah bisa menyentuh baris selebihnya.
        //
        // REQ_AUSY_00029: bisa dibatasi satu batch ("<no batch>::<uploader>", sama seperti PDF per
        // batch); kosong = semua batch. Amount ditulis dalam format Indonesia (1.000.000,50)
        // sebagai teks, dan dibaca kembali oleh Nominal.parse saat diunggah ulang.
        String paket = null, maker = null;
        if (batch != null && !batch.isBlank()) {
            int sep = batch.lastIndexOf("::");
            paket = sep < 0 ? batch : batch.substring(0, sep);
            maker = sep < 0 ? null : batch.substring(sep + 2);
        }
        final String fPaket = paket, fMaker = maker;
        List<GlTabel> rows = gl.findGlAktifAll(scope.visibleMakers(me)).stream()
                .filter(g -> fPaket == null || (fPaket.equals(g.batchKey())
                        && (fMaker == null || fMaker.equals(g.getGltrus()))))
                .toList();
        List<List<Object>> data = new ArrayList<>();
        for (GlTabel g : rows) data.add(Arrays.asList(
                g.getId(), s(g.getGltrac()), s(g.getGltrcd()),
                Nominal.format(g.getGltram()), s(g.getGltrds()), s(g.getNoPaket())));
        String nama = fPaket == null ? "DataGL_untuk_edit"
                : "DataGL_untuk_edit_Batch_" + ("(manual)".equals(fPaket) ? "manual" : fPaket);
        excel.download(resp, "Data GL", nama, BULK_HEAD, data, false);
    }

    /** Bulk edit step 3 — maker uploads the edited Excel; changed rows become one bulk change request. */
    @PostMapping("/gl-data/bulk-upload")
    public String bulkUpload(@RequestParam("file") MultipartFile file, @RequestParam(required=false) String note,
                             @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        if (file == null || file.isEmpty()) { ra.addFlashAttribute("warn", "File belum dipilih."); return "redirect:/gl-data"; }
        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            Sheet sh = wb.getSheetAt(0);
            List<Map<String,String>> rows = new ArrayList<>();
            List<String> amountSalah = new ArrayList<>();
            for (int r = 1; r <= sh.getLastRowNum(); r++) {
                Row row = sh.getRow(r);
                if (row == null) continue;
                String id = cell(row, 0);
                if (id.isBlank()) continue;
                // REQ_AUSY_00029: Amount dibaca dalam format Indonesia (1.000.000,50) maupun angka
                // biasa dari sel numerik. Yang tidak terbaca MENOLAK seluruh berkas — dulu nilai
                // seperti itu diam-diam menjadi 0 dan ikut diajukan sebagai perubahan.
                String amountTeks = cell(row, 3);
                java.math.BigDecimal amount = Nominal.parse(amountTeks);
                if (amount == null) { amountSalah.add("baris " + (r + 1) + " (\"" + amountTeks + "\")"); continue; }
                Map<String,String> m = new LinkedHashMap<>();
                m.put("id", id);
                m.put("gltrac", cell(row, 1));
                m.put("gltrcd", cell(row, 2).toUpperCase());
                m.put("gltram", amount.toPlainString());
                m.put("gltrds", cell(row, 4));
                m.put("noPaket", cell(row, 5));
                rows.add(m);
            }
            if (!amountSalah.isEmpty()) {
                ra.addFlashAttribute("warn", "Bulk edit tidak diajukan: kolom Amount tidak terbaca sebagai angka pada "
                        + String.join(", ", amountSalah) + ". Gunakan format 1.000.000,50.");
                return "redirect:/gl-data";
            }
            GlChangeService.Res r = service.submitBulkEditExcel(rows, me, note);
            ra.addFlashAttribute(r.ok() ? "ok" : "warn", r.message());
        } catch (Exception e) {
            ra.addFlashAttribute("warn", "Gagal membaca file Excel: " + e.getMessage());
        }
        return "redirect:/gl-data";
    }

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
