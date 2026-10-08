package com.mnc.ausy.service;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Nominal dalam format Indonesia: titik pemisah ribuan, koma pemisah desimal ({@code 1.000.000,50}).
 *
 * Satu tempat untuk menulis DAN membaca format ini (REQ_AUSY_00029). Excel bulk edit diekspor
 * dengan format ini lalu diunggah ulang, jadi pembacanya harus mengerti persis apa yang ditulis
 * penulisnya. Dulu pembaca bulk edit hanya membuang koma: {@code "1.000.000,50"} gagal diurai dan
 * diam-diam menjadi 0, sehingga upload ulang akan menyetel semua amount menjadi nol.
 */
public final class Nominal {

    private Nominal() {}

    /** {@code 1000000.5} menjadi {@code "1.000.000,50"}. Null dianggap 0. */
    public static String format(BigDecimal v) {
        DecimalFormatSymbols s = new DecimalFormatSymbols(Locale.ROOT);
        s.setGroupingSeparator('.');
        s.setDecimalSeparator(',');
        return new DecimalFormat("#,##0.00", s).format(v == null ? BigDecimal.ZERO : v);
    }

    /**
     * Baca nominal yang diketik orang atau terbaca dari sel Excel. Null bila tidak bisa dibaca;
     * pemanggil yang memutuskan menolaknya, bukan diam-diam menganggapnya 0.
     *
     * <ul>
     *   <li>Ada titik DAN koma: pemisah yang paling belakang adalah desimal
     *       ({@code 1.000.000,50} atau {@code 1,000,000.50}).</li>
     *   <li>Hanya koma: satu koma = desimal ({@code 1000000,50}); lebih dari satu = ribuan.</li>
     *   <li>Hanya titik: lebih dari satu = ribuan ({@code 1.000.000}); satu titik = desimal, karena
     *       begitulah nilai sel Excel numerik terbaca ({@code 1000000.5}).</li>
     * </ul>
     */
    public static BigDecimal parse(String raw) {
        if (raw == null) return null;
        String s = raw.replace(" ", "").replace(" ", "").trim();
        if (s.isEmpty()) return null;
        int dot = s.lastIndexOf('.'), comma = s.lastIndexOf(',');
        if (dot >= 0 && comma >= 0) {
            s = comma > dot ? s.replace(".", "").replace(',', '.') : s.replace(",", "");
        } else if (comma >= 0) {
            s = s.indexOf(',') == comma ? s.replace(',', '.') : s.replace(",", "");
        } else if (dot >= 0 && s.indexOf('.') != dot) {
            s = s.replace(".", "");
        }
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }
}
