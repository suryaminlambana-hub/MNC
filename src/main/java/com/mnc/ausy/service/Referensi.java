package com.mnc.ausy.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Nomor referensi transaksi (GLTRRF) di berkas ACCTXT: {@code YYYYMMDD-TT-NNNNNNN}, selalu 19
 * karakter (REQ_AUSY_00068).
 *
 * <ul>
 *   <li>{@code YYYYMMDD} — tanggal batch (tanggal di nomor batch). Kode batch 3 digit tidak lagi
 *       ikut di referensi.</li>
 *   <li>{@code TT} — nomor transmisi, selalu {@code 01}.</li>
 *   <li>{@code NNNNNNN} — running number 7 digit, <b>di-reset per hari</b>: batch pertama hari itu
 *       mulai dari 0000001, batch berikutnya di hari yang sama melanjutkan (REQ_AUSY_00066).</li>
 * </ul>
 *
 * Karena kode batch tidak ikut, keunikan referensi sepenuhnya bergantung pada running number
 * harian — maka penomoran selalu dilakukan di bawah kunci per tanggal
 * ({@link UploadService#kunciPenomoran}).
 *
 * Dulu running number diambil dari satu penghitung global yang tidak pernah di-reset, sehingga
 * referensi terus memanjang: lewat 99.999 baris ia tidak muat lagi di kolom 20 karakter, dan
 * upload besar gagal dengan galat SQL mentah (REQ_AUSY_00067).
 */
public final class Referensi {
    public static final String TRANSMISI = "01";
    /** Running number 7 digit — batas baris per hari (semua batch bertanggal sama). */
    public static final int MAKS_URUT = 9_999_999;

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private Referensi() {}

    /** Tanggal {@code yyyyMMdd} di depan nomor batch, atau null untuk batch lama tanpa tanggal. */
    public static String tanggal(String paket) {
        String p = paket == null ? "" : paket.trim();
        return p.matches("\\d{8}\\d{3,4}") ? p.substring(0, 8) : null;
    }

    /**
     * Tanggal yang dipakai referensi baris di batch {@code paket}: tanggal batch, atau hari ini
     * untuk batch lama yang nomornya bukan tanggal.
     */
    public static String tanggalReferensi(String paket) {
        String t = tanggal(paket);
        return t != null ? t : LocalDate.now().format(YYYYMMDD);
    }

    public static String of(String paket, int urut) {
        return tanggalReferensi(paket) + "-" + TRANSMISI + "-" + String.format("%07d", urut);
    }
}
