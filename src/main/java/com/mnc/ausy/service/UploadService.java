package com.mnc.ausy.service;

import com.mnc.ausy.domain.GlTabel;
import com.mnc.ausy.domain.TUpload;
import com.mnc.ausy.repo.GlTabelRepository;
import com.mnc.ausy.repo.TUploadRepository;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Service
public class UploadService {
    private static final DateTimeFormatter IN  = DateTimeFormatter.ofPattern("d-MMM-yy", Locale.ENGLISH);
    private static final DateTimeFormatter DDMMYY = DateTimeFormatter.ofPattern("ddMMyy");
    private static final int DATA_SHEET = 4;   // 5th sheet (0-based), matches the template

    private final GlTabelRepository gl;
    private final TUploadRepository tUploads;
    private final SequenceService seq;
    private final AuditService audit;

    public UploadService(GlTabelRepository gl, TUploadRepository tUploads, SequenceService seq, AuditService audit) {
        this.gl = gl; this.tUploads = tUploads; this.seq = seq; this.audit = audit;
    }

    private static String cell(Row r, int i) {
        if (r == null) return "";
        Cell c = r.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (c == null) return "";
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue().trim();
            case NUMERIC -> {
                double d = c.getNumericCellValue();
                yield (d == Math.floor(d)) ? String.valueOf((long) d) : String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            default -> "";
        };
    }

    private static BigDecimal amount(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(s.replace(",", "").trim()); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    @Transactional
    public UploadResult process(InputStream in, String fileName, String username) throws Exception {
        UploadResult res = new UploadResult();
        String today = LocalDate.now().format(IN).toLowerCase();

        try (Workbook wb = WorkbookFactory.create(in)) {
            if (wb.getNumberOfSheets() <= DATA_SHEET) {
                res.message = "Format file salah: sheet DATA (ke-5) tidak ditemukan.";
                return res;
            }
            Sheet sh = wb.getSheetAt(DATA_SHEET);
            int last = sh.getLastRowNum();

            // Pass 1: validate posting date (col A) == today
            for (int r = 1; r <= last; r++) {
                String a = cell(sh.getRow(r), 0);
                if (a.isBlank()) continue;
                if (!a.toLowerCase().equals(today)) {
                    res.errors.add(new String[]{ String.valueOf(r + 1),
                        "Kolom A [Posting Date] salah: " + a + " (seharusnya " + today + ")" });
                }
            }
            if (!res.errors.isEmpty()) {
                res.message = "Upload gagal: tanggal posting tidak sesuai hari ini.";
                return res;
            }

            String paket = String.format("%02d", gl.maxPaket() + 1);
            int n = 0;
            for (int r = 1; r <= last; r++) {
                Row row = sh.getRow(r);
                String postingDate = cell(row, 0);
                if (postingDate.isBlank()) continue;
                String dc = cell(row, 1).toUpperCase();
                BigDecimal amt = amount(cell(row, 13));
                String ddmmyy;
                try { ddmmyy = LocalDate.parse(postingDate, IN).format(DDMMYY); } catch (Exception e) { ddmmyy = ""; }

                GlTabel g = new GlTabel();
                g.setGltrfy(cell(row, 12));
                g.setGltrcd(dc);
                g.setGlprdc(cell(row, 2));
                g.setGltrac(cell(row, 4));
                g.setGltrpd(ddmmyy);
                g.setGlName(cell(row, 5));
                String desc = cell(row, 6); if (desc.length() > 40) desc = desc.substring(0, 40);
                g.setGltrds(desc);
                g.setGltrbr(cell(row, 7));
                g.setBrName(cell(row, 8));
                g.setGltrcc(cell(row, 9));
                g.setGltrcy(cell(row, 11));
                g.setGltram(amt);
                g.setGlflag(cell(row, 14));
                g.setGlresp(cell(row, 15));
                g.setCsName(cell(row, 10));
                g.setNoPaket(paket);
                g.setGleqam(amt);
                g.setGlotam(amt);
                g.setGltrus(username.length() > 10 ? username.substring(0, 10) : username);
                g.setGltrpd1(ddmmyy);
                g.setStatusPrint("BELUM UPLOAD");
                g.setGlsrc("p");
                g.setGlotcc(cell(row, 11));
                g.setGltrrf(paket + "-01-" + String.format("%04d", seq.next()));
                g.setStatusDual("PENDING");
                gl.save(g);
                if (dc.equals("D")) res.debit = res.debit.add(amt); else res.credit = res.credit.add(amt);
                n++;
            }

            res.rows = n;
            res.paket = paket;
            res.balanced = res.debit.compareTo(res.credit) == 0;

            TUpload t = new TUpload();
            t.setSessId(java.util.UUID.randomUUID().toString().substring(0, 20));
            t.setFileName(fileName);
            t.setJmlData(n);
            t.setUserId(username);
            t.setStatus("Success");
            t.setAdddt(LocalDateTime.now());
            tUploads.save(t);

            audit.transaction(username, "UPLOAD", "Paket " + paket + " — " + n + " baris (D=" + res.debit + ", C=" + res.credit + ", " + (res.balanced ? "balance" : "TIDAK balance") + ")");

            res.success = true;
            res.message = "Upload berhasil — Paket " + paket + " dibuat (" + n + " baris jurnal). " +
                          (res.balanced ? "Debit = Credit (balance)." : "PERINGATAN: Debit dan Credit tidak balance!");
            return res;
        }
    }
}
