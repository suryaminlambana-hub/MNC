package com.mnc.ausy.service;

import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Reusable .xlsx export: header row + data rows (Numbers become numeric cells). */
@Service
public class ExcelExportService {

    public void download(HttpServletResponse resp, String sheetName, String filePrefix,
                         List<String> headers, List<List<Object>> rows, boolean boldLastRow) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet(sheetName);
            CellStyle bold = wb.createCellStyle();
            Font bf = wb.createFont(); bf.setBold(true); bold.setFont(bf);

            Row hr = sh.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell c = hr.createCell(i); c.setCellValue(headers.get(i)); c.setCellStyle(bold);
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sh.createRow(r + 1);
                List<Object> data = rows.get(r);
                boolean makeBold = boldLastRow && r == rows.size() - 1;
                for (int i = 0; i < data.size(); i++) {
                    Cell c = row.createCell(i);
                    Object v = data.get(i);
                    if (v instanceof Number num) c.setCellValue(num.doubleValue());
                    else c.setCellValue(v == null ? "" : v.toString());
                    if (makeBold) c.setCellStyle(bold);
                }
            }
            for (int i = 0; i < headers.size(); i++) sh.autoSizeColumn(i);

            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            resp.setHeader("Content-Disposition", "attachment; filename=\"" + filePrefix + "_" + ts + ".xlsx\"");
            wb.write(resp.getOutputStream());
            resp.getOutputStream().flush();
        }
    }

    /** One .xlsx with multiple sheets (e.g. audit-trail Transaksi + Login in separate tabs). */
    public void downloadSheets(HttpServletResponse resp, String filePrefix, List<SheetData> sheets) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            CellStyle bold = wb.createCellStyle();
            Font bf = wb.createFont(); bf.setBold(true); bold.setFont(bf);
            for (SheetData spec : sheets) {
                Sheet sh = wb.createSheet(spec.name());
                Row hr = sh.createRow(0);
                for (int i = 0; i < spec.headers().size(); i++) {
                    Cell c = hr.createCell(i); c.setCellValue(spec.headers().get(i)); c.setCellStyle(bold);
                }
                for (int r = 0; r < spec.rows().size(); r++) {
                    Row row = sh.createRow(r + 1);
                    List<Object> data = spec.rows().get(r);
                    for (int i = 0; i < data.size(); i++) {
                        Cell c = row.createCell(i);
                        Object v = data.get(i);
                        if (v instanceof Number num) c.setCellValue(num.doubleValue());
                        else c.setCellValue(v == null ? "" : v.toString());
                    }
                }
                for (int i = 0; i < spec.headers().size(); i++) sh.autoSizeColumn(i);
            }
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            resp.setHeader("Content-Disposition", "attachment; filename=\"" + filePrefix + "_" + ts + ".xlsx\"");
            wb.write(resp.getOutputStream());
            resp.getOutputStream().flush();
        }
    }

    /** A single tab in a multi-sheet export. */
    public record SheetData(String name, List<String> headers, List<List<Object>> rows) {}
}
