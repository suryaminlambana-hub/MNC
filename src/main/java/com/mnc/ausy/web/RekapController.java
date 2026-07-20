package com.mnc.ausy.web;

import com.mnc.ausy.repo.GlTabelRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.math.BigDecimal;
import java.util.*;

/** Monitoring & Rekapitulasi (PDF §2.2 #3): recap of GL pakets awaiting authorization. */
@Controller
public class RekapController {
    private final GlTabelRepository gl;
    public RekapController(GlTabelRepository gl) { this.gl = gl; }

    public record Row(String paket, String uploader, long jml, BigDecimal debit, BigDecimal credit, boolean balanced) {}

    @GetMapping("/rekap")
    public String rekap(Model model) {
        List<Row> rows = new ArrayList<>();
        long totJml = 0; BigDecimal totD = BigDecimal.ZERO, totC = BigDecimal.ZERO; long maxJml = 1;
        for (Object[] r : gl.pendingPakets()) {
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
