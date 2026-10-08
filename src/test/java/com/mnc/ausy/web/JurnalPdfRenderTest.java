package com.mnc.ausy.web;

import com.mnc.ausy.domain.GlTabel;
import com.mnc.ausy.service.MncPdfService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REQ_AUSY_00030 — cetakan jurnal tetap bisa dibuat setelah tata letaknya disesuaikan.
 * Hasilnya disimpan ke target/jurnal-sample.pdf supaya bisa diperiksa mata.
 */
class JurnalPdfRenderTest {

    private static GlTabel row(String dc, String acc, String name, String amount) {
        GlTabel g = new GlTabel();
        g.setNoPaket("01");
        g.setGltrcd(dc);
        g.setGltrac(acc);
        g.setGlName(name);
        g.setGlflag("2");
        g.setGltrbr("1");
        g.setBrName("CAB.KPNO MENARA ICB BUMIPUTERA");
        g.setGltrcc("83000");
        g.setGltrcy("IDR");
        g.setGltram(new BigDecimal(amount));
        g.setGltrds("C23051223N MNCLAND SRV FIN 6 7 8 3.111.");
        g.setGltrpd("290523");
        g.setGltrus("1503291");
        g.setGltrrf("01-01-0001");
        return g;
    }

    @Test
    void jurnalTerbentuk() throws Exception {
        List<GlTabel> rows = new ArrayList<>(List.of(
                row("D", "51100004", "BBN GEDUNG-PERAWATAN GEDUNG", "821399.00"),
                row("D", "51100004", "BBN GEDUNG-PERAWATAN GEDUNG", "821399.00"),
                row("C", "51100004", "BBN GEDUNG-PERAWATAN GEDUNG", "821392.00"),
                row("C", "51100004", "BBN GEDUNG-PERAWATAN GEDUNG", "821392.00"),
                row("C", "20999154", "KLL AKUN SUSP BUKU BESAR", "7.00"),
                row("C", "20999154", "KLL AKUN SUSP BUKU BESAR", "7.00")));

        ReportController rc = new ReportController(null, null, null, new MncPdfService());
        Method m = ReportController.class.getDeclaredMethod("writeJournals",
                jakarta.servlet.http.HttpServletResponse.class, List.class);
        m.setAccessible(true);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        m.invoke(rc, resp, rows);

        byte[] pdf = resp.getContentAsByteArray();
        assertTrue(pdf.length > 1000, "PDF terlalu kecil");
        assertEquals("%PDF", new String(pdf, 0, 4));
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target", "jurnal-sample.pdf"), pdf);
    }
}
