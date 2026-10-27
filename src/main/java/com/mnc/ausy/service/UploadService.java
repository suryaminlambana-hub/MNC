package com.mnc.ausy.service;

import com.mnc.ausy.domain.GlTabel;
import com.mnc.ausy.domain.TUpload;
import com.mnc.ausy.domain.GlCode;
import com.mnc.ausy.repo.GlCodeRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
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
    private final GlCodeRepository glCodes;
    private final TUploadRepository tUploads;
    private final AuditService audit;

    public UploadService(GlTabelRepository gl, TUploadRepository tUploads, AuditService audit, GlCodeRepository glCodes) {
        this.glCodes = glCodes;
        this.gl = gl; this.tUploads = tUploads; this.audit = audit;
    }

    /**
     * Baris yang sudah mulai diisi orang: ada D/C, keterangan, atau nominal.
     * Kolom A sengaja tidak dihitung — di template MNC seluruh 167 baris isiannya berisi
     * rumus {@code =NOW()}, jadi kolom itu terisi bahkan pada baris yang masih kosong.
     */
    private static boolean barisTerisiSebagian(Row r) {
        for (int c : new int[]{ 1, 6, KOLOM_NOMINAL })
            if (!cell(r, c).isBlank()) return true;
        return false;
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
        try { return sen(new BigDecimal(s.replace(",", "").trim())); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    /**
     * Nominal dibulatkan ke 2 desimal (sen), persis seperti yang tersimpan di kolom NUMERIC(17,2)
     * dan yang dikirim di berkas ACCTXT.
     *
     * Nominal hasil rumus Excel membawa sisa pecahan floating-point — 32376629.63 tersimpan sebagai
     * 32376629.629999999. Dulu nilai mentah itu yang dijumlahkan, sehingga berkas yang balance
     * ditolak dengan pesan "Total Debit 97.129.888,89 tidak sama dengan total Credit
     * 97.129.888,89 (selisih 0,00)". Yang dibandingkan kini angka yang benar-benar akan dikirim.
     */
    static BigDecimal sen(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** Kolom N — Transaction_Amount. */
    private static final int KOLOM_NOMINAL = 13;

    /**
     * Nominal seperti dibaca {@link #amount}, tetapi null bila teksnya tidak bisa dibaca sebagai
     * angka — supaya pemeriksaan bisa MENOLAK, bukan diam-diam menganggapnya 0 (REQ_AUSY_00024).
     * Sel kosong tetap 0, sama seperti sebelumnya.
     */
    static BigDecimal nominalAtauNull(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        try { return sen(new BigDecimal(s.replace(",", "").trim())); } catch (Exception e) { return null; }
    }

    /**
     * Nominal untuk teks audit trail: {@code 5.000.000,00} (REQ_AUSY_00012), format yang sama
     * dengan kolom Amount di layar. Teks audit dibaca di Audit Trail dan card Aktivitas Terakhir,
     * dan angka mentah {@code 5000000} sulit dibaca sekilas.
     */
    static String nominal(BigDecimal v) { return Nominal.format(v); }

    /** Kolom currency — wajib berisi IDR (BUG_AUSY_00190). */
    private static final int KOLOM_CURRENCY = 11;
    /** Kolom E — Transaction Account, dicocokkan ke master Kode GL (REQ_AUSY_00059). */
    private static final int KOLOM_AKUN = 4;
    /** Kolom F — Gl_Name. Diabaikan: namanya diambil dari master Kode GL (REQ_AUSY_00065). */
    private static final int KOLOM_GL_NAME = 5;

    /**
     * {kolom, lebar maksimal} — lebar kolom gl_tabel tempat isian itu disimpan (REQ_AUSY_00067).
     * Kolom E dijaga master Kode GL, kolom G dipotong ke 40, Currency wajib IDR.
     */
    private static final int[][] KOLOM_LEBAR = {
            {2, 20},    // C Product Code        -> glprdc
            {7, 10},    // H Branch              -> gltrbr
            {8, 100},   // I Branch_Name         -> br_name
            {9, 10},    // J Cost_Center         -> gltrcc
            {10, 100},  // K Cost_Center_Name    -> cs_name
            {12, 5},    // M Foreign_Curr        -> gltrfy
            {14, 5},    // O Flag                -> glflag
            {15, 10},   // P Responding_Branch   -> glresp
    };

    /**
     * Kolom kode yang hanya boleh berisi angka 0-9 — tanda petik satu pun tidak
     * (BUG_AUSY_00192 / REQ_AUSY_00003): Product Code dan Transaction_Account.
     *
     * <p>Responding_Branch (kolom P) sengaja TIDAK ikut diperiksa. Isinya bukan ketikan maker
     * melainkan rumus {@code IF(O{n}=2,$H$2,"")} yang menyalin cabang dari H2, jadi bentuknya
     * mengikuti apa pun yang ada di sana — termasuk tanda petik penjaga angka nol di depan.
     * Memeriksanya berarti menolak berkas yang sah karena isi sel yang tidak pernah diketik
     * orangnya.
     */
    private static final int[][] KOLOM_ANGKA = {
            {2,  0}, {4,  1}
    };
    private static final String[] LABEL_ANGKA = {
            "Kolom C [Product Code]", "Kolom E [Transaction_Account]"
    };

    /**
     * Kolom yang boleh berisi angka DAN tanda petik satu (BUG_AUSY_00193): Branch dan
     * Cost_Center. Tanda petiknya dipakai di spreadsheet untuk menjaga angka nol di depan.
     */
    private static final int[][] KOLOM_ANGKA_PETIK = {
            {7, 0}, {9, 1}
    };
    private static final String[] LABEL_ANGKA_PETIK = {
            "Kolom H [Branch]", "Kolom J [Cost_Center]"
    };

    /** Nama kolom seperti terbaca di Excel, untuk menyebut letak kesalahan secara tepat. */
    private static final String[] LABEL_KOLOM = {
            "Kolom A [Posting_Date]", "Kolom B [D/C]", "Kolom C [Product Code]", "Kolom D [Product Name]",
            "Kolom E [Transaction_Account]", "Kolom F [Gl_Name]", "Kolom G [Transaction_Description]",
            "Kolom H [Branch]", "Kolom I [Branch_Name]", "Kolom J [Cost_Center]",
            "Kolom K [Cost_Center_Name]", "Kolom L [Currency]", "Kolom M [Foreign_Curr]",
            "Kolom N [Transaction_Amount]", "Kolom O [Flag]", "Kolom P [Responding_Branch]",
            "Kolom Q [Responding_Branch_Name]"
    };

    /**
     * Nilai galat Excel ({@code #N/A}, {@code #REF!}, …) pada sebuah sel, atau null kalau selnya
     * baik-baik saja.
     *
     * <p>Dipakai untuk menangkap VLOOKUP yang gagal. {@link #cell} memetakan sel galat menjadi
     * string kosong — cocok untuk membaca nilai, tapi berbahaya kalau dipakai memutuskan: baris
     * yang Gl_Name-nya {@code #N/A} dulu tersimpan dengan nama GL KOSONG, lalu terbawa apa adanya
     * ke berkas ACCTXT tanpa sepatah peringatan pun.
     */
    private static String galatExcel(Row r, int i) {
        if (r == null) return null;
        Cell c = r.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (c == null) return null;
        CellType t = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
        if (t != CellType.ERROR) return null;
        try { return FormulaError.forInt(c.getErrorCellValue()).getString(); }
        catch (Exception e) { return "#ERROR"; }
    }

    /** Hanya angka 0-9. */
    private static boolean angkaSaja(String v) {
        for (int i = 0; i < v.length(); i++) if (v.charAt(i) < '0' || v.charAt(i) > '9') return false;
        return true;
    }

    /** Angka 0-9 atau tanda petik satu. */
    private static boolean angkaAtauPetik(String v) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if ((c < '0' || c > '9') && c != '\'') return false;
        }
        return true;
    }

    /**
     * Isi Sheet1 yang sudah dibaca dan diperiksa. Bila {@link #errors} kosong, {@link #rows} berisi
     * baris GL siap simpan — belum bernomor batch, pemilik, maupun GLTRRF.
     */
    public static class Bacaan {
        public String message;
        public final List<String[]> errors = new ArrayList<>();   // [baris, pesan]
        public final List<GlTabel> rows = new ArrayList<>();
        public BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        public boolean ok() { return message == null && errors.isEmpty(); }
    }

    /**
     * Membaca dan memeriksa Sheet1 template MNC. Dipakai Upload Excel dan Bulk Edit batch
     * (REQ_AUSY_00057), sehingga keduanya memakai template dan aturan yang persis sama.
     *
     * @param tanggalPosting tanggal yang wajib tertulis di kolom A: hari ini untuk upload baru,
     *                       tanggal posting batch itu untuk Bulk Edit.
     * @param keteranganTanggal penjelasan tanggal itu untuk pesan gagal ("hari ini", ...).
     */
    public Bacaan baca(Workbook wb, LocalDate tanggalPosting, String keteranganTanggal) {
        Bacaan b = new Bacaan();
        String today = tanggalPosting.format(IN).toLowerCase();
        Sheet sh = dataSheet(wb);
        if (sh == null) {
            // BUG_AUSY_00182: tidak lagi menyuruh "Unduh Template" — tombolnya sudah
            // disembunyikan (BUG_AUSY_00181), jadi kalimat itu menunjuk ke sesuatu yang
            // tidak ada di layar dan hanya membuat orang mencari-cari.
            b.message = "Format file salah: sheet \"" + UploadTemplateService.DATA_SHEET
                    + "\" tidak ditemukan.";
            return b;
        }
        int last = sh.getLastRowNum();
        // REQ_AUSY_00059: master kode GL dibaca sekali untuk seluruh berkas — sekaligus namanya,
        // karena GL Name diambil dari master, bukan dari kolom F (REQ_AUSY_00065).
        Map<String, String> namaGl = new HashMap<>();
        for (GlCode c : glCodes.findAll())
            if (c.getCode() != null) namaGl.put(c.getCode().trim(), c.getDescription() == null ? "" : GlTextRule.trim(c.getDescription()));

        // Pass 1: validate posting date (col A) == tanggalPosting
        for (int r = 1; r <= last; r++) {
            if (cell(sh.getRow(r), KEY_COLUMN).isBlank()) continue;
            String a = cell(sh.getRow(r), 0);
            if (a.isBlank()) {
                b.errors.add(new String[]{ String.valueOf(r + 1), "Kolom A [Posting Date] belum diisi." });
            } else if (!a.toLowerCase().equals(today)) {
                b.errors.add(new String[]{ String.valueOf(r + 1),
                    "Kolom A [Posting Date] salah: " + a + " (seharusnya " + today + ")" });
            }
        }
        if (!b.errors.isEmpty()) {
            b.message = "Upload gagal: tanggal posting tidak sesuai " + keteranganTanggal + ".";
            return b;
        }
        if (!hasDataRow(sh, last)) {
            b.message = "Tidak ada baris data: kolom E [Transaction_Account] kosong di seluruh sheet \""
                    + sh.getSheetName() + "\".";
            return b;
        }

        // Pass 2: bentuk isian per kolom.
        //
        // Penyaringan simbol menyeluruh sudah dicabut (BUG_AUSY_00191) — kolom nama dan
        // deskripsi adalah free text yang memang wajar memuat tanda baca. Yang diperiksa kini
        // hanya kolom yang bentuknya memang ditentukan aturan bisnis, masing-masing dengan
        // aturannya sendiri.
        //
        // Berkasnya ditolak UTUH, bukan separuh masuk lalu ketahuan saat Checker sudah
        // menyetujui, dan tiap pelanggaran disebut lengkap dengan baris serta kolomnya.
        BigDecimal cekDebit = BigDecimal.ZERO, cekCredit = BigDecimal.ZERO;
        for (int r = 1; r <= last; r++) {
            Row row = sh.getRow(r);
            if (cell(row, KEY_COLUMN).isBlank()) {
                // Kolom E juga penanda "baris ini berisi data" — template MNC datang dengan 167
                // baris kosong, jadi kolom E kosong TIDAK bisa ditolak begitu saja. Yang ditolak
                // baris yang jelas sedang diisi orang tapi akunnya tertinggal (REQ_AUSY_00059).
                if (barisTerisiSebagian(row))
                    b.errors.add(new String[]{ String.valueOf(r + 1),
                            "Kolom E [Transaction_Account] belum diisi, padahal baris ini sudah berisi data." });
                continue;
            }
            String baris = String.valueOf(r + 1);

            // REQ_AUSY_00024 — nominal harus angka dan tidak boleh minus. Dulu nilai yang
            // tidak terbaca diam-diam menjadi 0 dan nominal minus masuk begitu saja.
            String nomTeks = cell(row, KOLOM_NOMINAL);
            BigDecimal nom = nominalAtauNull(nomTeks);
            if (nom == null)
                b.errors.add(new String[]{ baris, "Kolom N [Transaction_Amount] bukan angka — saat ini \""
                        + nomTeks + "\"." });
            else if (nom.signum() < 0)
                b.errors.add(new String[]{ baris, "Kolom N [Transaction_Amount] tidak boleh minus — saat ini "
                        + nominal(nom) + "." });
            else if (cell(row, 1).equalsIgnoreCase("D")) cekDebit = cekDebit.add(nom);
            else cekCredit = cekCredit.add(nom);

            // Sel bernilai galat Excel — hampir selalu VLOOKUP yang tidak menemukan padanan.
            //
            // Inilah yang sebenarnya terjadi saat kode diketik dengan tanda petik di depan
            // ("'10911513"): tanda petiknya BUKAN bagian dari nilai — Excel memakainya untuk
            // menandai sel itu teks, dan yang tersimpan tetap "10911513". Jadi tidak ada yang
            // bisa disaring dari nilainya. Yang rusak adalah pencariannya: angka-sebagai-teks
            // tidak cocok dengan daftar COA yang berisi angka, sehingga Gl_Name menjadi #N/A.
            //
            // Dulu baris itu tetap masuk: cell() memetakan sel galat menjadi string kosong,
            // jadi yang tersimpan adalah baris GL dengan NAMA GL KOSONG, dan begitulah ia
            // terbawa ke berkas ACCTXT. Sekarang berkasnya ditolak dan sebabnya disebut.
            for (int c = 0; c < LABEL_KOLOM.length; c++) {
                // Kolom F [Gl_Name] tidak dibaca lagi (REQ_AUSY_00065), jadi #N/A di sana tidak
                // menggagalkan berkas — kode akunnya sendiri tetap diperiksa terhadap master.
                if (c == KOLOM_GL_NAME) continue;
                String galat = galatExcel(row, c);
                if (galat != null)
                    b.errors.add(new String[]{ baris, LABEL_KOLOM[c] + " bernilai " + galat
                            + " — rumus pencarian tidak menemukan padanannya. Pastikan kodenya"
                            + " terdaftar dan diisi sebagai angka, bukan teks (diawali tanda petik)." });
            }

            // REQ_AUSY_00059 — Transaction Account wajib terdaftar di Manajemen Kode GL.
            // Dulu kode yang tidak dikenal lolos begitu saja dan baru ketahuan jauh di hilir,
            // saat berkas ACCTXT ditolak core. Daftar kodenya dibaca SEKALI di luar perulangan,
            // bukan satu query per baris: berkas 167 baris akan menanyakan hal yang sama 167 kali.
            String akun = GlTextRule.trim(cell(row, KOLOM_AKUN));
            if (!namaGl.containsKey(akun))
                b.errors.add(new String[]{ baris, "Transaction Account \"" + akun + "\" tidak terdaftar"
                        + " pada Manajemen Kode GL." });

            // BUG_AUSY_00190 — currency wajib IDR. Kosong pun ditolak: baris GL tanpa mata
            // uang tidak punya arti, dan dulu baris seperti itu lolos tanpa sepatah pesan.
            String curr = cell(row, KOLOM_CURRENCY);
            if (!curr.equalsIgnoreCase("IDR"))
                b.errors.add(new String[]{ baris, "Kolom L [Currency] wajib diisi \"IDR\""
                        + (curr.isBlank() ? " (saat ini kosong)." : " (saat ini \"" + curr + "\").") });

            // BUG_AUSY_00192 — kode hanya angka. Sel kosong dilewati: yang dipersoalkan
            // bentuk isiannya, bukan ada atau tidaknya isi.
            for (int[] col : KOLOM_ANGKA) {
                String v = cell(row, col[0]);
                if (!v.isEmpty() && !angkaSaja(v))
                    b.errors.add(new String[]{ baris, LABEL_ANGKA[col[1]]
                            + " hanya boleh berisi angka (0-9) — saat ini \"" + v + "\"." });
            }

            // BUG_AUSY_00193 — cabang & cost centre: angka dan tanda petik satu.
            for (int[] col : KOLOM_ANGKA_PETIK) {
                String v = cell(row, col[0]);
                if (!v.isEmpty() && !angkaAtauPetik(v))
                    b.errors.add(new String[]{ baris, LABEL_ANGKA_PETIK[col[1]]
                            + " hanya boleh berisi angka (0-9) dan tanda petik satu (') — saat ini \""
                            + v + "\"." });
            }

            // REQ_AUSY_00067 — D/C hanya D atau C. Dulu isian lain diam-diam dihitung Credit,
            // dan "Debit" menggagalkan penyimpanan dengan galat SQL mentah (kolomnya 1 karakter).
            String dcTeks = cell(row, 1);
            if (!dcTeks.equalsIgnoreCase("D") && !dcTeks.equalsIgnoreCase("C"))
                b.errors.add(new String[]{ baris, "Kolom B [D/C] wajib diisi \"D\" atau \"C\""
                        + (dcTeks.isBlank() ? " (saat ini kosong)." : " (saat ini \"" + dcTeks + "\").") });

            // REQ_AUSY_00067 — isian yang lebih panjang dari kolomnya di basis data disebut di sini,
            // per baris dan kolom, bukan lolos lalu gagal saat disimpan dengan galat SQL mentah.
            for (int[] col : KOLOM_LEBAR) {
                String v = cell(row, col[0]);
                if (col[0] == 9) v = v.replace(".", "").replace(",", "");   // cost centre disimpan begitu
                if (v.length() > col[1])
                    b.errors.add(new String[]{ baris, LABEL_KOLOM[col[0]] + " maksimal " + col[1]
                            + " karakter — saat ini " + v.length() + " (\"" + v + "\")." });
            }
        }
        // REQ_AUSY_00024 — total Debit harus sama dengan total Credit. Dulu berkas yang tidak
        // balance tetap masuk dengan sekadar peringatan, lalu baru tertahan di checker.
        // Diperiksa hanya bila semua nominal terbaca, supaya angkanya tidak menyesatkan.
        if (b.errors.isEmpty() && cekDebit.compareTo(cekCredit) != 0)
            b.errors.add(new String[]{ "Total", "Total Debit " + nominal(cekDebit)
                    + " tidak sama dengan total Credit " + nominal(cekCredit) + " (selisih "
                    + nominal(cekDebit.subtract(cekCredit).abs()) + ")." });
        if (!b.errors.isEmpty()) {
            b.message = "Upload gagal: ada isian yang tidak sesuai ketentuan kolom. "
                    + "Perbaiki di file Excel lalu unggah ulang.";
            return b;
        }

        // Pass 3: bentuk baris GL.
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
            g.setGltrfy(GlTextRule.trim(cell(row, 12)));
            g.setGltrcd(dc);
            g.setGlprdc(GlTextRule.trim(cell(row, 2)));
            String akun = GlTextRule.trim(cell(row, 4));
            g.setGltrac(akun);
            g.setGltrpd(ddmmyy);
            // REQ_AUSY_00065: GL Name selalu dari Manajemen Kode GL. Isian kolom F diabaikan —
            // user bisa saja mengubahnya di Sheet1, dan nama yang tersimpan tidak boleh ikut berubah.
            g.setGlName(namaGl.getOrDefault(akun, ""));
            String desc = GlTextRule.trim(cell(row, 6));
            if (desc.length() > 40) desc = desc.substring(0, 40);   // lebar kolom ACCTXT
            g.setGltrds(desc);
            g.setGltrbr(GlTextRule.trim(cell(row, 7)));
            g.setBrName(GlTextRule.trim(cell(row, 8)));
            // Titik & koma dibuang — cost centre itu kode angka, sama seperti source asli.
            // Karakter lain sudah dijamin bersih oleh pemeriksaan di atas.
            g.setGltrcc(cell(row, 9).replace(".", "").replace(",", ""));
            g.setGltrcy(GlTextRule.trim(cell(row, 11)));
            g.setGltram(amt);
            g.setGlflag(GlTextRule.trim(cell(row, 14)));
            g.setGlresp(GlTextRule.trim(cell(row, 15)));
            g.setCsName(GlTextRule.trim(cell(row, 10)));
            g.setGleqam(amt);
            g.setGlotam(amt);
            g.setGltrpd1(ddmmyy);
            g.setStatusPrint("BELUM UPLOAD");
            g.setGlsrc("p");
            g.setGlotcc(GlTextRule.trim(cell(row, 11)));
            g.setStatusDual("PENDING");
            b.rows.add(g);
            if (dc.equals("D")) b.debit = b.debit.add(amt); else b.credit = b.credit.add(amt);
        }
        // REQ_AUSY_00066/00070: running number referensi 6 digit per batch.
        if (b.rows.size() > Referensi.MAKS_URUT) {
            b.message = "Upload gagal: berkas berisi " + b.rows.size() + " baris, melebihi batas "
                    + "999.999 baris per batch. Pecah menjadi beberapa berkas lalu unggah satu per satu.";
            b.rows.clear();
        }
        return b;
    }

    /**
     * Pesan untuk pengguna saat unggahan gagal karena galat tak terduga (REQ_AUSY_00067). Galat
     * aslinya — bisa berisi query SQL dan nama constraint — hanya untuk log server, tidak pernah
     * ditampilkan di layar.
     */
    public static String pesanGagal(Exception e) {
        if (e instanceof org.springframework.dao.DataAccessException)
            return "Upload gagal: data tidak dapat disimpan ke basis data. Tidak ada data yang tersimpan."
                    + " Periksa kembali isian berkas, lalu unggah ulang. Bila masih gagal, hubungi admin"
                    + " dengan menyebut waktu kejadian.";
        return "Gagal membaca file Excel — pastikan berkas berformat .xlsx sesuai template MNC dan tidak"
                + " sedang dibuka/terkunci, lalu unggah ulang.";
    }

    /**
     * Simpan baris hasil {@link #baca} sebagai isi batch {@code paket} milik {@code username}:
     * nomor batch, pemilik, dan GLTRRF baru per baris.
     */
    public void simpanBaris(List<GlTabel> rows, String paket, String username) {
        // REQ_AUSY_00066: running number per batch. Upload baru mulai dari 000001; Bulk Edit
        // menghapus baris unggahan lalu menyimpan ulang, sehingga juga mulai dari awal — kecuali
        // ada baris manual yang tetap tinggal di batch itu, maka dilanjutkan setelahnya.
        int urut = gl.maxUrutReferensi(paket);
        for (GlTabel g : rows) {
            g.setNoPaket(paket);
            // username utuh: pemiliknya harus bisa dicocokkan untuk pembatasan data per maker (BUG_AUSY_00138).
            // Pemotongan ke 10 karakter tetap dilakukan GltrrfFormatter saat menulis file ACCTXT.
            g.setGltrus(username);
            g.setGltrrf(Referensi.of(paket, ++urut));
            gl.save(g);
        }
    }

    @Transactional
    public UploadResult process(InputStream in, String fileName, String username) throws Exception {
        UploadResult res = new UploadResult();
        try (Workbook wb = WorkbookFactory.create(in)) {
            Bacaan b = baca(wb, LocalDate.now(), "hari ini");
            res.errors.addAll(b.errors);
            if (!b.ok()) { res.message = b.message; return res; }

            // REQ_AUSY_00031: nomor batch mulai dari 1 setiap hari, digabung tanggal supaya tetap
            // unik (20261008001). Tanpa pemisah "-": referensi GLTRRF berbentuk
            // <batch>-<transmisi>-<urut> dan dipecah per "-" saat mencetak jurnal. Panjangnya
            // 11 karakter; di referensi tahunnya dipendekkan jadi YYMMDDXXX (lihat Referensi).
            String tgl = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String paket = tgl + String.format("%03d", gl.maxUrutBatchHarian(tgl) + 1);
            simpanBaris(b.rows, paket, username);
            int n = b.rows.size();
            res.debit = b.debit; res.credit = b.credit;

            res.rows = n;
            res.paket = paket;
            res.balanced = res.debit.compareTo(res.credit) == 0;

            TUpload t = new TUpload();
            t.setSessId(java.util.UUID.randomUUID().toString().substring(0, 20));
            t.setFileName(fileName);
            t.setNoPaket(paket);                  // REQ_AUSY_00036: nama file per batch
            t.setJmlData(n);
            t.setUserId(username);
            t.setStatus("Success");
            t.setAdddt(LocalDateTime.now());
            tUploads.save(t);

            audit.transaction(username, "UPLOAD", "Batch " + BatchLabel.label(paket) + " — " + n + " baris (D=" + nominal(res.debit)
                    + ", C=" + nominal(res.credit) + ", " + (res.balanced ? "balance" : "TIDAK balance") + ")");

            res.success = true;
            // Keterangan "N isian dirapikan" dilepas atas permintaan pengguna (BUG_AUSY_00180):
            // pembersihan karakter dianggap urusan internal, tidak perlu diumumkan tiap unggahan.
            // Berkas yang tidak balance sudah ditolak di pemeriksaan (REQ_AUSY_00024).
            res.message = "Upload berhasil — Batch " + BatchLabel.label(paket) + " menunggu approval Checker (" + n + " baris jurnal). "
                          + "Debit = Credit (balance).";
            return res;
        }
    }
}
