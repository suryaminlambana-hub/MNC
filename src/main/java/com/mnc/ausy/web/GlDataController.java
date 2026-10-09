package com.mnc.ausy.web;

import com.mnc.ausy.service.BatchLabel;
import com.mnc.ausy.domain.GlBatchStatus;
import com.mnc.ausy.domain.GlTabel;
import com.mnc.ausy.domain.TUpload;
import com.mnc.ausy.repo.GlTabelRepository;
import com.mnc.ausy.repo.TUploadRepository;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.BatchStatusService;
import com.mnc.ausy.service.DataScopeService;
import com.mnc.ausy.service.ExcelExportService;
import com.mnc.ausy.service.Nominal;
import com.mnc.ausy.service.OtorisasiService;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.*;

@Controller
public class GlDataController {
    private final GlTabelRepository gl;
    private final DataScopeService scope;
    private final OtorisasiService otorisasi;
    private final TUploadRepository uploads;
    private final ExcelExportService excel;
    private final BatchStatusService batchStatus;

    public GlDataController(GlTabelRepository gl, DataScopeService scope, OtorisasiService otorisasi,
                            TUploadRepository uploads, ExcelExportService excel, BatchStatusService batchStatus) {
        this.gl = gl; this.scope = scope; this.otorisasi = otorisasi;
        this.uploads = uploads; this.excel = excel; this.batchStatus = batchStatus;
    }

    /** Maker (GROUP_3): pemilik batch upload — hanya ia yang mendapat tombol Bulk Edit/Hapus. */
    private static boolean isMaker(AusyUserDetails me) {
        return me != null && me.getAuthorities().stream()
                .anyMatch(a -> "GROUP_3".equals(a.getAuthority()));
    }

    /** Kolom Excel Bulk Edit per batch — sama dengan format Bulk Edit sebelumnya. */
    private static final List<String> BULK_HEAD = List.of("ID", "GL Code", "D/C", "Amount", "Description", "Batch");

    /**
     * List Data GL: batch upload yang BELUM diotorisasi (REQ_AUSY_00036 &amp; 00044). Batch yang sudah
     * di-approve pindah ke penampungan EXECUTE dan hilang dari sini. Tabel "Data GL Sudah
     * Diotorisasi" untuk checker dicabut (REQ_AUSY_00036 feedback), jadi maker dan checker melihat
     * tabel yang sama — masing-masing dibatasi per pasangan maker (BUG_AUSY_00138 / 00139).
     */
    @GetMapping("/gl-legacy")
    public String list(@AuthenticationPrincipal AusyUserDetails me, Model model) {
        model.addAttribute("isMaker", isMaker(me));
        List<Object[]> pending = pendingUploads(me);
        model.addAttribute("pendingUploads", pending);
        // REQ_AUSY_00053: kolom Status — Dikembalikan (dari Return Data) per batch yang menunggu, dan
        // batch Rejected milik maker ini (barisnya sudah dihapus, ringkasannya dari gl_batch_status).
        List<GlBatchStatus> rejected = isMaker(me) ? batchStatus.rejectedOf(me) : List.of();
        model.addAttribute("returned", batchStatus.returnedMap());
        model.addAttribute("rejected", rejected);
        Set<String> pakets = new HashSet<>();
        pending.forEach(p -> pakets.add(String.valueOf(p[0])));
        rejected.forEach(r -> pakets.add(r.getNoPaket()));
        model.addAttribute("uploadFiles", uploadFiles(pakets));
        return "gl-legacy/list";
    }

    /** REQ_AUSY_00053: maker menghapus batch Rejected miliknya dari daftar. */
    @PostMapping("/gl-legacy/rejected/{paket}/delete")
    public String dismissRejected(@PathVariable String paket, @AuthenticationPrincipal AusyUserDetails me,
                                  RedirectAttributes ra) {
        String err = batchStatus.dismissRejected(paket, me);
        ra.addFlashAttribute(err == null ? "ok" : "warn",
                err == null ? "Batch " + BatchLabel.label(paket) + " (Rejected) dihapus dari daftar." : err);
        return "redirect:/gl-legacy";
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
    private Map<String, String> uploadFiles(Collection<String> pakets) {
        Map<String, String> out = new HashMap<>();
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
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Batch " + BatchLabel.label(paket) + " tidak ada di upload Anda yang belum diotorisasi.");
            return;
        }
        List<List<Object>> data = new ArrayList<>();
        for (GlTabel g : rows) data.add(Arrays.asList(g.getId(), nz(g.getGltrac()), nz(g.getGltrcd()),
                Nominal.format(g.getGltram()), nz(g.getGltrds()), BatchLabel.label(g.getNoPaket())));
        excel.download(resp, "Batch " + BatchLabel.label(paket), "BulkEdit_Batch_" + BatchLabel.label(paket), BULK_HEAD, data, false);
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
            // REQ_AUSY_00037 (feedback): berkas yang bukan template Bulk Edit ditolak dengan satu
            // kalimat — dulu diproses per baris dan pesannya memenuhi layar.
            if (!sesuaiTemplate(sh.getRow(0))) {
                ra.addFlashAttribute("warn", "File tidak sesuai template Bulk Edit (kolom harus "
                        + String.join(", ", BULK_HEAD) + "). " + OtorisasiService.PAKAI_TEMPLATE);
                return "redirect:/gl-legacy";
            }
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
                ra.addFlashAttribute("warn", "Bulk Edit ditolak, tidak ada data yang diubah: kolom Amount tidak terbaca"
                        + " sebagai angka pada " + OtorisasiService.ringkasSalah(amountSalah)
                        + ". Gunakan format 1.000.000,50.");
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

    /** Baris judul berkas = kolom hasil Export Excel Bulk Edit (huruf besar/kecil dan spasi diabaikan). */
    private static boolean sesuaiTemplate(Row judul) {
        if (judul == null) return false;
        for (int i = 0; i < BULK_HEAD.size(); i++)
            if (!BULK_HEAD.get(i).replace(" ", "").equalsIgnoreCase(cell(judul, i).replace(" ", ""))) return false;
        return true;
    }

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
