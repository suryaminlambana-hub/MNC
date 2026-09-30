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
import java.util.ArrayList;
import java.util.List;

/**
 * Template Excel untuk upload GL — salinan format AUSY.xls milik MNC (BUG_AUSY_00154).
 *
 * Template lama buatan sendiri: satu sheet "Cover", tiga sheet pengganjal, lalu sheet "DATA"
 * berisi empat baris contoh. Penguji menolaknya karena yang dipakai orang MNC sehari-hari
 * bukan itu. Bentuk yang benar:
 *
 * <ul>
 *   <li>lima sheet referensi — Product_Code, COA, Cost_Center, Branch_Code, Branch_Closed_List —
 *       isinya data statis milik MNC, dipakai sebagai acuan dan sumber VLOOKUP;</li>
 *   <li>sheet <b>Sheet1</b> paling belakang sebagai tempat penginputan data GL.</li>
 * </ul>
 *
 * Isi sheet referensi disalin apa adanya dari AUSY.xls ke berkas CSV di {@code resources/template}
 * supaya bisa diperbarui tanpa menyentuh kode — saat MNC mengirim daftar baru, yang diganti
 * berkasnya saja.
 *
 * Sheet1 sengaja tidak diisi baris contoh: di berkas MNC pun kosong. Yang ada di tiap baris
 * hanya rumus bantu — tanggal hari ini, dan pencarian nama GL / cabang / cost centre dari sheet
 * referensi — sehingga maker cukup mengisi kolom yang memang datanya dia yang punya.
 *
 * <p><b>Dibaca oleh {@link UploadService}.</b> Sheet datanya dicari menurut NAMA, jadi urutan
 * sheet di sini tidak lagi menjadi perjanjian tak tertulis seperti dulu ("sheet ke-5").
 */
@Service
public class UploadTemplateService {

    private static final Logger LOG = LoggerFactory.getLogger(UploadTemplateService.class);

    /** Sheet tempat maker mengisi data GL — namanya dipakai UploadService untuk menemukannya. */
    public static final String DATA_SHEET = "Sheet1";

    /** Sheet referensi, urut seperti di AUSY.xls: nama sheet -> berkas CSV isinya. */
    private static final String[][] REFERENCE = {
            {"Product_Code",       "template/product_code.csv"},
            {"COA",                "template/coa.csv"},
            {"Cost_Center",        "template/cost_center.csv"},
            {"Branch_Code",        "template/branch_code.csv"},
            {"Branch_Closed_List", "template/branch_closed_list.csv"},
    };

    /** Judul kolom Sheet1, persis seperti AUSY.xls — campuran spasi dan garis bawah ikut ditiru. */
    private static final String[] HEADERS = {
            "Posting_Date", "D/C", "Product Code", "Product Name", "Transaction_Account", "Gl_Name",
            "Transaction_Description", "Branch", "Branch_Name", "Cost_Center", "Cost_Center_Name",
            "Currency", "Foreign_Curr", "Transaction_Amount", "Flag", "Responding_Branch",
            "Responding_Branch_Name"
    };

    /** Banyaknya baris isian yang disiapkan berumus, sama seperti AUSY.xls. */
    private static final int INPUT_ROWS = 167;

    public void download(HttpServletResponse resp) throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            CellStyle head = headerStyle(wb);
            int[] refRows = new int[REFERENCE.length];
            for (int i = 0; i < REFERENCE.length; i++)
                refRows[i] = referenceSheet(wb, REFERENCE[i][0], REFERENCE[i][1], head);

            dataSheet(wb, head, refRows[1], refRows[2], refRows[3]);

            resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            resp.setHeader("Content-Disposition", "attachment; filename=\"template_upload.xlsx\"");
            OutputStream o = resp.getOutputStream();
            wb.write(o);
            o.flush();
        }
    }

    private static CellStyle headerStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font bold = wb.createFont();
        bold.setBold(true);
        s.setFont(bold);
        s.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return s;
    }

    /**
     * Satu sheet referensi dari CSV. Mengembalikan nomor baris terakhir yang terisi — dipakai
     * menyusun rentang VLOOKUP di Sheet1 supaya rentangnya selalu sepanjang datanya. (Di AUSY.xls
     * rentang Cost_Center berhenti di baris 231 padahal datanya 297 baris; kode yang menghitung
     * sendiri tidak bisa ketinggalan seperti itu.)
     */
    private int referenceSheet(Workbook wb, String name, String resource, CellStyle head) {
        Sheet sh = wb.createSheet(name);
        int r = 0;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new ClassPathResource(resource).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                Row row = sh.createRow(r);
                String[] cells = splitCsv(line);
                for (int c = 0; c < cells.length; c++) {
                    Cell cell = row.createCell(c);
                    cell.setCellValue(cells[c]);
                    if (r == 0) cell.setCellStyle(head);
                }
                r++;
            }
        } catch (Exception e) {
            LOG.warn("Data referensi {} tidak bisa dibaca, sheet dibiarkan kosong: {}", resource, e.getMessage());
            return r;
        }
        // Lebar kolom dipatok, bukan autoSizeColumn: sheet COA berisi 1.775 baris dan pengukuran
        // per sel membuat unduhan terasa menggantung tanpa hasil yang lebih baik.
        for (int c = 0; c < 8; c++) sh.setColumnWidth(c, 22 * 256);
        if (r > 0) sh.createFreezePane(0, 1);
        return r;
    }

    /**
     * Sheet1 — tempat maker mengisi. Kosong seperti punya MNC; yang disiapkan hanya rumus bantu:
     * tanggal hari ini di kolom A (validasi upload menolak tanggal selain hari ini, jadi tanggal
     * mati pada template statis selalu gagal dipakai), dan VLOOKUP nama GL / cabang / cost centre
     * supaya maker tidak perlu menyalin nama dari sheet referensi.
     *
     * Baris pertama tidak diberi Flag: di AUSY.xls baris itu jadi acuan cabang penampung yang
     * ditunjuk rumus {@code =IF(O{n}=2,$H$2,"")} pada baris-baris sesudahnya.
     */
    private void dataSheet(Workbook wb, CellStyle head, int coaRows, int ccRows, int branchRows) {
        Sheet sh = wb.createSheet(DATA_SHEET);

        CellStyle dateStyle = wb.createCellStyle();
        dateStyle.setDataFormat(wb.createDataFormat().getFormat("d-mmm-yy"));

        Row hr = sh.createRow(0);
        for (int i = 0; i < HEADERS.length; i++) {
            Cell c = hr.createCell(i);
            c.setCellValue(HEADERS[i]);
            c.setCellStyle(head);
        }

        for (int i = 1; i <= INPUT_ROWS; i++) {
            Row row = sh.createRow(i);
            int n = i + 1;                                  // nomor baris seperti terlihat di Excel

            Cell a = row.createCell(0);
            a.setCellFormula("NOW()");
            a.setCellStyle(dateStyle);

            row.createCell(5).setCellFormula(
                    "VLOOKUP(E" + n + ",COA!$A$2:$B$" + coaRows + ",2,FALSE)");
            row.createCell(8).setCellFormula(
                    "VLOOKUP(H" + n + ",Branch_Code!$A$2:$B$" + branchRows + ",2,FALSE)");
            row.createCell(11).setCellValue("IDR");

            if (i == 1) continue;                           // baris acuan cabang penampung

            row.createCell(10).setCellFormula(
                    "VLOOKUP(J" + n + ",Cost_Center!$A$2:$B$" + ccRows + ",2,FALSE)");
            row.createCell(14).setCellValue(2);
            row.createCell(15).setCellFormula("IF(O" + n + "=2,$H$2,\"\")");
            row.createCell(16).setCellFormula(
                    "VLOOKUP(P" + n + ",Branch_Code!$A$2:$B$" + branchRows + ",2,FALSE)");
        }

        for (int c = 0; c < HEADERS.length; c++) sh.setColumnWidth(c, 22 * 256);
        sh.createFreezePane(0, 1);
        // Excel menghitung ulang seluruh rumus saat berkas dibuka: tanpa ini sel rumus yang
        // ditulis POI belum punya nilai tersimpan, dan yang terbaca saat upload akan kosong.
        wb.setForceFormulaRecalculation(true);
    }

    /**
     * Pemisah CSV sederhana yang menghormati tanda kutip — beberapa deskripsi memuat koma
     * ({@code JOINT FINANCE GROUP - COMMERCIALS}), jadi split(",") biasa memecah di tempat salah.
     */
    private static String[] splitCsv(String line) {
        List<String> out = new ArrayList<>();
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
