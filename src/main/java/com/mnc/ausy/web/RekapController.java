package com.mnc.ausy.web;

import com.mnc.ausy.repo.GlTabelRepository;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.AuditService;
import com.mnc.ausy.service.ExcelExportService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.math.BigDecimal;
import java.util.*;

/** Monitoring & Rekapitulasi (PDF §2.2 #3): recap of GL pakets awaiting authorization. */
@Controller
public class RekapController {
    private final GlTabelRepository gl;
    private final ExcelExportService excel;
    private final AuditService audit;
    public RekapController(GlTabelRepository gl, ExcelExportService excel, AuditService audit) {
        this.gl = gl; this.excel = excel; this.audit = audit;
    }

    public record Row(String paket, String uploader, long jml, BigDecimal debit, BigDecimal credit, boolean balanced) {}

    @GetMapping("/rekap/export")
    public void export(@AuthenticationPrincipal AusyUserDetails me, HttpServletResponse resp) throws Exception {
        List<String> head = List.of("Paket","Uploader","Jumlah Baris","Debit","Credit","Balance");
        List<List<Object>> data = new ArrayList<>();
        long totJml = 0; BigDecimal totD = BigDecimal.ZERO, totC = BigDecimal.ZERO;
        for (Object[] r : gl.approvedPakets()) {
            long jml = ((Number) r[2]).longValue();
            BigDecimal d = (BigDecimal) r[3], c = (BigDecimal) r[4];
            data.add(Arrays.asList(r[0], r[1], jml, d, c, d.compareTo(c) == 0 ? "Balance" : "Tidak Balance"));
            totJml += jml; totD = totD.add(d); totC = totC.add(c);
        }
        data.add(Arrays.asList("TOTAL", "", totJml, totD, totC, totD.compareTo(totC) == 0 ? "Balance" : "Tidak Balance"));
        excel.download(resp, "Rekapitulasi", "Rekap_GL", head, data, true);
        audit.transaction(me.getUsername(), "EXPORT_REKAP", "Export rekapitulasi GL ke Excel");
    }

    @GetMapping("/rekap")
    public String rekap(Model model) {
        List<Row> rows = new ArrayList<>();
        long totJml = 0; BigDecimal totD = BigDecimal.ZERO, totC = BigDecimal.ZERO; long maxJml = 1;
        for (Object[] r : gl.approvedPakets()) {
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
