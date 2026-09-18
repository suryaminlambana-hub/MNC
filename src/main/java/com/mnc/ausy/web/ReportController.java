package com.mnc.ausy.web;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.mnc.ausy.domain.GlTabel;
import com.mnc.ausy.repo.GlTabelRepository;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.AuditService;
import com.mnc.ausy.service.DataScopeService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Controller
public class ReportController {
    private final GlTabelRepository gl;
    private final DataScopeService scope;
    private final AuditService audit;

    public ReportController(GlTabelRepository gl, DataScopeService scope, AuditService audit) {
        this.gl = gl; this.scope = scope; this.audit = audit;
    }

    /**
     * BUG_AUSY_00137 — unduh isi halaman List GL Aktif sebagai PDF.
     *
     * Alamatnya sengaja berada di bawah {@code /gl-legacy} supaya hak aksesnya otomatis mengikuti
     * menu List GL Aktif (AccessService memakai pemilik URL terpanjang); kalau diletakkan di
     * {@code /report/...} endpoint ini tidak dimiliki menu mana pun dan akan terbuka untuk semua
     * yang login. Isinya persis yang terlihat di layar, termasuk batasan per maker (BUG_AUSY_00138).
     */
    @GetMapping("/gl-legacy/pdf")
    public void listGlAktif(@AuthenticationPrincipal AusyUserDetails me, HttpServletResponse resp) throws Exception {
        List<GlTabel> rows = scope.filterRows(me, gl.findAll().stream()
                .filter(r -> r.getActive() != null && r.getActive() == 1 && !"PENDING".equals(r.getStatusDual()))
                .sorted(Comparator.comparing(GlTabel::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList()));

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        resp.setContentType("application/pdf");
        resp.setHeader("Content-Disposition", "attachment; filename=\"List_GL_Aktif_" + stamp + ".pdf\"");

        Document doc = new Document(PageSize.A4.rotate(), 24, 24, 24, 24);
        PdfWriter.getInstance(doc, resp.getOutputStream());
        doc.open();
        Font h = new Font(Font.HELVETICA, 14, Font.BOLD);
        Font sm = new Font(Font.HELVETICA, 7);
        Font smb = new Font(Font.HELVETICA, 7, Font.BOLD);

        doc.add(new Paragraph("MNC BANK — List GL Aktif", h));
        doc.add(new Paragraph("Dicetak: " + LocalDateTime.now().format(PRINTED)
                + " oleh " + (me == null ? "-" : me.getUsername()), sm));
        doc.add(new Paragraph(" "));

        PdfPTable t = new PdfPTable(new float[]{1.2f, 1.2f, 1.6f, .7f, 3f, 3.4f, 2.2f, 1.4f, 1.6f});
        t.setWidthPercentage(100);
        for (String head : new String[]{"ID","Batch","NoGL","D/C","GL Name","Description","Amount","Tgl","Uploader"}) {
            PdfPCell c = new PdfPCell(new Phrase(head, smb)); c.setGrayFill(0.9f); t.addCell(c);
        }
        BigDecimal d = BigDecimal.ZERO, c2 = BigDecimal.ZERO;
        for (GlTabel g : rows) {
            t.addCell(new Phrase(g.getId() == null ? "" : String.valueOf(g.getId()), sm));
            t.addCell(new Phrase(nz(g.getNoPaket()), sm));
            t.addCell(new Phrase(nz(g.getGltrac()), sm));
            t.addCell(new Phrase(nz(g.getGltrcd()), sm));
            t.addCell(new Phrase(nz(g.getGlName()), sm));
            t.addCell(new Phrase(nz(g.getGltrds()), sm));
            PdfPCell amt = new PdfPCell(new Phrase(g.getGltram()==null?"0":g.getGltram().toPlainString(), sm));
            amt.setHorizontalAlignment(Element.ALIGN_RIGHT); t.addCell(amt);
            t.addCell(new Phrase(nz(g.getGltrpd()), sm));
            t.addCell(new Phrase(nz(g.getGltrus()), sm));
            if ("D".equalsIgnoreCase(g.getGltrcd())) d = d.add(nzv(g.getGltram())); else c2 = c2.add(nzv(g.getGltram()));
        }
        doc.add(t);
        doc.add(new Paragraph(" "));
        doc.add(new Paragraph("Jumlah baris : " + rows.size(), smb));
        doc.add(new Paragraph("Total Debit  : " + d.toPlainString(), smb));
        doc.add(new Paragraph("Total Credit : " + c2.toPlainString(), smb));
        doc.close();

        if (me != null)
            audit.transaction(me.getUsername(), "EXPORT_GL_PDF",
                    "Unduh List GL Aktif sebagai PDF: " + rows.size() + " baris");
    }

    private static final DateTimeFormatter PRINTED = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    @GetMapping("/report/jurnal/{paket}")
    public void jurnal(@PathVariable String paket, HttpServletResponse resp) throws Exception {
        List<GlTabel> rows = gl.findByNoPaketOrderById(paket);
        resp.setContentType("application/pdf");
        resp.setHeader("Content-Disposition", "inline; filename=\"jurnal_" + paket + ".pdf\"");

        Document doc = new Document(PageSize.A4.rotate(), 28, 28, 28, 28);
        PdfWriter.getInstance(doc, resp.getOutputStream());
        doc.open();
        Font h = new Font(Font.HELVETICA, 14, Font.BOLD);
        Font sm = new Font(Font.HELVETICA, 8);
        Font smb = new Font(Font.HELVETICA, 8, Font.BOLD);
        doc.add(new Paragraph("MNC BANK — Jurnal GL", h));
        doc.add(new Paragraph("Batch: " + paket, sm));
        doc.add(new Paragraph(" "));

        PdfPTable t = new PdfPTable(new float[]{2,3,1,2,3,3});
        t.setWidthPercentage(100);
        for (String head : new String[]{"NoGL","GL Name","D/C","Cabang","Description","Amount"}) {
            PdfPCell c = new PdfPCell(new Phrase(head, smb)); c.setGrayFill(0.9f); t.addCell(c);
        }
        BigDecimal d = BigDecimal.ZERO, c2 = BigDecimal.ZERO;
        for (GlTabel g : rows) {
            t.addCell(new Phrase(nz(g.getGltrac()), sm));
            t.addCell(new Phrase(nz(g.getGlName()), sm));
            t.addCell(new Phrase(nz(g.getGltrcd()), sm));
            t.addCell(new Phrase(nz(g.getGltrbr()), sm));
            t.addCell(new Phrase(nz(g.getGltrds()), sm));
            PdfPCell amt = new PdfPCell(new Phrase(g.getGltram()==null?"0":g.getGltram().toPlainString(), sm));
            amt.setHorizontalAlignment(Element.ALIGN_RIGHT); t.addCell(amt);
            if ("D".equalsIgnoreCase(g.getGltrcd())) d = d.add(nzv(g.getGltram())); else c2 = c2.add(nzv(g.getGltram()));
        }
        doc.add(t);
        doc.add(new Paragraph(" "));
        doc.add(new Paragraph("Total Debit : " + d.toPlainString(), smb));
        doc.add(new Paragraph("Total Credit: " + c2.toPlainString(), smb));
        doc.add(new Paragraph("Status: " + (d.compareTo(c2)==0 ? "BALANCE" : "TIDAK BALANCE"), smb));
        doc.close();
    }

    private static String nz(String s){ return s==null?"":s; }
    private static BigDecimal nzv(BigDecimal v){ return v==null?BigDecimal.ZERO:v; }
}
