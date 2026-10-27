package com.mnc.ausy.service;

/**
 * Nomor referensi transaksi (GLTRRF) di berkas ACCTXT: {@code YYMMDDXXX-TT-NNNNNN}.
 *
 * <ul>
 *   <li>{@code YYMMDDXXX} — nomor batch dengan tahun 2 digit (REQ_AUSY_00068). Nomor batch di
 *       basis data tetap {@code YYYYMMDDXXX}; hanya referensinya yang dipendekkan.</li>
 *   <li>{@code TT} — nomor transmisi, selalu {@code 01}.</li>
 *   <li>{@code NNNNNN} — running number 6 digit, <b>di-reset per hari</b>: batch pertama hari itu
 *       mulai dari 000001, batch berikutnya di hari yang sama melanjutkan (REQ_AUSY_00066).
 *       "Hari" adalah tanggal di nomor batch, bukan tanggal saat baris disimpan.</li>
 * </ul>
 *
 * Dulu running number diambil dari satu penghitung global yang tidak pernah di-reset, sehingga
 * referensi terus memanjang: lewat 99.999 baris ia tidak muat lagi di kolom 20 karakter, dan
 * upload besar gagal dengan galat SQL mentah (REQ_AUSY_00067). Panjang referensi kini tetap 19
 * karakter (20 bila nomor batch hari itu sudah 4 digit).
 */
public final class Referensi {
    public static final String TRANSMISI = "01";
    /** Running number 6 digit — batas baris per hari (semua batch bertanggal sama). */
    public static final int MAKS_URUT = 999_999;

    private Referensi() {}

    /** Tanggal {@code yyyyMMdd} di depan nomor batch, atau null untuk batch lama tanpa tanggal. */
    public static String tanggal(String paket) {
        String p = paket == null ? "" : paket.trim();
        return p.matches("\\d{8}\\d{3,4}") ? p.substring(0, 8) : null;
    }

    public static String of(String paket, int urut) {
        String p = paket == null ? "" : paket.trim();
        if (p.matches("\\d{8}\\d{3,4}")) p = p.substring(2);   // YYYYMMDDXXX -> YYMMDDXXX
        return p + "-" + TRANSMISI + "-" + String.format("%06d", urut);
    }
}
