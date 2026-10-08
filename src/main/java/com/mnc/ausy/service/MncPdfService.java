package com.mnc.ausy.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.math.BigDecimal;

/**
 * Potongan yang dipakai bersama semua PDF AUSY supaya tampilannya satu format MNC
 * (BUG_AUSY_00152 & BUG_AUSY_00153).
 *
 * Acuannya berkas jurnal AUSY yang dipakai MNC hari ini: kertas mendatar, logo MNC bank di
 * kiri atas, tiga baris judul di tengah, lalu tabel bergaris.
 */
@Service
public class MncPdfService {

    private static final Logger LOG = LoggerFactory.getLogger(MncPdfService.class);

    public static final String BANK_NAME = "PT. BANK MNC INTERNASIONAL, Tbk.";
    /** Nama berkas logo di classpath; satu strip berisi MNC bank + motionbank + kontak. */
    private static final String LOGO = "pdf/mnc-logo.pdf";

    /**
     * Bagian strip logo yang dipakai, dalam titik: hanya lambang "MNC bank" di kiri.
     * Berkas aslinya (293,4 x 66,5) juga memuat logo motionbank dan baris nomor telepon,
     * yang tidak ada di jurnal acuan — sisanya dipotong lewat clip, bukan diedit, supaya
     * berkas logonya tetap sama dengan yang diberikan MNC.
     */
    private static final float LOGO_X = 4f, LOGO_Y = 24f, LOGO_W = 124f, LOGO_H = 40f;

    public static final Font TITLE   = new Font(Font.HELVETICA, 10, Font.BOLD);
    public static final Font HEAD    = new Font(Font.HELVETICA, 6, Font.BOLD);
    public static final Font BODY    = new Font(Font.HELVETICA, 6);
    public static final Font FOOT    = new Font(Font.HELVETICA, 7);
    /**
     * Font yang sama untuk menulis langsung ke content stream (kaki halaman).
     * {@link Font} keluarga Helvetica belum punya BaseFont sampai dipakai lewat Paragraph,
     * jadi menulis manual butuh instance BaseFont sendiri.
     */
    public static final BaseFont FOOT_BASE = baseHelvetica();
    public static final float FOOT_SIZE = 7f;

    private static BaseFont baseHelvetica() {
        try {
            return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            LoggerFactory.getLogger(MncPdfService.class).warn("Font kaki halaman gagal dibuat: {}", e.getMessage());
            return null;
        }
    }
    public static final Font FOOT_B  = new Font(Font.HELVETICA, 7, Font.BOLD);

    // ---- Jurnal AUSY (REQ_AUSY_00030) --------------------------------------------------------
    // Sedikit lebih besar dari gaya umum di atas, yang juga dipakai PDF Audit Trail. Dipisah
    // supaya penyesuaian jurnal tidak ikut mengubah cetakan lain. Ukurannya masih bisa diatur
    // lagi setelah ditinjau QA; cukup ubah angka di bagian ini.
    public static final Font J_HEAD   = new Font(Font.HELVETICA, 7f, Font.BOLD);
    public static final Font J_BODY   = new Font(Font.HELVETICA, 7f);
    public static final Font J_FOOT   = new Font(Font.HELVETICA, 8.5f);
    public static final Font J_FOOT_B = new Font(Font.HELVETICA, 8.5f, Font.BOLD);
    /** Nomor paket & halaman di pojok kanan bawah: tebal dan lebih besar. */
    public static final BaseFont J_PAGE_BASE = baseHelveticaBold();
    public static final float J_PAGE_SIZE = 9f;

    private static BaseFont baseHelveticaBold() {
        try {
            return BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            LoggerFactory.getLogger(MncPdfService.class).warn("Font tebal kaki halaman gagal dibuat: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Angka seperti di jurnal MNC: {@code 1,642,798,00}.
     *
     * Pemisah ribuan dan desimalnya sama-sama koma — begitu apa adanya di berkas acuan yang
     * diberikan MNC, jadi ditiru persis supaya hasil cetaknya bisa disandingkan dengan yang
     * lama. Kalau ternyata itu keliru di sistem lamanya, satu baris ini tempat menggantinya.
     */
    public static String amount(BigDecimal v) {
        BigDecimal x = (v == null ? BigDecimal.ZERO : v).setScale(2, java.math.RoundingMode.HALF_UP);
        String[] p = x.abs().toPlainString().split("\\.");
        StringBuilder w = new StringBuilder(p[0]);
        for (int i = w.length() - 3; i > 0; i -= 3) w.insert(i, ',');
        return (x.signum() < 0 ? "-" : "") + w + "," + p[1];
    }

    /** Logo MNC bank di kiri atas halaman yang sedang terbuka. */
    public void stampLogo(PdfWriter writer, Document doc) {
        try (InputStream in = new ClassPathResource(LOGO).getInputStream()) {
            PdfReader reader = new PdfReader(in.readAllBytes());
            PdfImportedPage logo = writer.getImportedPage(reader, 1);
            PdfContentByte cb = writer.getDirectContent();
            float x = doc.leftMargin();
            float y = doc.top() - LOGO_H + 6f;
            cb.saveState();
            cb.rectangle(x, y, LOGO_W, LOGO_H);
            cb.clip();
            cb.newPath();
            // Geser template supaya bagian yang dipilih jatuh tepat di dalam kotak clip.
            cb.addTemplate(logo, 1f, 0, 0, 1f, x - LOGO_X, y - LOGO_Y);
            cb.restoreState();
        } catch (Exception e) {
            // Logo hilang tidak boleh menggagalkan cetakan — isinya yang penting.
            LOG.warn("Logo MNC tidak bisa dipasang di PDF: {}", e.getMessage());
        }
    }

    /**
     * Tiga baris judul di tengah halaman, sejajar logo.
     * {@code line3} boleh null untuk cetakan yang tidak punya baris keterangan.
     */
    public void title(Document doc, String line2, String line3) throws DocumentException {
        Paragraph p1 = new Paragraph(BANK_NAME, TITLE);
        p1.setAlignment(Element.ALIGN_CENTER);
        doc.add(p1);
        Paragraph p2 = new Paragraph(line2, TITLE);
        p2.setAlignment(Element.ALIGN_CENTER);
        doc.add(p2);
        if (line3 != null) {
            Paragraph p3 = new Paragraph(line3, TITLE);
            p3.setAlignment(Element.ALIGN_CENTER);
            doc.add(p3);
        }
        doc.add(new Paragraph(" "));
    }

    /** Sel judul kolom: teks tebal kecil, rata kiri, sama seperti jurnal acuan. */
    public PdfPCell head(String text) {
        PdfPCell c = new PdfPCell(new Phrase(text, HEAD));
        c.setPadding(2f);
        return c;
    }

    /** Sel isi biasa. */
    public PdfPCell cell(String text) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, BODY));
        c.setPadding(2f);
        return c;
    }

    /** Sel isi rata kanan — dipakai kolom nominal. */
    public PdfPCell cellRight(String text) {
        PdfPCell c = cell(text);
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return c;
    }

    /** Sel dengan font tertentu — dipakai jurnal yang punya gaya sendiri (REQ_AUSY_00030). */
    public PdfPCell cell(String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, font));
        c.setPadding(2.5f);
        return c;
    }

    /**
     * Kotak tanda tangan jurnal dengan garis di bawah labelnya, supaya terbaca sebagai tabel
     * (REQ_AUSY_00030): baris label di atas, ruang tanda tangan kosong di bawahnya.
     */
    public PdfPCell signTable(String label) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        PdfPCell head = new PdfPCell(new Phrase(label, J_FOOT_B));
        head.setPadding(3f);
        t.addCell(head);
        PdfPCell space = new PdfPCell();
        space.setFixedHeight(44f);
        t.addCell(space);
        PdfPCell wrap = new PdfPCell(t);
        wrap.setBorder(com.lowagie.text.Rectangle.NO_BORDER);
        wrap.setPaddingLeft(4f);
        wrap.setPaddingRight(4f);
        return wrap;
    }

    /** Kotak tanda tangan kosong seperti "Dibuat / Diperiksa / Disetujui" di jurnal. */
    public PdfPCell signBox(String label) {
        PdfPCell c = new PdfPCell();
        c.addElement(new Paragraph(label, FOOT));
        c.setFixedHeight(48f);
        c.setPadding(3f);
        return c;
    }
}
