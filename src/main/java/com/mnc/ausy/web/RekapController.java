package com.mnc.ausy.web;

import com.mnc.ausy.domain.GlBatchStatus;
import com.mnc.ausy.domain.TUpload;
import com.mnc.ausy.repo.GlTabelRepository;
import com.mnc.ausy.repo.TUploadRepository;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.AuditService;
import com.mnc.ausy.service.BatchLabel;
import com.mnc.ausy.service.BatchStatusService;
import com.mnc.ausy.service.DataScopeService;
import com.mnc.ausy.service.ExcelExportService;
import com.mnc.ausy.service.GlChangeService;
import com.mnc.ausy.service.NamaUserService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Monitoring &amp; Rekapitulasi (PDF §2.2 #3).
 *  - Maker (REQ_AUSY_00054): data miliknya yang BELUM di-approve, dari Batch Upload dan Kelola Data GL,
 *    berstatus Menunggu Otorisasi / Dikembalikan / Rejected; bisa difilter per sumber dan status.
 *  - Role lain: rekap GL aktif (sudah diotorisasi) per batch, seperti sebelumnya.
 */
@Controller
public class RekapController {
    private final GlTabelRepository gl;
    private final ExcelExportService excel;
    private final AuditService audit;
    private final DataScopeService scope;
    private final GlChangeService changes;
    private final BatchStatusService batchStatus;
    private final TUploadRepository uploads;
    private final NamaUserService nama;

    public RekapController(GlTabelRepository gl, ExcelExportService excel, AuditService audit,
                           DataScopeService scope, GlChangeService changes, BatchStatusService batchStatus,
                           TUploadRepository uploads, NamaUserService nama) {
        this.gl = gl; this.excel = excel; this.audit = audit; this.scope = scope;
        this.changes = changes; this.batchStatus = batchStatus; this.uploads = uploads; this.nama = nama;
    }

    private static boolean isMaker(AusyUserDetails me) {
        return me != null && me.getAuthorities().stream().anyMatch(a -> "GROUP_3".equals(a.getAuthority()));
    }

    // ===================== Maker: data yang belum di-approve (REQ_AUSY_00054) =====================

    public static final String SUMBER_BATCH = "Batch Upload", SUMBER_MANUAL = "Kelola Data GL";
    public static final String MENUNGGU = "Menunggu Otorisasi", DIKEMBALIKAN = "Dikembalikan", REJECTED = "Rejected";
    public static final List<String> SUMBER = List.of(SUMBER_BATCH, SUMBER_MANUAL);
    public static final List<String> STATUS = List.of(MENUNGGU, DIKEMBALIKAN, REJECTED);
    private static final DateTimeFormatter TAMPIL = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    /** Satu baris rekap maker: satu batch upload, atau satu pengajuan Kelola Data GL. */
    public record Item(String sumber, String data, String detail, String maker, String waktu, long jml,
                       BigDecimal debit, BigDecimal credit, String status, String catatan) {}

    private List<Item> itemsMaker(AusyUserDetails me, String sumber, String status) {
        List<Item> out = new ArrayList<>();

        // Batch Upload: menunggu otorisasi (termasuk yang Dikembalikan) + Rejected.
        List<Object[]> batches = new ArrayList<>(scope.filterArrays(me, gl.pendingPakets(), 1));
        batches.addAll(gl.rejectedPakets(me.getUsername()));
        Map<String, GlBatchStatus> st = batchStatus.statusMap();
        Map<String, String> files = new HashMap<>();
        List<String> pakets = batches.stream().map(p -> String.valueOf(p[0])).toList();
        if (!pakets.isEmpty())
            for (TUpload t : uploads.findByNoPaketIn(pakets))
                if (t.getNoPaket() != null && t.getFileName() != null) files.put(t.getNoPaket(), t.getFileName());
        for (Object[] p : batches) {
            String paket = String.valueOf(p[0]), maker = String.valueOf(p[1]);
            GlBatchStatus s = st.get(paket + "|" + maker);
            String stat = s == null ? MENUNGGU
                    : (GlBatchStatus.REJECTED.equals(s.getStatus()) ? REJECTED : DIKEMBALIKAN);
            out.add(new Item(SUMBER_BATCH, "Batch " + BatchLabel.label(paket), files.getOrDefault(paket, ""), maker,
                    waktu(p[5]), ((Number) p[2]).longValue(), (BigDecimal) p[3], (BigDecimal) p[4], stat,
                    s == null ? "" : nama.of(s.getDecidedBy()) + ": " + s.getNote()));
        }

        // Kelola Data GL: pengajuan yang menunggu (termasuk Dikembalikan dari penampungan) + Rejected.
        List<Map<String, String>> manual = new ArrayList<>(changes.pengajuanOf(me));
        manual.addAll(changes.rejectedOf(me));
        for (Map<String, String> m : manual) {
            String stat = "Rejected".equals(m.get("status")) ? REJECTED
                    // data dikembalikan yang pengajuan ulangnya ditolak checker
                    : (m.get("crId") == null || m.get("crId").isEmpty()) ? REJECTED
                    : "Dikembalikan".equals(m.get("jenis")) ? DIKEMBALIKAN : MENUNGGU;
            BigDecimal amt = amount(m.get("gltram"));
            boolean debit = "D".equalsIgnoreCase(m.get("gltrcd"));
            String glId = m.getOrDefault("glId", "");
            out.add(new Item(SUMBER_MANUAL,
                    m.get("jenis") + " · GL " + m.getOrDefault("gltrac", "") + " " + m.getOrDefault("gltrcd", "")
                            + (glId.isEmpty() ? "" : " (Data #" + glId + ")"),
                    m.getOrDefault("gltrds", ""), me.getUsername(), m.getOrDefault("diajukan", ""), 1,
                    debit ? amt : BigDecimal.ZERO, debit ? BigDecimal.ZERO : amt, stat, m.getOrDefault("catatan", "")));
        }

        return out.stream()
                .filter(i -> sumber == null || sumber.isBlank() || sumber.equals(i.sumber()))
                .filter(i -> status == null || status.isBlank() || status.equals(i.status()))
                .toList();
    }

    private static String waktu(Object o) {
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().format(TAMPIL);
        if (o instanceof LocalDateTime t) return t.format(TAMPIL);
        return "";
    }

    private static BigDecimal amount(String s) {
        try { return s == null || s.isBlank() ? BigDecimal.ZERO : new BigDecimal(s.trim()); }
        catch (Exception e) { return BigDecimal.ZERO; }
    }

    // ===================== Role lain: rekap GL aktif (tidak berubah) =====================

    /** Rekap hanya mencakup data GL yang boleh dilihat user ini — kolom ke-2 = gltrus (BUG_AUSY_00138). */
    private List<Object[]> scopedPakets(AusyUserDetails me) {
        return scope.filterArrays(me, gl.approvedPakets(), 1);
    }

    public record Row(String paket, String uploader, long jml, BigDecimal debit, BigDecimal credit, boolean balanced) {}

    /** Grup data tambahan manual ditulis apa adanya, bukan sebagai nomor batch (BUG_AUSY_00126). */
    private static String batchLabel(String paket) {
        return "(manual)".equals(paket) ? "Data Tambahan Manual" : BatchLabel.label(paket);
    }

    @GetMapping("/rekap/export")
    public void export(@AuthenticationPrincipal AusyUserDetails me, HttpServletResponse resp,
                       @RequestParam(required = false) String sumber,
                       @RequestParam(required = false) String status) throws Exception {
        if (isMaker(me)) {
            List<String> head = List.of("Sumber", "Data", "Detail", "Maker", "Tanggal", "Jumlah Baris",
                    "Debit", "Credit", "Status", "Catatan Checker");
            List<List<Object>> data = new ArrayList<>();
            for (Item i : itemsMaker(me, sumber, status))
                data.add(Arrays.asList(i.sumber(), i.data(), i.detail(), nama.of(i.maker()), i.waktu(), i.jml(),
                        i.debit(), i.credit(), i.status(), i.catatan()));
            excel.download(resp, "Rekapitulasi", "Rekap_Belum_Approve", head, data, true);
            audit.transaction(me.getUsername(), "EXPORT_REKAP", "Export rekapitulasi data belum di-approve ke Excel"
                    + (blank(sumber) ? "" : ", sumber " + sumber) + (blank(status) ? "" : ", status " + status));
            return;
        }
        List<String> head = List.of("Batch","Uploader","Jumlah Baris","Debit","Credit","Balance");
        List<List<Object>> data = new ArrayList<>();
        long totJml = 0; BigDecimal totD = BigDecimal.ZERO, totC = BigDecimal.ZERO;
        for (Object[] r : scopedPakets(me)) {
            long jml = ((Number) r[2]).longValue();
            BigDecimal d = (BigDecimal) r[3], c = (BigDecimal) r[4];
            data.add(Arrays.asList(batchLabel((String) r[0]), r[1], jml, d, c,
                    d.compareTo(c) == 0 ? "Balance" : "Tidak Balance"));
            totJml += jml; totD = totD.add(d); totC = totC.add(c);
        }
        data.add(Arrays.asList("TOTAL", "", totJml, totD, totC, totD.compareTo(totC) == 0 ? "Balance" : "Tidak Balance"));
        excel.download(resp, "Rekapitulasi", "Rekap_GL", head, data, true);
        audit.transaction(me.getUsername(), "EXPORT_REKAP", "Export rekapitulasi GL ke Excel");
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    @GetMapping("/rekap")
    public String rekap(@AuthenticationPrincipal AusyUserDetails me, Model model,
                        @RequestParam(required = false) String sumber,
                        @RequestParam(required = false) String status) {
        if (isMaker(me)) {
            List<Item> semua = itemsMaker(me, null, null);
            List<Item> items = itemsMaker(me, sumber, status);
            Map<String, Long> perStatus = new LinkedHashMap<>();
            for (String s : STATUS) perStatus.put(s, semua.stream().filter(i -> s.equals(i.status())).count());
            model.addAttribute("items", items);
            model.addAttribute("perStatus", perStatus);
            model.addAttribute("sumberList", SUMBER);
            model.addAttribute("statusList", STATUS);
            model.addAttribute("fSumber", sumber == null ? "" : sumber);
            model.addAttribute("fStatus", status == null ? "" : status);
            model.addAttribute("totJml", items.stream().mapToLong(Item::jml).sum());
            model.addAttribute("totDebit", items.stream().map(Item::debit).reduce(BigDecimal.ZERO, BigDecimal::add));
            model.addAttribute("totCredit", items.stream().map(Item::credit).reduce(BigDecimal.ZERO, BigDecimal::add));
            model.addAttribute("rejectedDays", GlChangeService.REKAP_REJECTED_DAYS);
            return "rekap/maker";
        }
        List<Row> rows = new ArrayList<>();
        long totJml = 0; BigDecimal totD = BigDecimal.ZERO, totC = BigDecimal.ZERO; long maxJml = 1;
        for (Object[] r : scopedPakets(me)) {
            long jml = ((Number) r[2]).longValue();
            BigDecimal d = (BigDecimal) r[3], c = (BigDecimal) r[4];
            rows.add(new Row((String) r[0], (String) r[1], jml, d, c, d.compareTo(c) == 0));
            totJml += jml; totD = totD.add(d); totC = totC.add(c);
            if (jml > maxJml) maxJml = jml;
        }
        model.addAttribute("rows", rows);
        model.addAttribute("totJml", totJml);
        model.addAttribute("totDebit", totD);
        model.addAttribute("totCredit", totC);
        model.addAttribute("maxJml", maxJml);
        return "rekap/index";
    }
}
