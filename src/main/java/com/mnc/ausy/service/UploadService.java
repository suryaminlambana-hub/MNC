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
     * Kolom Excel yang isinya teks dan ikut tertulis ke ACCTXT.
     *
     * Kolom angka (nominal) dan tanggal sengaja tidak ikut: tanda hubung pada "5-Oct-26" justru
     * bagian dari formatnya, dan menggantinya dengan spasi membuat tanggal gagal diurai.
     * Dipakai untuk menghitung berapa isian yang dirapikan, bukan untuk menolak apa pun
     * (BUG_AUSY_00180).
     */
    private static final int[] TEXT_COLUMNS = { 2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14, 15 };

    @Transactional
    public UploadResult process(InputStream in, String fileName, String username) throws Exception {
        UploadResult res = new UploadResult();
        String today = LocalDate.now().format(IN).toLowerCase();

        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sh = dataSheet(wb);
            if (sh == null) {
                // BUG_AUSY_00182: tidak lagi menyuruh "Unduh Template" — tombolnya sudah
                // disembunyikan (BUG_AUSY_00181), jadi kalimat itu menunjuk ke sesuatu yang
                // tidak ada di layar dan hanya membuat orang mencari-cari.
                res.message = "Format file salah: sheet \"" + UploadTemplateService.DATA_SHEET
                        + "\" tidak ditemukan.";
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

            // Pass 2: hitung berapa isian yang perlu dirapikan, semata untuk dilaporkan ke layar
            // (BUG_AUSY_00180). Karakter asing tidak lagi menggagalkan unggahan — ia diganti
            // spasi saat baris disusun di bawah. Tetap dihitung supaya maker tahu datanya ada
            // yang berubah, bukan diam-diam.
            int dirapikan = 0;
            for (int r = 1; r <= last; r++) {
                Row row = sh.getRow(r);
                if (cell(row, KEY_COLUMN).isBlank()) continue;
                for (int col : TEXT_COLUMNS)
                    if (GlTextRule.needsClean(cell(row, col))) dirapikan++;
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

                // Kolom teks dirapikan di sini, bukan ditolak (BUG_AUSY_00180): karakter di luar
                // huruf/angka/spasi/titik/koma/garis bawah diganti spasi lalu ujungnya dibuang.
                // Tanggal dan nominal TIDAK lewat sini — tanda hubung pada "5-Oct-26" justru
                // bagian dari formatnya, dan menggantinya dengan spasi membuat tanggal gagal
                // diurai.
                GlTabel g = new GlTabel();
                g.setGltrfy(GlTextRule.clean(cell(row, 12)));
                g.setGltrcd(dc);
                g.setGlprdc(GlTextRule.clean(cell(row, 2)));
                g.setGltrac(GlTextRule.clean(cell(row, 4)));
                g.setGltrpd(ddmmyy);
                g.setGlName(GlTextRule.clean(cell(row, 5)));
                String desc = GlTextRule.clean(cell(row, 6));
                if (desc.length() > 40) desc = desc.substring(0, 40);   // lebar kolom ACCTXT
                g.setGltrds(desc);
                g.setGltrbr(GlTextRule.clean(cell(row, 7)));
                g.setBrName(GlTextRule.clean(cell(row, 8)));
                g.setGltrcc(cell(row, 9).replace(".", "").replace(",", ""));   // sama seperti source asli
                g.setGltrcy(GlTextRule.clean(cell(row, 11)));
                g.setGltram(amt);
                g.setGlflag(GlTextRule.clean(cell(row, 14)));
                g.setGlresp(GlTextRule.clean(cell(row, 15)));
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
                          (res.balanced ? "Debit = Credit (balance)." : "PERINGATAN: Debit dan Credit tidak balance!") +
                          // Pembersihan tidak boleh diam-diam: maker berhak tahu datanya ada yang
                          // berubah sebelum checker menyetujuinya (BUG_AUSY_00180).
                          (dirapikan == 0 ? "" : " " + dirapikan + " isian dirapikan: karakter di luar "
                                  + GlTextRule.ALLOWED_TEXT + " diganti spasi.");
            return res;
        }
    }
}
