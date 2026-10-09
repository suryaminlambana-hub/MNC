package com.mnc.ausy.web;

import com.mnc.ausy.domain.GlCode;
import com.mnc.ausy.domain.GlTabel;
import com.mnc.ausy.domain.TUpload;
import com.mnc.ausy.repo.GlCodeRepository;
import com.mnc.ausy.repo.GlTabelRepository;
import com.mnc.ausy.repo.TUploadRepository;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.ApprovalService;
import com.mnc.ausy.service.AuditService;
import com.mnc.ausy.service.DataScopeService;
import com.mnc.ausy.service.GlChangeService;
import com.mnc.ausy.service.GlExportService;
import com.mnc.ausy.service.ExcelExportService;
import com.mnc.ausy.service.Nominal;
import com.mnc.ausy.service.OtorisasiService;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
    private final GlExportService glExport;
    private final AuditService audit;
    private final GlChangeService changes;
    private final ApprovalService approval;
    private final DataScopeService scope;
    private final OtorisasiService otorisasi;
    private final TUploadRepository uploads;
    private final ExcelExportService excel;

    public GlDataController(GlTabelRepository gl, GlCodeRepository glCode, GlExportService glExport,
                            AuditService audit, GlChangeService changes, ApprovalService approval,
                            DataScopeService scope, OtorisasiService otorisasi,
                            TUploadRepository uploads, ExcelExportService excel) {
        this.gl = gl; this.glCode = glCode; this.glExport = glExport; this.audit = audit;
        this.changes = changes; this.approval = approval; this.scope = scope; this.otorisasi = otorisasi;
        this.uploads = uploads; this.excel = excel;
    }

    /**
     * A GL row is "registered" when its NoGL exists in the GL Code master.
     *
     * Sisi D/C tidak ikut dicocokkan sejak BUG_AUSY_00170: master Kode GL hanya memuat nomor
     * akun, jadi satu kode yang terdaftar berlaku untuk baris debit maupun credit.
     */
    private Set<String> registeredKeys() {
        return glCode.findAll().stream().map(GlCode::getCode).collect(Collectors.toSet());
    }
    private static boolean isRegistered(Set<String> reg, GlTabel g) {
        return reg.contains(n(g.getGltrac()));
    }
    private static String n(String s) { return s == null ? "" : s; }

    /** Hasil agregat multi-kolom kadang dibungkus Object[1][] oleh JPA. */
    private static Object[] unwrapRow(Object[] row) {
        return (row != null && row.length == 1 && row[0] instanceof Object[] inner) ? inner : row;
    }

    /**
     * Pemilik batch upload (maker). Dipakai untuk memisahkan tampilan List Data GL: maker hanya
     * melihat batch yang masih perlu ia tangani (REQ_AUSY_00044), checker melihat data yang sudah
     * diotorisasi untuk keperluan tracking.
     */
    private static boolean isMaker(AusyUserDetails me) {
        return me != null && me.getAuthorities().stream()
                .anyMatch(a -> "GROUP_3".equals(a.getAuthority()));
    }

    /** Baris per halaman di tabel data terotorisasi — datanya bisa belasan ribu (BUG_AUSY_00147). */
    private static final int PAGE_SIZE = 10;

    /** Kolom Excel Bulk Edit per batch — sama dengan format Bulk Edit sebelumnya. */
    private static final List<String> BULK_HEAD = List.of("ID", "GL Code", "D/C", "Amount", "Description", "Batch");

    @GetMapping("/gl-legacy")
    public String list(@AuthenticationPrincipal AusyUserDetails me, Model model,
                       @RequestParam(defaultValue = "0") int page) {
        boolean maker = isMaker(me);
        model.addAttribute("isMaker", maker);

        // REQ_AUSY_00036 & 00044: batch upload yang BELUM diotorisasi — inilah isi List Data GL
        // bagi maker. Batch yang sudah di-approve pindah ke penampungan EXECUTE dan hilang dari sini.
        List<Object[]> pending = pendingUploads(me);
        model.addAttribute("pendingUploads", pending);
        model.addAttribute("uploadFiles", uploadFiles(pending));

        // Tabel data yang sudah diotorisasi hanya untuk non-maker (checker/monitoring), untuk
        // keperluan tracking. Tetap dipaginasi di database (BUG_AUSY_00147) dan dibatasi per
        // pasangan maker (BUG_AUSY_00138 / 00139).
        if (!maker) {
            Set<String> makers = scope.visibleMakers(me);
            Pageable req = PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.by(Sort.Direction.DESC, "id"));
            Page<GlTabel> result;
            Object[] sum;
            if (makers == null) {
                result = gl.pageActive(req);
                sum = unwrapRow(gl.activeSummary());
            } else if (makers.isEmpty()) {
                result = Page.empty(req);
                sum = new Object[]{0L, BigDecimal.ZERO, 0L};
            } else {
                result = gl.pageActiveByMakers(makers, req);
                sum = unwrapRow(gl.activeSummaryByMakers(makers));
            }
            model.addAttribute("rows", result.getContent());
            model.addAttribute("registered", registeredKeys());
            model.addAttribute("total", ((Number) sum[0]).longValue());
            model.addAttribute("totalAmount", sum[1] == null ? BigDecimal.ZERO : (BigDecimal) sum[1]);
            model.addAttribute("page", result.getNumber());
            model.addAttribute("totalPages", Math.max(result.getTotalPages(), 1));
            model.addAttribute("activeBatches", scope.filterArrays(me, gl.approvedPakets(), 1));   // REQ_AUSY_00023
        }
        return "gl-legacy/list";
    }

    /**
     * Batch upload yang menunggu otorisasi, disaring per maker (REQ_AUSY_00014). Kolom waktunya
     * (max(adddt)) bisa datang sebagai java.sql.Timestamp tergantung driver, sedangkan
     * #temporals di template hanya menerima LocalDateTime — diseragamkan di sini.
     */
    private List<Object[]> pendingUploads(AusyUserDetails me) {
        List<Object[]> out = scope.filterArrays(me, gl.pendingPakets(), 1);
        for (Object[] p : out)
            if (p.length > 5 && p[5] instanceof java.sql.Timestamp ts) p[5] = ts.toLocalDateTime();
        return out;
    }

    /** Nama file Excel per nomor batch (REQ_AUSY_00036); upload lama tanpa tautan tidak muncul. */
    private Map<String, String> uploadFiles(List<Object[]> pending) {
        Map<String, String> out = new HashMap<>();
        List<String> pakets = pending.stream().map(p -> String.valueOf(p[0])).toList();
        if (pakets.isEmpty()) return out;
        for (TUpload t : uploads.findByNoPaketIn(pakets))
            if (t.getNoPaket() != null && t.getFileName() != null) out.put(t.getNoPaket(), t.getFileName());
        return out;
    }

    /** REQ_AUSY_00022 — maker menghapus batch upload miliknya yang belum diotorisasi checker. */
    @PostMapping("/gl-legacy/pending/{paket}/delete")
    public String withdrawPending(@PathVariable String paket, @AuthenticationPrincipal AusyUserDetails me,
                                  RedirectAttributes ra) {
        OtorisasiService.ExportResult r = otorisasi.withdraw(paket, me);
        ra.addFlashAttribute(r.ok() ? "ok" : "warn", r.message());
        return "redirect:/gl-legacy";
    }

    /**
     * REQ_AUSY_00037 — Bulk Edit per batch, langkah 1: unduh isi batch (milik maker sendiri, belum
     * diotorisasi) sebagai Excel. Amount ditulis dalam format Indonesia (1.000.000,50).
     */
    @GetMapping("/gl-legacy/pending/{paket}/bulk-export")
    public void bulkExportPending(@PathVariable String paket, @AuthenticationPrincipal AusyUserDetails me,
                                  HttpServletResponse resp) throws Exception {
        List<GlTabel> rows = me == null ? List.of() : gl.findBatchRows(paket, me.getUsername()).stream()
                .filter(g -> "PENDING".equals(g.getStatusDual()) && Short.valueOf((short) 1).equals(g.getActive()))
                .toList();
        if (rows.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Batch " + paket + " tidak ada di upload Anda yang belum diotorisasi.");
            return;
        }
        List<List<Object>> data = new ArrayList<>();
        for (GlTabel g : rows) data.add(Arrays.asList(g.getId(), nz(g.getGltrac()), nz(g.getGltrcd()),
                Nominal.format(g.getGltram()), nz(g.getGltrds()), nz(g.getNoPaket())));
        excel.download(resp, "Batch " + paket, "BulkEdit_Batch_" + paket, BULK_HEAD, data, false);
    }

    /**
     * REQ_AUSY_00037 &amp; 00047 — Bulk Edit per batch, langkah 2: unggah Excel hasil edit. Langsung
     * diterapkan ke batch itu saja (belum diotorisasi, jadi tanpa checker); PDF ikut berubah.
     */
    @PostMapping("/gl-legacy/pending/{paket}/bulk-upload")
    public String bulkUploadPending(@PathVariable String paket, @RequestParam("file") MultipartFile file,
                                    @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        if (file == null || file.isEmpty()) { ra.addFlashAttribute("warn", "File belum dipilih."); return "redirect:/gl-legacy"; }
        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            Sheet sh = wb.getSheetAt(0);
            List<Map<String, String>> isi = new ArrayList<>();
            List<String> amountSalah = new ArrayList<>();
            for (int r = 1; r <= sh.getLastRowNum(); r++) {
                Row row = sh.getRow(r);
                if (row == null || cell(row, 0).isBlank()) continue;
                String amountTeks = cell(row, 3);
                BigDecimal amount = Nominal.parse(amountTeks);
                if (amount == null) { amountSalah.add("baris " + (r + 1) + " (\"" + amountTeks + "\")"); continue; }
                Map<String, String> m = new LinkedHashMap<>();
                m.put("baris", String.valueOf(r + 1));
                m.put("id", cell(row, 0));
                m.put("gltrac", cell(row, 1));
                m.put("gltrcd", cell(row, 2));
                m.put("gltram", amount.toPlainString());
                m.put("gltrds", cell(row, 4));
                isi.add(m);
            }
            if (!amountSalah.isEmpty()) {
                ra.addFlashAttribute("warn", "Bulk Edit tidak diterapkan: kolom Amount tidak terbaca sebagai angka pada "
                        + String.join(", ", amountSalah) + ". Gunakan format 1.000.000,50.");
                return "redirect:/gl-legacy";
            }
            OtorisasiService.ExportResult r = otorisasi.bulkEditPending(paket, me, isi);
            ra.addFlashAttribute(r.ok() ? "ok" : "warn", r.message());
        } catch (Exception e) {
            ra.addFlashAttribute("warn", "Gagal membaca file Excel: " + e.getMessage());
        }
        return "redirect:/gl-legacy";
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String cell(Row r, int i) {
        Cell c = r.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (c == null) return "";
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue().trim();
            case NUMERIC -> { double d = c.getNumericCellValue(); yield (d == Math.floor(d)) ? String.valueOf((long) d) : String.valueOf(d); }
            default -> "";
        };
    }
}
