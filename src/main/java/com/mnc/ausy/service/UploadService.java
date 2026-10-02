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

    /**
     * Sheet data dicari menurut NAMA (BUG_AUSY_00154). Dulu dipatok indeks ke-4 — "sheet ke-5" —
     * yang hanya benar untuk template buatan sendiri; di AUSY.xls milik MNC sheet ke-5 adalah
     * Branch_Closed_List, sehingga berkas asli MNC terbaca daftar cabang tutup sebagai data GL.
     * "Sheet1" nama MNC, "DATA" nama template lama; keduanya dilayani supaya berkas yang sudah
     * beredar tidak mendadak ditolak.
     */
    private static final String[] DATA_SHEET_NAMES = { UploadTemplateService.DATA_SHEET, "DATA" };

    /**
     * Kolom E [Transaction_Account] menentukan sebuah baris berisi data atau tidak.
     *
     * Dulu yang dipakai kolom A [Posting Date], dan itu tidak bisa dipertahankan: di template MNC
     * kolom A berisi rumus {@code =NOW()} pada seluruh 167 baris isian, jadi baris kosong pun
     * bertanggal. Tanpa nomor akun sebuah baris GL tidak punya arti apa-apa, sehingga kolom inilah
     * penanda yang jujur.
     */
    private static final int KEY_COLUMN = 4;

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
        // Sel rumus dibaca dari nilai yang Excel simpan saat berkas terakhir dihitung. Template
        // MNC penuh rumus (=NOW(), VLOOKUP nama GL/cabang/cost centre) dan dulu semuanya terbaca
        // kosong, sehingga berkas MNC yang sah tidak menghasilkan satu baris pun (BUG_AUSY_00154).
        CellType type = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
        return switch (type) {
            // Dirapikan dengan GlTextRule.trim, bukan String.trim (BUG_AUSY_00180): yang terakhir
            // hanya mengenali karakter sampai U+0020, sedangkan yang sering ikut tersalin dari
            // Excel justru NBSP. Nilai inilah yang diperiksa DAN yang disimpan, jadi spasi di
            // ujung tidak pernah lagi sampai ke basis data maupun ke berkas ACCTXT.
            case STRING -> GlTextRule.trim(c.getStringCellValue());
            case NUMERIC -> {
                // Tanggal ditulis dengan ejaan yang sama seperti yang diketik tangan di template
                // lama (d-MMM-yy), supaya pemeriksaan & penguraian tanggal di bawah tidak peduli
                // sel itu berisi teks atau tanggal sungguhan.
                if (DateUtil.isCellDateFormatted(c))
                    yield c.getLocalDateTimeCellValue().toLocalDate().format(IN);
                double d = c.getNumericCellValue();
                yield (d == Math.floor(d)) ? String.valueOf((long) d) : String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            default -> "";
        };
    }

    /**
     * Ada isinya atau tidak. Template MNC datang dengan 167 baris berumus yang semuanya kosong,
     * jadi "sheetnya panjang" bukan tanda berkas itu berisi data — pesan "tidak ada baris data"
     * harus tetap muncul kalau maker lupa mengisi.
     */
    private static boolean hasDataRow(Sheet sh, int last) {
        for (int r = 1; r <= last; r++)
            if (!cell(sh.getRow(r), KEY_COLUMN).isBlank()) return true;
        return false;
    }

    /**
     * Sheet tempat data GL diisi, dicari menurut nama. Null kalau berkasnya bukan template kami.
     */
    private static Sheet dataSheet(Workbook wb) {
        for (String name : DATA_SHEET_NAMES) {
            Sheet sh = wb.getSheet(name);
            if (sh != null) return sh;
        }
        return null;
    }

    private static BigDecimal amount(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(s.replace(",", "").trim()); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    /**
     * Kolom Excel yang isinya teks dan ikut tertulis ke ACCTXT — {index kolom, index label}.
     * Kolom angka (nominal) dan tanggal tidak ikut: keduanya sudah punya validasinya sendiri.
     */
    private static final int[][] TEXT_COLUMNS = {
            {2, 0}, {4, 1}, {5, 2}, {6, 3}, {7, 4}, {8, 5},
            {9, 6}, {10, 7}, {11, 8}, {12, 9}, {14, 10}, {15, 11}
    };

    private static final String[] COLUMN_LABEL = {
            "Kolom C [Product]", "Kolom E [Tr. Account]", "Kolom F [GL Name]",
            "Kolom G [Description]", "Kolom H [Branch]", "Kolom I [Branch Name]",
            "Kolom J [Cost Centre]", "Kolom K [Cost Centre Name]", "Kolom L [Currency]",
            "Kolom M [Tr. Year]", "Kolom O [Flag]", "Kolom P [Resp]"
    };

    @Transactional
    public UploadResult process(InputStream in, String fileName, String username) throws Exception {
        UploadResult res = new UploadResult();
        String today = LocalDate.now().format(IN).toLowerCase();

        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sh = dataSheet(wb);
            if (sh == null) {
                res.message = "Format file salah: sheet \"" + UploadTemplateService.DATA_SHEET
                        + "\" tidak ditemukan. Unduh Template dan isi pada sheet itu.";
                return res;
            }
            int last = sh.getLastRowNum();

            // Pass 1: validate posting date (col A) == today
            for (int r = 1; r <= last; r++) {
                if (cell(sh.getRow(r), KEY_COLUMN).isBlank()) continue;
                String a = cell(sh.getRow(r), 0);
                if (a.isBlank()) {
                    res.errors.add(new String[]{ String.valueOf(r + 1), "Kolom A [Posting Date] belum diisi." });
                } else if (!a.toLowerCase().equals(today)) {
                    res.errors.add(new String[]{ String.valueOf(r + 1),
                        "Kolom A [Posting Date] salah: " + a + " (seharusnya " + today + ")" });
                }
            }
            if (!res.errors.isEmpty()) {
                res.message = "Upload gagal: tanggal posting tidak sesuai hari ini.";
                return res;
            }
            if (!hasDataRow(sh, last)) {
                res.message = "Tidak ada baris data: kolom E [Transaction_Account] kosong di seluruh sheet \""
                        + sh.getSheetName() + "\".";
                return res;
            }

            // Pass 2: karakter yang tidak bisa ditulis ke ACCTXT (BUG_AUSY_00162).
            // Diperiksa sebelum satu baris pun disimpan, supaya file yang bermasalah ditolak
            // utuh — bukan separuh masuk lalu ketahuan waktu checker sudah meng-approve.
            for (int r = 1; r <= last; r++) {
                Row row = sh.getRow(r);
                if (cell(row, KEY_COLUMN).isBlank()) continue;
                for (int[] col : TEXT_COLUMNS) {
                    String problem = GlTextRule.problem(COLUMN_LABEL[col[1]], cell(row, col[0]));
                    if (problem != null)
                        res.errors.add(new String[]{ String.valueOf(r + 1), problem });
                }
            }
            if (!res.errors.isEmpty()) {
                res.message = "Upload gagal: ada karakter yang tidak diizinkan. Perbaiki di file Excel lalu unggah ulang.";
                return res;
            }

            String paket = String.format("%02d", gl.maxPaket() + 1);
            int n = 0;
            for (int r = 1; r <= last; r++) {
                Row row = sh.getRow(r);
                if (cell(row, KEY_COLUMN).isBlank()) continue;
                String postingDate = cell(row, 0);
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
                String desc = cell(row, 6); if (desc.length() > 40) desc = desc.substring(0, 40);   // lebar kolom ACCTXT
                g.setGltrds(desc);
                g.setGltrbr(cell(row, 7));
                g.setBrName(cell(row, 8));
                g.setGltrcc(cell(row, 9).replace(".", "").replace(",", ""));   // sama seperti source asli
                g.setGltrcy(cell(row, 11));
                g.setGltram(amt);
                g.setGlflag(cell(row, 14));
                g.setGlresp(cell(row, 15));
                g.setCsName(cell(row, 10));
                g.setNoPaket(paket);
                g.setGleqam(amt);
                g.setGlotam(amt);
                // username utuh: pemiliknya harus bisa dicocokkan untuk pembatasan data per maker (BUG_AUSY_00138).
                // Pemotongan ke 10 karakter tetap dilakukan GltrrfFormatter saat menulis file ACCTXT.
                g.setGltrus(username);
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

            audit.transaction(username, "UPLOAD", "Batch " + paket + " — " + n + " baris (D=" + res.debit + ", C=" + res.credit + ", " + (res.balanced ? "balance" : "TIDAK balance") + ")");

            res.success = true;
            res.message = "Upload berhasil — Batch " + paket + " menunggu approval Checker (" + n + " baris jurnal). " +
                          (res.balanced ? "Debit = Credit (balance)." : "PERINGATAN: Debit dan Credit tidak balance!");
            return res;
        }
    }
}
