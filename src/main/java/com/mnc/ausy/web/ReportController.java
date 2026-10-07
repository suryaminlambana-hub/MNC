package com.mnc.ausy.web;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import com.mnc.ausy.domain.GlTabel;
import com.mnc.ausy.repo.GlTabelRepository;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.AuditService;
import com.mnc.ausy.service.DataScopeService;
import com.mnc.ausy.service.MncPdfService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cetakan jurnal AUSY dalam format MNC (BUG_AUSY_00153).
 *
 * Tata letaknya mengikuti berkas jurnal yang dipakai MNC sekarang: kertas mendatar, logo
 * MNC bank di kiri atas, tiga baris judul di tengah, tabel 14 kolom, lalu baris kaki berisi
 * user input, kotak Dibuat / Diperiksa / Disetujui dan TOTAL Dr / Cr / SELISIH.
 *
 * Satu batch = satu cetakan jurnal. Halaman List GL Aktif bisa memuat beberapa batch
 * sekaligus, jadi PDF-nya berisi satu jurnal per batch, masing-masing di halaman sendiri —
 * bukan satu tabel campur, karena jurnal MNC selalu dibaca per batch.
 */
@Controller
public class ReportController {

    private final GlTabelRepository gl;
    private final DataScopeService scope;
    private final AuditService audit;
    private final MncPdfService mnc;

    public ReportController(GlTabelRepository gl, DataScopeService scope, AuditService audit, MncPdfService mnc) {
        this.gl = gl; this.scope = scope; this.audit = audit; this.mnc = mnc;
    }

    /** Judul kolom jurnal MNC — dua baris persis seperti berkas acuan. */
    private static final String[][] COLS = {
            {"Posting", "Date"}, {"D/C", ""}, {"Tr.", "Account"}, {"GL Name", ""},
            {"Flag", ""}, {"Branch", ""}, {"Branch Name", ""}, {"Product", ""},
            {"Product Name", ""}, {"Cost", "Centre"}, {"Cost Centre", "Name"},
            {"Curren", "cy"}, {"Transaction", "Amount"}, {"Transaction Description", ""}
    };
    private static final float[] WIDTHS = {1.5f, .5f, 1.3f, 3.2f, .6f, .8f, 2.6f, .9f, 1.4f, .9f, 1.3f, .8f, 1.6f, 4.2f};

    /** Tanggal di kolom pertama: 29-May-23, seperti jurnal acuan. */
    private static final DateTimeFormatter ROW_DATE  = DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH);
    private static final DateTimeFormatter DDMMYY_IN = DateTimeFormatter.ofPattern("ddMMyy");
    private static final DateTimeFormatter FOOT_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * REQ_AUSY_00014 — jurnal PDF SATU batch upload yang masih menunggu otorisasi checker.
     *
     * Menggantikan tombol "Download PDF" seluruh List GL Aktif (dicabut di REQ_AUSY_00013): PDF
     * dibuat per batch supaya isinya tidak tercampur dengan batch lain.
     *
     * Alamatnya sengaja di bawah {@code /gl-legacy} supaya hak aksesnya mengikuti menu List GL
     * Aktif (AccessService memakai pemilik URL terpanjang). Batch dicari dari daftar pending yang
     * sudah disaring per maker, jadi maker hanya bisa mengunduh batch miliknya sendiri; nomor
     * batch lain menghasilkan 404, bukan isi batch orang lain.
     */
    @GetMapping("/gl-legacy/pending/{paket}/pdf")
    public void pendingBatch(@PathVariable String paket, @AuthenticationPrincipal AusyUserDetails me,
                             HttpServletResponse resp) throws Exception {
        String maker = null;
        for (Object[] p : scope.filterArrays(me, gl.pendingPakets(), 1))
            if (paket.equals(String.valueOf(p[0]))) { maker = String.valueOf(p[1]); break; }
        if (maker == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND,
                    "Batch " + paket + " tidak ada di daftar upload yang menunggu otorisasi Anda.");
            return;
        }
        List<GlTabel> rows = gl.findBatchRows(paket, maker).stream()
                .filter(g -> "PENDING".equals(g.getStatusDual()) && Short.valueOf((short) 1).equals(g.getActive()))
                .toList();
        resp.setContentType("application/pdf");
        resp.setHeader("Content-Disposition", "attachment; filename=\"Jurnal_Batch_" + paket + "_pending.pdf\"");
        writeJournals(resp, rows);

        if (me != null)
            audit.transaction(me.getUsername(), "EXPORT_GL_PDF",
                    "Unduh PDF Batch " + paket + " (menunggu otorisasi): " + rows.size() + " baris");
    }

    @GetMapping("/report/jurnal/{paket}")
    public void jurnal(@PathVariable String paket, HttpServletResponse resp) throws Exception {
        List<GlTabel> rows = gl.findByNoPaketOrderById(paket);
        resp.setContentType("application/pdf");
        resp.setHeader("Content-Disposition", "inline; filename=\"jurnal_" + paket + ".pdf\"");
        writeJournals(resp, rows);
    }

    /** Satu dokumen, satu jurnal per batch. */
    private void writeJournals(HttpServletResponse resp, List<GlTabel> rows) throws Exception {
        Document doc = new Document(PageSize.A4.rotate(), 24, 24, 24, 30);
        PdfWriter writer = PdfWriter.getInstance(doc, resp.getOutputStream());
        PageFooter footer = new PageFooter();
        writer.setPageEvent(footer);
        doc.open();

        // Urutan batch dipertahankan seperti datangnya dari query supaya cetakannya bisa
        // disandingkan baris per baris dengan layar.
        Map<String, List<GlTabel>> byPaket = new LinkedHashMap<>();
        for (GlTabel g : rows) byPaket.computeIfAbsent(nz(g.getNoPaket()), k -> new ArrayList<>()).add(g);

        if (byPaket.isEmpty()) {
            footer.paket = "-";
            mnc.stampLogo(writer, doc);
            mnc.title(doc, "JURNAL \" AUSY \" ( ACCOUNTING UPLOAD SYSTEM )", null);
            doc.add(new Paragraph("Tidak ada data GL aktif untuk dicetak.", MncPdfService.FOOT));
            doc.close();
            return;
        }

        boolean first = true;
        for (Map.Entry<String, List<GlTabel>> e : byPaket.entrySet()) {
            // newPage() DULU, baru nomor batchnya diganti: kaki halaman ditulis pada saat
            // halaman ditutup, jadi halaman terakhir sebuah batch harus masih memegang nomor
            // batch itu. Kebalik sedikit saja dan kaki halaman menyebut batch berikutnya.
            if (!first) doc.newPage();
            footer.paket = e.getKey();
            first = false;
            journal(writer, doc, e.getKey(), e.getValue());
        }
        doc.close();
    }

    private void journal(PdfWriter writer, Document doc, String paket, List<GlTabel> rows) throws DocumentException {
        mnc.stampLogo(writer, doc);
        mnc.title(doc, "JURNAL \" AUSY \" ( ACCOUNTING UPLOAD SYSTEM )",
                "Di Proses pada No. Transmisi : " + transmisi(rows) + ", No Paket : " + paket);

        PdfPTable t = new PdfPTable(WIDTHS);
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        for (String[] c : COLS) {
            PdfPCell cell = mnc.head(c[0] + (c[1].isEmpty() ? "" : "\n" + c[1]));
            t.addCell(cell);
        }

        BigDecimal d = BigDecimal.ZERO, c = BigDecimal.ZERO;
        for (GlTabel g : rows) {
            t.addCell(mnc.cell(postingDate(g)));
            t.addCell(mnc.cell(nz(g.getGltrcd())));
            t.addCell(mnc.cell(nz(g.getGltrac())));
            t.addCell(mnc.cell(nz(g.getGlName())));
            t.addCell(mnc.cell(nz(g.getGlflag())));
            t.addCell(mnc.cell(nz(g.getGltrbr())));
            t.addCell(mnc.cell(nz(g.getBrName())));
            t.addCell(mnc.cell(nz(g.getGlprdc())));
            t.addCell(mnc.cell(""));                      // Product Name: tidak ada di data, kosong seperti acuan
            t.addCell(mnc.cell(nz(g.getGltrcc())));
            t.addCell(mnc.cell(nz(g.getCsName())));
            t.addCell(mnc.cell(nz(g.getGltrcy())));
            t.addCell(mnc.cellRight(MncPdfService.amount(g.getGltram())));
            t.addCell(mnc.cell(nz(g.getGltrds())));
            if ("D".equalsIgnoreCase(g.getGltrcd())) d = d.add(nzv(g.getGltram()));
            else c = c.add(nzv(g.getGltram()));
        }
        doc.add(t);
        doc.add(new Paragraph(" "));
        doc.add(footer(rows, d, c));
    }

    /**
     * Kaki halaman "Paket 01    Halaman 1 / 3" di kanan bawah, seperti jurnal acuan.
     *
     * Jumlah halaman belum diketahui saat halaman ditulis, jadi tempatnya disediakan sebagai
     * template kosong lalu diisi ketika dokumen ditutup — cara baku OpenPDF untuk "N dari M".
     */
    private static class PageFooter extends PdfPageEventHelper {
        String paket = "";
        private PdfTemplate total;

        @Override public void onOpenDocument(PdfWriter writer, Document doc) {
            total = writer.getDirectContent().createTemplate(30, 10);
        }

        @Override public void onEndPage(PdfWriter writer, Document doc) {
            if (MncPdfService.FOOT_BASE == null) return;
            PdfContentByte cb = writer.getDirectContent();
            String text = "Paket " + paket + "    Halaman " + writer.getPageNumber() + " / ";
            float y = doc.bottom() - 12f;
            float right = doc.right();
            cb.beginText();
            cb.setFontAndSize(MncPdfService.FOOT_BASE, MncPdfService.FOOT_SIZE);
            // Tempat untuk total digeser ke kiri sebanyak lebarnya supaya seluruh baris rata kanan.
            cb.showTextAligned(Element.ALIGN_RIGHT, text, right - 16f, y, 0);
            cb.endText();
            cb.addTemplate(total, right - 16f, y);
        }

        @Override public void onCloseDocument(PdfWriter writer, Document doc) {
            if (MncPdfService.FOOT_BASE == null) return;
            total.beginText();
            total.setFontAndSize(MncPdfService.FOOT_BASE, MncPdfService.FOOT_SIZE);
            // Saat dokumen ditutup, penghitungnya sudah maju ke halaman BERIKUTNYA yang tidak
            // pernah jadi — jumlah halaman sebenarnya satu kurangnya.
            total.showText(String.valueOf(writer.getPageNumber() - 1));
            total.endText();
        }
    }

    /**
     * Baris kaki jurnal: keterangan pembuat di kiri, tiga kotak tanda tangan di tengah,
     * total di kanan — disusun sebagai satu tabel tanpa garis supaya sejajar seperti acuan.
     */
    private PdfPTable footer(List<GlTabel> rows, BigDecimal d, BigDecimal c) {
        PdfPTable f = new PdfPTable(new float[]{2.2f, 2f, 2f, 2f, 2.4f});
        f.setWidthPercentage(100);

        PdfPCell who = new PdfPCell();
        who.setBorder(Rectangle.NO_BORDER);
        who.addElement(new Paragraph("User Input : " + nz(rows.isEmpty() ? "" : rows.get(0).getGltrus()), MncPdfService.FOOT));
        who.addElement(new Paragraph("Tanggal     : " + footDate(rows), MncPdfService.FOOT));
        f.addCell(who);

        for (String s : new String[]{"Dibuat :", "Diperiksa :", "Disetujui :"}) f.addCell(mnc.signBox(s));

        PdfPCell tot = new PdfPCell();
        tot.setBorder(Rectangle.NO_BORDER);
        tot.addElement(totalLine("TOTAL Dr :", d));
        tot.addElement(totalLine("TOTAL Cr :", c));
        tot.addElement(totalLine("SELISIH    :", d.subtract(c)));
        f.addCell(tot);
        return f;
    }

    private static Paragraph totalLine(String label, BigDecimal v) {
        Paragraph p = new Paragraph(label + "  " + MncPdfService.amount(v), MncPdfService.FOOT_B);
        p.setAlignment(Element.ALIGN_RIGHT);
        return p;
    }

    /**
     * Nomor transmisi diambil dari bagian tengah GLTRRF ({@code <paket>-<transmisi>-<urut>}),
     * kolom yang sama yang dipakai berkas ACCTXT.
     */
    private static String transmisi(List<GlTabel> rows) {
        for (GlTabel g : rows) {
            String ref = nz(g.getGltrrf());
            String[] part = ref.split("-");
            if (part.length >= 2 && !part[1].isBlank()) return part[1];
        }
        return "01";
    }

    /** GLTRPD disimpan sebagai ddMMyy; jurnal menampilkannya 29-May-23. */
    private static String postingDate(GlTabel g) {
        String raw = nz(g.getGltrpd());
        try { return LocalDate.parse(raw, DDMMYY_IN).format(ROW_DATE); }
        catch (Exception e) { return raw; }
    }

    private static String footDate(List<GlTabel> rows) {
        for (GlTabel g : rows) {
            try { return LocalDate.parse(nz(g.getGltrpd()), DDMMYY_IN).format(FOOT_DATE); }
            catch (Exception ignored) { }
        }
        return LocalDate.now().format(FOOT_DATE);
    }

    private static String nz(String s){ return s==null?"":s; }
    private static BigDecimal nzv(BigDecimal v){ return v==null?BigDecimal.ZERO:v; }
}
