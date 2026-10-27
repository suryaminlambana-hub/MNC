package com.mnc.ausy.service;

/**
 * Nomor referensi transaksi (GLTRRF) di berkas ACCTXT: {@code YYMMDDXXX-TT-NNNNNN}.
 *
 * <ul>
 *   <li>{@code YYMMDDXXX} — nomor batch dengan tahun 2 digit (REQ_AUSY_00068). Nomor batch di
 *       basis data tetap {@code YYYYMMDDXXX}; hanya referensinya yang dipendekkan.</li>
 *   <li>{@code TT} — nomor transmisi, selalu {@code 01}.</li>
 *   <li>{@code NNNNNN} — running number 6 digit yang mulai dari 000001 di setiap batch
 *       (REQ_AUSY_00066).</li>
 * </ul>
 *
 * Dulu running number diambil dari satu penghitung global yang tidak pernah di-reset, sehingga
 * referensi terus memanjang: lewat 99.999 baris ia tidak muat lagi di kolom 20 karakter, dan
 * upload besar gagal dengan galat SQL mentah (REQ_AUSY_00067). Batch sudah unik, jadi nomor urut
 * cukup unik di dalam batch — panjang referensi kini tetap 19 karakter (20 bila nomor batch hari
 * itu sudah 4 digit).
 */
public final class Referensi {
    public static final String TRANSMISI = "01";
    /** Running number 6 digit — batas baris per batch. */
    public static final int MAKS_URUT = 999_999;

    private Referensi() {}

    public static String of(String paket, int urut) {
        String p = paket == null ? "" : paket.trim();
        if (p.matches("\\d{8}\\d{3,4}")) p = p.substring(2);   // YYYYMMDDXXX -> YYMMDDXXX
        return p + "-" + TRANSMISI + "-" + String.format("%06d", urut);
    }
}
