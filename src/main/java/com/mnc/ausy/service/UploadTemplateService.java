package com.mnc.ausy.service;

import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Template Excel untuk upload GL. Dipakai halaman Upload Excel maupun NALA Seamless Upload —
 * formatnya sama, jadi satu sumber saja supaya tidak pernah beda isi (BUG_AUSY_00122).
 *
 * Tanggal contoh sengaja diisi tanggal hari ini: validasi upload menolak tanggal posting yang
 * bukan hari ini, jadi template statis dengan tanggal mati selalu gagal dipakai.
 */
@Service
public class UploadTemplateService {

    private static final Logger LOG = LoggerFactory.getLogger(UploadTemplateService.class);

    private static final String[] HEADERS = {
            "Posting Date", "D/C", "Product Code", "Product Name", "Transaction Account", "GL Name",
            "Transaction Description", "Branch", "Branch Name", "Cost Center", "Cost Center Name",
            "Currency", "Foreign Curr", "Transaction Amount", "Flag", "Responding Branch",
            "Responding Branch Name"
    };

    public void download(HttpServletResponse resp) throws Exception {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("d-MMM-yy", Locale.ENGLISH));
        try (Workbook wb = new XSSFWorkbook()) {
            // Urutan sheet TIDAK boleh berubah: pembaca upload mengambil sheet ke-5 (indeks 4).
            wb.createSheet("Cover");
            productCodeSheet(wb);                       // BUG_AUSY_00154, menggantikan "Info1" yang kosong
            wb.createSheet("Info2"); wb.createSheet("Info3");
            Sheet d = wb.createSheet("DATA");
            Row hr = d.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) hr.createCell(i).setCellValue(HEADERS[i]);
            String[][] rows = {
                {today,"D","20999154","BY KOMUNIKASI DATA","900","KANTOR PUSAT OPERASIONAL","BIAYA KOMUNIKASI DATA","44444","KANTOR PUSAT","01","FRONT LINE","IDR","IDR","5000000","","",""},
                {today,"D","20999155","BY LISTRIK DAN AIR","901","KANTOR PUSAT OPERASIONAL","BIAYA LISTRIK","44444","KANTOR PUSAT","01","FRONT LINE","IDR","IDR","2500000","","",""},
                {today,"C","10001000","KAS KANTOR PUSAT","100","KANTOR PUSAT OPERASIONAL","PEMBAYARAN KAS","44444","KANTOR PUSAT","01","FRONT LINE","IDR","IDR","4000000","","",""},
                {today,"C","10001000","KAS KANTOR PUSAT","100","KANTOR PUSAT OPERASIONAL","PEMBAYARAN KAS TAMBAHAN","44444","KANTOR PUSAT","01","FRONT LINE","IDR","IDR","3500000","","",""}
            };
            for (int r = 0; r < rows.length; r++) {
                Row rr = d.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) rr.createCell(c).setCellValue(rows[r][c]);
            }
            resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            resp.setHeader("Content-Disposition", "attachment; filename=\"template_upload.xlsx\"");
            OutputStream o = resp.getOutputStream();
            wb.write(o);
            o.flush();
        }
    }

    /**
     * Sheet referensi "Product_Code" (BUG_AUSY_00154).
     *
     * Template lama punya tiga sheet kosong bernama Info1/Info2/Info3 — hanya pengganjal supaya
     * sheet DATA jatuh di urutan kelima. Berkas AUSY.xls milik MNC mengisi tempat itu dengan
     * daftar referensi; yang diberikan ke kami sejauh ini daftar kode produk, jadi itu yang
     * dipasang. Isinya persis kolom dan baris milik MNC (G9PCNO, G9DESC, G9ENGT, G9TEXT,
     * Last Update), dibaca dari berkas resource supaya bisa diperbarui tanpa menyentuh kode.
     *
     * Info2/Info3 sengaja dibiarkan kosong: sheet MNC untuk keduanya belum kami terima, dan
     * mengarang isinya lebih berbahaya daripada membiarkannya kosong.
     */
    private void productCodeSheet(Workbook wb) {
        Sheet sh = wb.createSheet("Product_Code");
        CellStyle headStyle = wb.createCellStyle();
        Font bold = wb.createFont();
        bold.setBold(true);
        headStyle.setFont(bold);
        headStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        headStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        int r = 0;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new ClassPathResource("template/product_code.csv").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                Row row = sh.createRow(r);
                String[] cells = splitCsv(line);
                for (int c = 0; c < cells.length; c++) {
                    Cell cell = row.createCell(c);
                    cell.setCellValue(cells[c]);
                    if (r == 0) cell.setCellStyle(headStyle);
                }
                r++;
            }
        } catch (Exception e) {
            LOG.warn("Daftar kode produk tidak bisa dibaca, sheet Product_Code dibiarkan kosong: {}", e.getMessage());
            return;
        }
        for (int c = 0; c < 5; c++) sh.autoSizeColumn(c);
        sh.createFreezePane(0, 1);
    }

    /**
     * Pemisah CSV sederhana yang menghormati tanda kutip — beberapa deskripsi memuat koma
     * ({@code JOINT FINANCE GROUP - COMMERCIALS}), jadi split(",") biasa memecah di tempat salah.
     */
    private static String[] splitCsv(String line) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                out.add(cur.toString()); cur.setLength(0);
            } else cur.append(ch);
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
