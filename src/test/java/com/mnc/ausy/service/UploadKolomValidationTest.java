package com.mnc.ausy.service;

import com.mnc.ausy.domain.GlCode;
import com.mnc.ausy.repo.GlCodeRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ketentuan bentuk isian per kolom pada unggahan Excel —
 * BUG_AUSY_00190 (Currency wajib IDR), BUG_AUSY_00191 (penyaringan simbol dicabut),
 * BUG_AUSY_00192 (kolom kode hanya angka) dan BUG_AUSY_00193 (cabang & cost centre
 * boleh angka serta tanda petik satu).
 *
 * <p>Yang diuji adalah jalur penolakan {@link UploadService#process}, yang selalu kembali
 * SEBELUM apa pun disimpan — karena itu repository-nya boleh null: kalau suatu saat ada
 * penyimpanan yang bocor ke sisi validasi, tesnya akan gagal dengan NPE, bukan lolos diam-diam.
 */
class UploadKolomValidationTest {

    private static final DateTimeFormatter IN = DateTimeFormatter.ofPattern("d-MMM-yy", Locale.ENGLISH);

    /** Penanda di data uji: tulis sel ini sebagai nilai galat Excel, bukan teks. */
    private static final String GALAT = "<#N/A>";

    /** Satu baris data yang seluruh kolomnya sah. Dipakai sebagai dasar, lalu dirusak satu kolom. */
    private static String[] barisSah() {
        String[] c = new String[17];
        java.util.Arrays.fill(c, "");
        c[0]  = LocalDate.now().format(IN);     // A Posting_Date
        c[1]  = "D";                            // B D/C
        c[2]  = "01";                           // C Product Code
        c[3]  = "Giro";                         // D Product Name
        c[4]  = "1010100001";                   // E Transaction_Account
        c[5]  = "Kas Besar";                    // F Gl_Name
        c[6]  = "Setoran tunai";                // G Transaction_Description
        c[7]  = "001";                          // H Branch
        c[8]  = "Kantor Pusat";                 // I Branch_Name
        c[9]  = "00100";                        // J Cost_Center
        c[10] = "Operasional";                  // K Cost_Center_Name
        c[11] = "IDR";                          // L Currency
        c[12] = "";                             // M Foreign_Curr
        c[13] = "1000000";                      // N Transaction_Amount
        c[14] = "";                             // O Flag
        c[15] = "";                             // P Responding_Branch
        c[16] = "";                             // Q Responding_Branch_Name
        return c;
    }

    /** Berkas satu-sheet bernama "Sheet1", header MNC, lalu baris-baris yang diberikan. */
    private static byte[] berkas(String[]... baris) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Sheet1");
            String[] head = {
                    "Posting_Date", "D/C", "Product Code", "Product Name", "Transaction_Account", "Gl_Name",
                    "Transaction_Description", "Branch", "Branch_Name", "Cost_Center", "Cost_Center_Name",
                    "Currency", "Foreign_Curr", "Transaction_Amount", "Flag", "Responding_Branch",
                    "Responding_Branch_Name" };
            Row hr = sh.createRow(0);
            for (int i = 0; i < head.length; i++) hr.createCell(i).setCellValue(head[i]);
            for (int r = 0; r < baris.length; r++) {
                Row row = sh.createRow(r + 1);
                for (int c = 0; c < baris[r].length; c++) {
                    // "#N/A" ditulis sebagai sel GALAT sungguhan, bukan teks berbunyi "#N/A" —
                    // itulah yang Excel simpan saat VLOOKUP tidak menemukan padanan.
                    if (GALAT.equals(baris[r][c]))
                        row.createCell(c).setCellErrorValue(org.apache.poi.ss.usermodel.FormulaError.NA.getCode());
                    else
                        row.createCell(c).setCellValue(baris[r][c]);
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static UploadResult proses(String[]... baris) throws Exception {
        // Bawaan: seluruh kode yang muncul di data uji dianggap terdaftar, supaya tes kolom
        // LAIN tidak ikut gagal karena validasi kode GL (REQ_AUSY_00059). Tes yang memang
        // menguji validasi itu memakai overload di bawah dan menyebut daftarnya sendiri.
        java.util.Set<String> kode = new java.util.LinkedHashSet<>();
        for (String[] r : baris) if (r.length > 4 && r[4] != null && !r[4].isBlank()) kode.add(r[4].trim());
        return proses(kode, baris);
    }

    /**
     * Master Kode GL yang berlaku untuk pengujian ini (REQ_AUSY_00059).
     *
     * Hanya {@code findAll} yang dipakai sisi validasi, jadi sisanya dibiarkan tidak didukung:
     * kalau suatu hari validasinya menanyakan hal lain, tesnya gagal dengan jelas di method itu
     * dan bukan diam-diam memakai jawaban kosong.
     */
    private static GlCodeRepository masterKode(java.util.Set<String> kode) {
        return (GlCodeRepository) java.lang.reflect.Proxy.newProxyInstance(
                GlCodeRepository.class.getClassLoader(),
                new Class<?>[]{ GlCodeRepository.class },
                (proxy, method, args) -> {
                    if (!"findAll".equals(method.getName()) || (args != null && args.length > 0))
                        throw new UnsupportedOperationException("tak terduga: " + method.getName());
                    java.util.List<GlCode> out = new java.util.ArrayList<>();
                    for (String k : kode) { GlCode c = new GlCode(); c.setCode(k); out.add(c); }
                    return out;
                });
    }

    private static UploadResult proses(java.util.Set<String> kodeTerdaftar, String[]... baris) throws Exception {
        return new UploadService(null, null, null, null, masterKode(kodeTerdaftar))
                .process(new ByteArrayInputStream(berkas(baris)), "uji.xlsx", "penguji");
    }

    /** Seluruh pesan kesalahan untuk nomor baris Excel tertentu, digabung jadi satu. */
    private static String pesanBaris(UploadResult res, int barisExcel) {
        StringBuilder sb = new StringBuilder();
        for (String[] e : res.errors)
            if (e[0].equals(String.valueOf(barisExcel))) sb.append(e[1]).append(" | ");
        return sb.toString();
    }

    // ---- BUG_AUSY_00190: Currency wajib IDR ----

    @Test
    void currencySelainIdrDitolak() throws Exception {
        String[] b = barisSah(); b[11] = "USD";
        UploadResult res = proses(b);

        assertFalse(res.success);
        assertEquals(0, res.rows, "tidak satu baris pun boleh tersimpan");
        assertTrue(res.message.contains("tidak sesuai ketentuan kolom"), res.message);
        assertTrue(pesanBaris(res, 2).contains("Currency"), pesanBaris(res, 2));
        assertTrue(pesanBaris(res, 2).contains("USD"), "nilainya ikut disebut: " + pesanBaris(res, 2));
    }

    @Test
    void currencyKosongDitolak() throws Exception {
        String[] b = barisSah(); b[11] = "";
        UploadResult res = proses(b);

        assertFalse(res.success);
        assertTrue(pesanBaris(res, 2).contains("Currency"), pesanBaris(res, 2));
        assertTrue(pesanBaris(res, 2).contains("kosong"), pesanBaris(res, 2));
    }

    @Test
    void currencyIdrHurufKecilTetapDiterima() throws Exception {
        String[] b = barisSah(); b[11] = "idr";
        assertEquals("", pesanBaris(currencyAtauKolomLain(b), 2), "besar-kecil huruf tidak dipersoalkan");
    }

    // ---- BUG_AUSY_00192: Product Code, Transaction_Account, Responding_Branch hanya angka ----

    @Test
    void productCodeBukanAngkaDitolak() throws Exception {
        String[] b = barisSah(); b[2] = "A1";
        assertTrue(pesanBaris(proses(b), 2).contains("Product Code"), "Product Code harus ditolak");
    }

    @Test
    void transactionAccountBukanAngkaDitolak() throws Exception {
        String[] b = barisSah(); b[4] = "1010-100";
        assertTrue(pesanBaris(proses(b), 2).contains("Transaction_Account"), "Transaction_Account harus ditolak");
    }

    /**
     * Responding_Branch (kolom P) TIDAK divalidasi — REQ_AUSY_00003.
     *
     * Isinya rumus {@code IF(O{n}=2,$H$2,"")} yang menyalin cabang dari H2, jadi tanda petik
     * penjaga angka nol di depan ikut terbawa ke sana. Kalau kolom ini ikut dipatok angka saja,
     * berkas yang sah ditolak gara-gara sel yang tidak pernah diketik makernya.
     */
    @Test
    void respondingBranchTidakIkutDivalidasi() throws Exception {
        String[] b = barisSah(); b[15] = "'001";
        assertEquals("", pesanBaris(currencyAtauKolomLain(b), 2),
                "kolom rumus tidak boleh dipersoalkan, tapi masih: " + pesanBaris(currencyAtauKolomLain(b), 2));
    }

    @Test
    void respondingBranchKosongTetapBoleh() throws Exception {
        String[] b = barisSah(); b[15] = "";
        assertEquals("", pesanBaris(currencyAtauKolomLain(b), 2),
                "hanya baris antar-cabang yang mengisinya, jadi kosong bukan kesalahan");
    }

    /** Product Code & Transaction_Account: tanda petik satu pun TIDAK diizinkan (REQ_AUSY_00003). */
    @Test
    void productCodeDenganTandaPetikTetapDitolak() throws Exception {
        String[] b = barisSah(); b[2] = "'01";
        assertTrue(pesanBaris(proses(b), 2).contains("Product Code"),
                "tanda petik hanya untuk Branch & Cost_Center, bukan kolom ini");
    }

    // ---- BUG_AUSY_00193: Branch & Cost_Center — angka dan tanda petik satu ----

    @Test
    void branchDenganTandaPetikDiterima() throws Exception {
        String[] b = barisSah(); b[7] = "'001"; b[9] = "'00100";
        assertEquals("", pesanBaris(currencyAtauKolomLain(b), 2), "tanda petik satu diizinkan");
    }

    @Test
    void branchDenganTandaLainDitolak() throws Exception {
        String[] b = barisSah(); b[7] = "00-1";
        assertTrue(pesanBaris(proses(b), 2).contains("Branch"), "Branch harus ditolak");
    }

    @Test
    void costCenterDenganHurufDitolak() throws Exception {
        String[] b = barisSah(); b[9] = "KP01";
        assertTrue(pesanBaris(proses(b), 2).contains("Cost_Center"), "Cost_Center harus ditolak");
    }

    // ---- Sel galat Excel (#N/A) — kode diketik sebagai teks / tidak terdaftar ----

    /**
     * Inti laporan "tanda petik masih bisa bypass".
     *
     * Tanda petik di depan angka BUKAN bagian dari nilai sel — Excel memakainya untuk menandai
     * sel itu teks, dan yang tersimpan tetap "10911513". Jadi tidak ada yang bisa disaring dari
     * nilainya; yang rusak adalah VLOOKUP-nya, yang lalu menghasilkan #N/A di Gl_Name.
     */
    @Test
    void gagalVlookupDitolak() throws Exception {
        String[] b = barisSah();
        b[4] = "10911513";      // tersimpan sebagai teks, persis seperti hasil ketik '10911513
        b[5] = GALAT;           // maka Gl_Name = #N/A
        UploadResult res = proses(b);

        assertFalse(res.success);
        assertEquals(0, res.rows, "tidak satu baris pun boleh tersimpan");
        assertTrue(pesanBaris(res, 2).contains("Gl_Name"), pesanBaris(res, 2));
        assertTrue(pesanBaris(res, 2).contains("#N/A"), pesanBaris(res, 2));
    }

    @Test
    void galatDiKolomPencarianLainJugaDitolak() throws Exception {
        String[] b = barisSah(); b[10] = GALAT;      // Cost_Center_Name
        assertTrue(pesanBaris(proses(b), 2).contains("Cost_Center_Name"), pesanBaris(proses(b), 2));
    }

    /**
     * Baris template yang BELUM diisi tidak boleh ikut ditolak.
     *
     * Berkas MNC datang dengan 167 baris berumus; selama kolom E kosong, VLOOKUP di F/I/K/Q
     * semuanya berbunyi #N/A. Kalau baris begitu ikut diperiksa, tidak akan ada satu berkas pun
     * yang bisa diunggah.
     */
    @Test
    void barisKosongBerNaTidakIkutDitolak() throws Exception {
        String[] kosong = new String[17];
        java.util.Arrays.fill(kosong, "");
        kosong[5] = GALAT; kosong[8] = GALAT; kosong[10] = GALAT; kosong[16] = GALAT;

        String[] rusak = barisSah(); rusak[11] = "USD";     // supaya berkasnya tetap ditolak
        UploadResult res = proses(rusak, kosong);

        assertEquals("", pesanBaris(res, 3), "baris kosong tidak boleh dipersoalkan: " + pesanBaris(res, 3));
        assertTrue(pesanBaris(res, 2).contains("Currency"), pesanBaris(res, 2));
    }

    /**
     * Angka-sebagai-teks yang pencariannya BERHASIL tetap diterima. Berkas MNC memakai tanda
     * petik untuk menjaga angka nol di depan, jadi menolak teks begitu saja akan membuang data
     * yang sah — yang ditolak adalah pencarian yang gagal, bukan bentuk selnya.
     */
    @Test
    void angkaSebagaiTeksDenganPencarianBerhasilTetapDiterima() throws Exception {
        String[] b = barisSah(); b[4] = "10911513"; b[5] = "PYMAD KYD-MODAL KERJA - KORPORASI";
        assertEquals("", pesanBaris(currencyAtauKolomLain(b), 2), pesanBaris(currencyAtauKolomLain(b), 2));
    }

    // ---- BUG_AUSY_00191: simbol di kolom teks bebas tidak lagi ditolak ----

    @Test
    void simbolDiKolomTeksBebasTidakLagiDitolak() throws Exception {
        String[] bersih = barisSah();
        bersih[5] = "Kas Besar (Non-Operasional) & Lain-lain / Cab. Pusat";   // Gl_Name
        bersih[6] = "Setoran tunai: Rp1.000.000, a/n PT. ABC & Co.";          // Transaction_Description
        bersih[3] = "Giro - Valas (USD)";                                     // Product Name
        bersih[8] = "KCP Sudirman - Jkt";                                     // Branch_Name
        bersih[10] = "Ops. & Umum";                                           // Cost_Center_Name

        // Baris kedua sengaja dibuat salah supaya berkasnya tetap ditolak dan tidak ada
        // penyimpanan yang dijalankan — isi res.errors-lah buktinya.
        String[] rusak = barisSah(); rusak[11] = "USD";

        UploadResult res = proses(bersih, rusak);

        assertFalse(res.errors.isEmpty(), "baris kedua memang harus ditolak");
        assertEquals("", pesanBaris(res, 2),
                "baris penuh tanda baca tidak boleh lagi dipersoalkan, tapi masih: " + pesanBaris(res, 2));
        assertTrue(pesanBaris(res, 3).contains("Currency"), pesanBaris(res, 3));
    }

    /**
     * Jalankan baris yang seharusnya LOLOS validasi kolom, dipasangkan dengan satu baris yang
     * pasti ditolak. Tanpa baris pasangan itu berkasnya lolos seluruhnya dan {@code process}
     * meneruskan ke penyimpanan — yang repository-nya null di tes ini.
     */
    // ---- REQ_AUSY_00024: nominal minus & tidak balance ditolak ----

    @Test
    void nominalMinusDitolak() throws Exception {
        String[] b = barisSah(); b[13] = "-500000";
        assertTrue(pesanBaris(currencyAtauKolomLain(b), 2).contains("tidak boleh minus"));
    }

    @Test
    void nominalBukanAngkaDitolak() throws Exception {
        String[] b = barisSah(); b[13] = "lima ratus";
        assertTrue(pesanBaris(currencyAtauKolomLain(b), 2).contains("bukan angka"));
    }

    @Test
    void totalTidakBalanceDitolak() throws Exception {
        String[] debit = barisSah();                         // D 1.000.000
        String[] credit = barisSah(); credit[1] = "C"; credit[13] = "900000";
        UploadResult res = proses(debit, credit);
        assertFalse(res.success);
        String total = pesanBaris(res, -1) + res.errors.stream()
                .filter(e -> e[0].equals("Total")).map(e -> e[1]).findFirst().orElse("");
        assertTrue(total.contains("tidak sama dengan total Credit"), "pesan: " + total);
        assertTrue(total.contains("selisih 100.000,00"), "pesan: " + total);
    }

    private static UploadResult currencyAtauKolomLain(String[] barisUji) throws Exception {
        String[] rusak = barisSah(); rusak[2] = "XX";       // Product Code jelas bukan angka
        return proses(barisUji, rusak);
    }

    // ---- REQ_AUSY_00059: Transaction Account wajib terdaftar di Manajemen Kode GL ----

    @Test
    void transactionAccountTidakTerdaftarDitolak() throws Exception {
        String[] b = barisSah(); b[4] = "9999999999";
        UploadResult res = proses(java.util.Set.of("1010100001"), b);

        assertFalse(res.success);
        assertEquals(0, res.rows, "tidak satu baris pun boleh tersimpan");
        assertTrue(pesanBaris(res, 2).contains("tidak terdaftar"), pesanBaris(res, 2));
        assertTrue(pesanBaris(res, 2).contains("9999999999"), "kodenya ikut disebut: " + pesanBaris(res, 2));
    }

    @Test
    void barisTerisiTanpaTransactionAccountDitolak() throws Exception {
        // Kolom E juga penanda "baris berisi data", jadi yang ditolak adalah baris yang jelas
        // sedang diisi (ada D/C, keterangan dan nominal) tapi akunnya tertinggal. Dipasangkan
        // dengan satu baris sah supaya berkasnya tidak ditolak lebih dulu sebagai "tidak ada
        // baris data" — itu jalur lain, dan bukan yang sedang diuji di sini.
        String[] sah = barisSah();
        String[] tanpaAkun = barisSah(); tanpaAkun[4] = ""; tanpaAkun[1] = "C";
        UploadResult res = proses(java.util.Set.of(sah[4]), sah, tanpaAkun);

        assertFalse(res.success);
        assertTrue(pesanBaris(res, 3).contains("Transaction_Account"), "baris 3: " + pesanBaris(res, 3));
        assertTrue(pesanBaris(res, 3).contains("belum diisi"), "baris 3: " + pesanBaris(res, 3));
    }

    @Test
    void berkasTanpaSatuPunAkunDitolakSebagaiTidakAdaData() throws Exception {
        // Seluruh barisnya tanpa akun: berkasnya ditolak lebih dulu di jalur "tidak ada baris
        // data", bukan sebagai kesalahan per baris. Dua jalur berbeda, dua pesan berbeda.
        String[] b = barisSah(); b[4] = "";
        UploadResult res = proses(java.util.Set.of("1010100001"), b);

        assertFalse(res.success);
        assertTrue(res.message.contains("Tidak ada baris data"), res.message);
    }

    @Test
    void barisBenarBenarKosongTidakDianggapSalah() throws Exception {
        // Template MNC datang dengan 167 baris kosong. Kalau kolom E kosong ditolak mentah,
        // tidak ada satu berkas pun yang bisa diunggah.
        String[] kosong = new String[17];
        java.util.Arrays.fill(kosong, "");
        kosong[0] = LocalDate.now().format(IN);     // seperti rumus =NOW() di template
        String[] sah = barisSah();
        UploadResult res = proses(java.util.Set.of(sah[4]), sah, kosong);

        assertFalse(pesanBaris(res, 3).contains("Transaction_Account"),
                "baris kosong tidak boleh dikeluhkan: " + pesanBaris(res, 3));
    }

    @Test
    void transactionAccountTerdaftarDiterima() throws Exception {
        // Baris yang sah seutuhnya tidak boleh tersangkut di validasi kolom. Penyimpanannya
        // memang gagal (repository null), jadi yang diperiksa: TIDAK ada keluhan soal kode GL.
        String[] b = barisSah();
        try {
            UploadResult res = proses(java.util.Set.of(b[4]), b);
            assertFalse(pesanBaris(res, 2).contains("tidak terdaftar"), pesanBaris(res, 2));
        } catch (NullPointerException lolosValidasi) {
            // sampai ke tahap simpan = validasi kolomnya lulus
        }
    }

    @Test
    void barisBermasalahDisebutNomornya() throws Exception {
        // Validasi berjalan per baris: yang salah baris ketiga, bukan seluruh berkas.
        String[] sah = barisSah();
        String[] salah = barisSah(); salah[4] = "8888888888"; salah[1] = "C";
        UploadResult res = proses(java.util.Set.of(sah[4]), sah, salah);

        assertFalse(res.success);
        assertTrue(pesanBaris(res, 3).contains("8888888888"), "baris 3: " + pesanBaris(res, 3));
        assertFalse(pesanBaris(res, 2).contains("tidak terdaftar"), "baris 2 bersih: " + pesanBaris(res, 2));
    }
}
