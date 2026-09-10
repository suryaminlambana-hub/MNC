package com.mnc.ausy.service;

import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
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

    private static final String[] HEADERS = {
            "Posting Date", "D/C", "Product Code", "Product Name", "Transaction Account", "GL Name",
            "Transaction Description", "Branch", "Branch Name", "Cost Center", "Cost Center Name",
            "Currency", "Foreign Curr", "Transaction Amount", "Flag", "Responding Branch",
            "Responding Branch Name"
    };

    public void download(HttpServletResponse resp) throws Exception {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("d-MMM-yy", Locale.ENGLISH));
        try (Workbook wb = new XSSFWorkbook()) {
            wb.createSheet("Cover"); wb.createSheet("Info1"); wb.createSheet("Info2"); wb.createSheet("Info3");
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
}
