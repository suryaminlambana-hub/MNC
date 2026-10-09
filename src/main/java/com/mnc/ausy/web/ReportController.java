package com.mnc.ausy.web;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import com.mnc.ausy.service.BatchLabel;
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
            {"Posting", "Date"}, {"D/C", ""}, {"GL", "Number"}, {"GL Name", ""},   // REQ_AUSY_00030: dulu "Tr. Account"
            {"Flag", ""}, {"Branch", ""}, {"Branch Name", ""}, {"Product", ""},
            {"Product Name", ""}, {"Cost", "Centre"}, {"Cost Centre", "Name"},
            {"Curren", "cy"}, {"Transaction", "Amount"}, {"Transaction Description", ""}
    };
    // REQ_AUSY_00030: Branch, Product dan Currency dilebarkan (diambil dari Description) supaya
    // judul kolomnya tidak terpotong di tengah kata dengan font jurnal yang lebih besar.
    private static final float[] WIDTHS = {1.5f, .5f, 1.3f, 2.8f, .6f, 1.0f, 2.5f, 1.0f, 1.4f, .9f, 1.3f, 1.0f, 1.6f, 4.2f};

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
        // REQ_AUSY_00053: batch milik sendiri yang ditolak checker juga bisa dilihat PDF-nya.
        if (maker == null && me != null)
            for (Object[] p : gl.rejectedPakets(me.getUsername()))
                if (paket.equals(String.valueOf(p[0]))) { maker = me.getUsername(); break; }
        if (maker == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND,
                    "Batch " + BatchLabel.label(paket) + " tidak ada di daftar upload yang menunggu otorisasi Anda.");
            return;
        }
        List<GlTabel> rows = gl.findBatchRows(paket, maker).stream()
                .filter(g -> ("PENDING".equals(g.getStatusDual()) && Short.valueOf((short) 1).equals(g.getActive()))
                        || "REJECTED".equals(g.getStatusDual()))
                .toList();
        resp.setContentType("application/pdf");
        resp.setHeader("Content-Disposition", "attachment; filename=\"Jurnal_Batch_" + paket + "_pending.pdf\"");
        writeJournals(resp, rows);

        if (me != null)
            audit.transaction(me.getUsername(), "EXPORT_GL_PDF",
                    "Unduh PDF Batch " + BatchLabel.label(paket) + " (menunggu otorisasi): " + rows.size() + " baris");
    }

    // REQ_AUSY_00023 PDF batch GL Aktif (/gl-legacy/batch-pdf) dicabut bersama tabel "Data GL Sudah
    // Diotorisasi" di List Data GL (REQ_AUSY_00036 feedback): tidak ada lagi yang memakainya.

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
            mnc.stampLogo(writer, doc, MncPdfService.J_LOGO_SCALE);
            mnc.title(doc, "JURNAL \" AUSY \" ( ACCOUNTING UPLOAD SYSTEM )", null, MncPdfService.J_TITLE);
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
            footer.paket = BatchLabel.label(e.getKey());
            first = false;
            journal(writer, doc, e.getKey(), e.getValue());
        }
        doc.close();
    }

    private void journal(PdfWriter writer, Document doc, String paket, List<GlTabel> rows) throws DocumentException {
        mnc.stampLogo(writer, doc, MncPdfService.J_LOGO_SCALE);
        mnc.title(doc, "JURNAL \" AUSY \" ( ACCOUNTING UPLOAD SYSTEM )",
                "Di Proses pada No. Transmisi : " + transmisi(rows) + ", No Paket : " + BatchLabel.label(paket),
                MncPdfService.J_TITLE);

        // REQ_AUSY_00030: font jurnal sendiri (sedikit lebih besar) dan jarak antara judul,
        // tabel, dan kaki diperlebar supaya tidak terlalu rapat.
        PdfPTable t = new PdfPTable(WIDTHS);
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        t.setSpacingBefore(4f);
        for (String[] c : COLS)
            t.addCell(mnc.cell(c[0] + (c[1].isEmpty() ? "" : "\n" + c[1]), MncPdfService.J_HEAD));

        BigDecimal d = BigDecimal.ZERO, c = BigDecimal.ZERO;
        for (GlTabel g : rows) {
            t.addCell(body(postingDate(g)));
            t.addCell(body(nz(g.getGltrcd())));
            t.addCell(body(nz(g.getGltrac())));
            t.addCell(body(nz(g.getGlName())));
            t.addCell(body(nz(g.getGlflag())));
            t.addCell(body(nz(g.getGltrbr())));
            t.addCell(body(nz(g.getBrName())));
            t.addCell(body(nz(g.getGlprdc())));
            t.addCell(body(""));                          // Product Name: tidak ada di data, kosong seperti acuan
            t.addCell(body(nz(g.getGltrcc())));
            t.addCell(body(nz(g.getCsName())));
            t.addCell(body(nz(g.getGltrcy())));
            PdfPCell amt = body(MncPdfService.amount(g.getGltram()));
            amt.setHorizontalAlignment(Element.ALIGN_RIGHT);
            t.addCell(amt);
            t.addCell(body(nz(g.getGltrds())));
            if ("D".equalsIgnoreCase(g.getGltrcd())) d = d.add(nzv(g.getGltram()));
            else c = c.add(nzv(g.getGltram()));
        }
        doc.add(t);
        PdfPTable foot = footer(rows, d, c);
        foot.setSpacingBefore(14f);
        doc.add(foot);
    }

    private PdfPCell body(String text) { return mnc.cell(text, MncPdfService.J_BODY); }

    /**
     * Kaki halaman "Paket 01    Halaman 1 / 3" di kanan bawah, seperti jurnal acuan.
     *
     * Jumlah halaman belum diketahui saat halaman ditulis, jadi tempatnya disediakan sebagai
     * template kosong lalu diisi ketika dokumen ditutup — cara baku OpenPDF untuk "N dari M".
     */
    private static class PageFooter extends PdfPageEventHelper {
        String paket = "";
        private PdfTemplate total;

        /** Nomor paket & halaman tebal dan lebih besar (REQ_AUSY_00030); cadangan: font biasa. */
        private static com.lowagie.text.pdf.BaseFont font() {
            return MncPdfService.J_PAGE_BASE != null ? MncPdfService.J_PAGE_BASE : MncPdfService.FOOT_BASE;
        }

        @Override public void onOpenDocument(PdfWriter writer, Document doc) {
            total = writer.getDirectContent().createTemplate(30, 12);
        }

        @Override public void onEndPage(PdfWriter writer, Document doc) {
            if (font() == null) return;
            PdfContentByte cb = writer.getDirectContent();
            String text = "Paket " + paket + "    Halaman " + writer.getPageNumber() + " / ";
            float y = doc.bottom() - 12f;
            float right = doc.right();
            cb.beginText();
            cb.setFontAndSize(font(), MncPdfService.J_PAGE_SIZE);
            // Tempat untuk total digeser ke kiri sebanyak lebarnya supaya seluruh baris rata kanan.
            cb.showTextAligned(Element.ALIGN_RIGHT, text, right - 16f, y, 0);
            cb.endText();
            cb.addTemplate(total, right - 16f, y);
        }

        @Override public void onCloseDocument(PdfWriter writer, Document doc) {
            if (font() == null) return;
            total.beginText();
            total.setFontAndSize(font(), MncPdfService.J_PAGE_SIZE);
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

        // REQ_AUSY_00030: "User Input" dan "Tanggal" tebal & lebih besar, label dan isinya sejajar
        // sebagai tabel dua kolom (dulu disejajarkan dengan spasi, yang meleset di font proporsional).
        PdfPTable whoT = new PdfPTable(new float[]{1f, 1.6f});
        whoT.setWidthPercentage(100);
        whoT.addCell(plain("User Input", MncPdfService.J_FOOT_B, Element.ALIGN_LEFT));
        whoT.addCell(plain(": " + nz(rows.isEmpty() ? "" : rows.get(0).getGltrus()), MncPdfService.J_FOOT_B, Element.ALIGN_LEFT));
        whoT.addCell(plain("Tanggal", MncPdfService.J_FOOT_B, Element.ALIGN_LEFT));
        whoT.addCell(plain(": " + footDate(rows), MncPdfService.J_FOOT_B, Element.ALIGN_LEFT));
        PdfPCell who = new PdfPCell(whoT);
        who.setBorder(Rectangle.NO_BORDER);
        f.addCell(who);

        // Kotak tanda tangan dengan garis di bawah labelnya, supaya tampak seperti tabel.
        for (String s : new String[]{"Dibuat :", "Diperiksa :", "Disetujui :"}) f.addCell(mnc.signTable(s));

        // TOTAL Dr / TOTAL Cr / SELISIH sebagai tabel dua kolom: labelnya sejajar, angkanya
        // rata kanan di satu kolom. Dulu SELISIH disejajarkan dengan spasi dan meleset.
        PdfPTable totT = new PdfPTable(new float[]{1f, 1.4f});
        totT.setWidthPercentage(100);
        totalRow(totT, "TOTAL Dr", d);
        totalRow(totT, "TOTAL Cr", c);
        totalRow(totT, "SELISIH", d.subtract(c));
        PdfPCell tot = new PdfPCell(totT);
        tot.setBorder(Rectangle.NO_BORDER);
        f.addCell(tot);
        return f;
    }

    private static PdfPCell plain(String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBorder(Rectangle.NO_BORDER);
        c.setHorizontalAlignment(align);
        c.setPadding(1.5f);
        return c;
    }

    private static void totalRow(PdfPTable t, String label, BigDecimal v) {
        t.addCell(plain(label + " :", MncPdfService.J_FOOT_B, Element.ALIGN_LEFT));
        t.addCell(plain(MncPdfService.amount(v), MncPdfService.J_FOOT_B, Element.ALIGN_RIGHT));
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
