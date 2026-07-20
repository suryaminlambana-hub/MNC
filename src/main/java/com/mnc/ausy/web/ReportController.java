package com.mnc.ausy.web;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.mnc.ausy.domain.GlTabel;
import com.mnc.ausy.repo.GlTabelRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@Controller
public class ReportController {
    private final GlTabelRepository gl;
    public ReportController(GlTabelRepository gl) { this.gl = gl; }

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
        doc.add(new Paragraph("Paket: " + paket, sm));
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
